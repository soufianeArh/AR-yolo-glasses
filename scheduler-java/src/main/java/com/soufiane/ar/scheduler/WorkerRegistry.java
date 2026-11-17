package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.HeartbeatAck;
import com.soufiane.ar.proto.v1.InferenceWorkerGrpc;
import com.soufiane.ar.proto.v1.WorkerHeartbeat;
import io.grpc.ConnectivityState;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Every C++ worker the scheduler knows about, built ONLY from heartbeats.
 *
 * A worker's first heartbeat registers it (address, models, threads) - nothing is configured
 * by hand. A worker that stops sending heartbeats for longer than the timeout is unhealthy
 * and gets no new frames; when its heartbeats come back, it is healthy again.
 *
 * Load = frames this scheduler has sent to a worker and not yet got an answer for. This is
 * accurate because the workers ABORT an inference when its deadline passes (Step 5): the
 * scheduler's count drops at the deadline at the same moment the worker stops computing.
 */
final class WorkerRegistry implements AutoCloseable {

    /** One C++ worker, as last reported by its heartbeat plus what this scheduler tracks. */
    static final class Worker {
        final String id;
        volatile String address;
        volatile List<String> models = List.of();
        volatile int threads;
        volatile int reportedQueueDepth;
        volatile float avgInferMs;
        volatile long lastSeenNanos;
        volatile boolean markedHealthy = true;   // last state we logged, for transition messages

        /** Frames sent to the worker and not answered yet. Changed only through the registry. */
        final AtomicInteger inFlight = new AtomicInteger();
        /** When this worker was last picked: the tie-break, so idle workers share the work. */
        long lastPickedNanos;
        final AtomicLong sent = new AtomicLong();
        final AtomicLong ok = new AtomicLong();
        final AtomicLong failed = new AtomicLong();

        volatile ManagedChannel channel;
        volatile InferenceWorkerGrpc.InferenceWorkerFutureStub stub;

        Worker(String id) {
            this.id = id;
        }

        boolean isHealthy(long nowNanos, long timeoutNanos) {
            return nowNanos - lastSeenNanos <= timeoutNanos;
        }

        long msSinceSeen(long nowNanos) {
            return TimeUnit.NANOSECONDS.toMillis(nowNanos - lastSeenNanos);
        }
    }

    /** How channels to workers are made. Tests replace it so no real connection is attempted. */
    interface ChannelFactory {
        ManagedChannel create(String address);
    }

    static final ChannelFactory GRPC_CHANNELS = address -> {
        ManagedChannel ch = Grpc.newChannelBuilder(address, InsecureChannelCredentials.create())
                .maxInboundMessageSize(8 * 1024 * 1024)
                .build();
        // Connect NOW, not on the first frame. Otherwise the first frame pays the connection
        // setup (~250 ms measured) and misses its deadline - and the frames behind it too.
        ch.getState(true);
        return ch;
    };

    private final ConcurrentHashMap<String, Worker> workers = new ConcurrentHashMap<>();
    private final long timeoutNanos;
    private final ChannelFactory channels;
    private final LongSupplier clock;

    WorkerRegistry(int heartbeatTimeoutMs) {
        this(heartbeatTimeoutMs, GRPC_CHANNELS, System::nanoTime);
    }

    WorkerRegistry(int heartbeatTimeoutMs, ChannelFactory channels, LongSupplier clock) {
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatTimeoutMs);
        this.channels = channels;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ heartbeats

