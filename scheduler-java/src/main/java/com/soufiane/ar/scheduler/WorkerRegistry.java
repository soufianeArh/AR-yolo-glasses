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

final class WorkerRegistry implements AutoCloseable {

    //everything the scheduler knows about one c++ worker
    static final class Worker {
        final String id;
        volatile String address;
        volatile List<String> models = List.of();
        volatile int threads;
        volatile int reportedQueueDepth;
        volatile float avgInferMs;
        volatile long lastSeenNanos;
        volatile boolean markedHealthy = true;

        // frames sent to this worker and not answered yet
        final AtomicInteger inFlight = new AtomicInteger();
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

    // tests plug fake channels in here
    interface ChannelFactory {
        ManagedChannel create(String address);
    }

    static final ChannelFactory GRPC_CHANNELS = address -> {
        ManagedChannel ch = Grpc.newChannelBuilder(address, InsecureChannelCredentials.create())
                .maxInboundMessageSize(8 * 1024 * 1024)
                .build();
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

    // the first heartbeat registers the worker and the next ones refresh its load and health
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
        // same id from a new address is refused while the old one is still alive
        } else if (!w.address.equals(hb.getAddress())) {
            if (w.isHealthy(now, timeoutNanos)) {
                return HeartbeatAck.newBuilder().setAccepted(false).build();
            }
            Log.info("worker " + id + " moved " + w.address + " -> " + hb.getAddress());
            w.channel.shutdownNow();
            connect(w, hb.getAddress());
        }

        w.models = List.copyOf(hb.getModelsList());
        w.threads = hb.getThreads();
        w.reportedQueueDepth = hb.getQueueDepth();
        w.avgInferMs = hb.getAvgInferMs();
        w.lastSeenNanos = now;

        ConnectivityState state = w.channel.getState(false);
        // the worker is alive again so reconnect now instead of waiting for the grpc backoff
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

    // picks the least loaded worker and counts the frame right away
    // so two sessions never grab the same last slot
    synchronized Optional<Worker> reserve(String model, Worker exclude) {
        final long now = clock.getAsLong();
        Worker best = null;
        for (Worker w : workers.values()) {
            if (w == exclude || !w.isHealthy(now, timeoutNanos) || !w.models.contains(model)) continue;
            // fewest frames in flight wins and on a tie the one picked longest ago
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

    // called when the answer or the error comes back
    void release(Worker w) {
        w.inFlight.decrementAndGet();
    }

    // marks the worker unhealthy right away instead of waiting for the heartbeat timeout
    synchronized void suspend(Worker w,String reason) {
        final long now = clock.getAsLong();
        if (w.isHealthy(now, timeoutNanos)) {
            w.lastSeenNanos = now - timeoutNanos - 1;
            w.markedHealthy = false;
            Log.info("worker " + w.id + " unreachable (" + reason + ") -> suspended until its next heartbeat");
        }
    }

    // runs every second from Main
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