    /** Called for every heartbeat. Registration and address changes are rare, so a lock is fine. */
    synchronized HeartbeatAck onHeartbeat(WorkerHeartbeat hb) {
        final long now = clock.getAsLong();
        final String id = hb.getWorkerId();
        if (id.isEmpty() || hb.getAddress().isEmpty()) {
            return HeartbeatAck.newBuilder().setAccepted(false).build();
        }

        Worker w = workers.get(id);
        if (w == null) {
            w = new Worker(id);
            connect(w, hb.getAddress());
            workers.put(id, w);
            Log.info("worker registered: " + id + " at " + hb.getAddress() + " models=" + hb.getModelsList()
                    + " threads=" + hb.getThreads());
        } else if (!w.address.equals(hb.getAddress())) {
            if (w.isHealthy(now, timeoutNanos)) {
                // Two live processes claiming the same id: keep the first, reject the newcomer.
                return HeartbeatAck.newBuilder().setAccepted(false).build();
            }
            // The old one is gone; the id came back on a new address (restart on another port).
            Log.info("worker " + id + " moved " + w.address + " -> " + hb.getAddress());
            w.channel.shutdownNow();
            connect(w, hb.getAddress());
        }

        w.models = List.copyOf(hb.getModelsList());
        w.threads = hb.getThreads();
        w.reportedQueueDepth = hb.getQueueDepth();
        w.avgInferMs = hb.getAvgInferMs();
        w.lastSeenNanos = now;

        // A heartbeat proves the worker is up. If our connection to it failed earlier (crash,
        // restart), gRPC would wait out its reconnect back-off (1 s, growing up to minutes) and
        // fail frames meanwhile. Skip the back-off and reconnect now.
        ConnectivityState state = w.channel.getState(false);
        if (state == ConnectivityState.TRANSIENT_FAILURE || state == ConnectivityState.IDLE) {
            w.channel.resetConnectBackoff();
            w.channel.getState(true);
        }

        if (!w.markedHealthy) {
            w.markedHealthy = true;
            Log.info("worker " + id + " is back (heartbeats resumed)");
        }
        return HeartbeatAck.newBuilder().setAccepted(true).build();
    }

    private void connect(Worker w, String address) {
        w.address = address;
        w.channel = channels.create(address);
        w.stub = InferenceWorkerGrpc.newFutureStub(w.channel);
    }

    // ------------------------------------------------------------------ scheduling

    /**
     * Picks a worker for one frame AND reserves it (its in-flight count goes up), atomically,
     * so two frames arriving at the same moment can't both see the same idle worker.
     *
     * Policy: among the healthy workers that have the model (and are not excluded), the one with
     * the fewest frames in flight; on a tie, the one picked longest ago (round robin among equals).
     * Every reservation must be undone exactly once with {@link #release}.
     */
    synchronized Optional<Worker> reserve(String model, Worker exclude) {
        final long now = clock.getAsLong();
        Worker best = null;
        for (Worker w : workers.values()) {
            if (w == exclude || !w.isHealthy(now, timeoutNanos) || !w.models.contains(model)) continue;
            if (best == null
                    || w.inFlight.get() < best.inFlight.get()
                    || (w.inFlight.get() == best.inFlight.get() && w.lastPickedNanos < best.lastPickedNanos)) {
                best = w;
            }
        }
        if (best != null) {
            best.inFlight.incrementAndGet();
            best.sent.incrementAndGet();
            best.lastPickedNanos = now;
        }
        return Optional.ofNullable(best);
    }

    void release(Worker w) {
        w.inFlight.decrementAndGet();
    }

    /**
     * A call to this worker failed with "unreachable": stop sending it frames NOW instead of
     * waiting up to the heartbeat timeout. Its next heartbeat (if it is actually alive) makes it
     * healthy again, so a false alarm costs at most one heartbeat interval (~1 s).
     */
    synchronized void suspend(Worker w, String reason) {
        final long now = clock.getAsLong();
        if (w.isHealthy(now, timeoutNanos)) {
            w.lastSeenNanos = now - timeoutNanos - 1;
            w.markedHealthy = false;
            Log.info("worker " + w.id + " unreachable (" + reason + ") -> suspended until its next heartbeat");
        }
    }

    /** Logs health transitions. Called every second. */
    void sweep() {
        final long now = clock.getAsLong();
        for (Worker w : workers.values()) {
            if (w.markedHealthy && !w.isHealthy(now, timeoutNanos)) {
                w.markedHealthy = false;
                Log.info("worker " + w.id + " missed heartbeats for " + w.msSinceSeen(now)
                        + " ms -> unhealthy, no new frames");
            }
        }
    }

    Collection<Worker> all() {
        return workers.values();
    }

    Optional<Worker> get(String id) {
        return Optional.ofNullable(workers.get(id));
    }

    boolean healthy(Worker w) {
        return w.isHealthy(clock.getAsLong(), timeoutNanos);
    }

    @Override
    public void close() {
        for (Worker w : workers.values()) {
            w.channel.shutdownNow();
        }
    }
}
