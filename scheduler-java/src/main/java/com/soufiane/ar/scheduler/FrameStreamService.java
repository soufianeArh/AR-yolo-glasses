package com.soufiane.ar.scheduler;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.soufiane.ar.proto.v1.Frame;
import com.soufiane.ar.proto.v1.FrameResult;
import com.soufiane.ar.proto.v1.FrameStreamGrpc;
import com.soufiane.ar.proto.v1.ResultStatus;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android app <-> Java scheduler. One long-lived two-way stream per Android app session.
 *
 * For each frame: reserve the least-loaded worker, call it with what is left of the frame's
 * deadline, send the result back on the same stream. If that worker's queue was full or it
 * could not be reached, try ONE other worker while enough deadline is left.
 *
 * Results can go back in a different order than frames arrived; the Android app matches them
 * by frame_id. Nothing here blocks: worker calls are async and their callbacks write the result.
 */
final class FrameStreamService extends FrameStreamGrpc.FrameStreamImplBase {

    static final String DEFAULT_MODEL = "default";

    /** Captures the device token from the "authorization" header when a stream opens. */
    static final Context.Key<String> DEVICE_TOKEN = Context.key("device-token");

    static final class AuthInterceptor implements ServerInterceptor {
        private static final Metadata.Key<String> AUTH =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                           ServerCallHandler<Q, R> next) {
            String token = headers.get(AUTH);
            Context ctx = Context.current().withValue(DEVICE_TOKEN, token == null ? "" : token);
            return Contexts.interceptCall(ctx, call, headers, next);
        }
    }

    private final WorkerRegistry registry;
    private final Metrics metrics;
    private final Config cfg;
    private final AtomicLong sessionSeq = new AtomicLong();

    FrameStreamService(WorkerRegistry registry, Metrics metrics, Config cfg) {
        this.registry = registry;
        this.metrics = metrics;
        this.cfg = cfg;
    }

    @Override
    public StreamObserver<Frame> stream(StreamObserver<FrameResult> out) {
        // The token is only recorded, not checked. Checking it belongs with real devices (Step 7).
        String token = DEVICE_TOKEN.get();
        Session s = new Session("s" + sessionSeq.incrementAndGet(), out);
        Log.info("session " + s.id + " opened (device token: " + (token == null || token.isEmpty() ? "none" : "present") + ")");
        return new StreamObserver<>() {
            @Override public void onNext(Frame frame) { s.dispatch(frame); }
            @Override public void onError(Throwable t) { s.clientGone(t); }
            @Override public void onCompleted() { s.clientDone(); }
        };
    }

    /** gRPC failures worth one more try on ANOTHER worker. */
    private static boolean retryable(Status.Code code) {
        // RESOURCE_EXHAUSTED: that worker's queue is full, another may be free.
        // UNAVAILABLE: that worker can't be reached (crashed, restarting).
        // NOT DEADLINE_EXCEEDED: the frame's time is used up, a retry would only be later.
        return code == Status.Code.RESOURCE_EXHAUSTED || code == Status.Code.UNAVAILABLE;
    }

    /** One Android app connection. */
    private final class Session {
        final String id;
        private final StreamObserver<FrameResult> out;
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean halfClosed = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean gone;
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong answered = new AtomicLong();

        Session(String id, StreamObserver<FrameResult> out) {
            this.id = id;
            this.out = out;
        }

        void dispatch(Frame incoming) {
            final long t0 = System.nanoTime();
            received.incrementAndGet();
            final Frame frame = incoming.toBuilder().setSessionId(id).build();

            // A well-behaved Android app keeps 1-2 frames in flight. Beyond the cap, answer at once
            // instead of letting one session flood the workers.
            if (pending.get() >= cfg.sessionMaxInFlight()) {
                send(failure(frame, ResultStatus.SESSION_LIMIT, "",
                        "session has " + cfg.sessionMaxInFlight() + " frames in flight already"), t0, false);
                return;
            }
            pending.incrementAndGet();
            final String model = frame.getModelsCount() == 0 ? DEFAULT_MODEL : frame.getModels(0);
            attempt(frame, model, t0, null, null);
        }

        /**
         * @param failedWorker the worker that already failed this frame (first attempt: null)
         * @param previous     the failure to report if no other worker can take it (first attempt: null)
         */
        private void attempt(Frame frame, String model, long t0, WorkerRegistry.Worker failedWorker,
                             FrameResult previous) {
            final long leftMs = cfg.deadlineMs() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            if (leftMs <= 0) {
                send(failure(frame, ResultStatus.DROPPED_DEADLINE, "", "deadline used up before a worker took it"), t0, true);
                return;
            }
            WorkerRegistry.Worker w = registry.reserve(model, failedWorker).orElse(null);
            if (w == null) {
                send(previous != null ? previous
                        : failure(frame, ResultStatus.NO_WORKER, "", "no healthy worker has model '" + model + "'"),
                        t0, true);
                return;
            }

            ListenableFuture<FrameResult> call;
            try {
                call = w.stub.withDeadlineAfter(leftMs, TimeUnit.MILLISECONDS).detect(frame);
            } catch (RuntimeException e) {          // channel already shut down, etc.
                registry.release(w);
                w.failed.incrementAndGet();
                send(failure(frame, ResultStatus.WORKER_FAILED, w.id, String.valueOf(e.getMessage())), t0, true);
                return;
            }

            Futures.addCallback(call, new FutureCallback<>() {
                @Override
                public void onSuccess(FrameResult r) {
                    registry.release(w);
                    w.ok.incrementAndGet();
                    send(r.toBuilder().setSessionId(id).build(), t0, true);
                }

                @Override
                public void onFailure(Throwable e) {
                    registry.release(w);
                    Status st = Status.fromThrowable(e);
                    String why = st.getCode() + (st.getDescription() == null ? "" : ": " + st.getDescription());
                    ResultStatus rs = switch (st.getCode()) {
                        case DEADLINE_EXCEEDED -> ResultStatus.DROPPED_DEADLINE;
                        case RESOURCE_EXHAUSTED -> ResultStatus.NO_WORKER;      // queue full
                        default -> ResultStatus.WORKER_FAILED;
                    };
                    if (st.getCode() == Status.Code.UNAVAILABLE) {
                        w.failed.incrementAndGet();
                        registry.suspend(w, why);   // don't send it more frames until it heartbeats again
                    }
                    FrameResult fail = failure(frame, rs, w.id, why);

                    final long left = cfg.deadlineMs() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    if (failedWorker == null && retryable(st.getCode()) && left >= cfg.minRetryMs()) {
                        metrics.retried();
                        attempt(frame, model, t0, w, fail);
                    } else {
                        send(fail, t0, true);
                    }
                }
            }, MoreExecutors.directExecutor());
        }

        private FrameResult failure(Frame f, ResultStatus status, String workerId, String error) {
            return FrameResult.newBuilder()
                    .setSessionId(id)
                    .setFrameId(f.getFrameId())
                    .setStatus(status)
                    .setWorkerId(workerId)
                    .setError(error)
                    .build();
        }

        /** @param counted true if this frame was counted in {@code pending}. */
        private void send(FrameResult r, long t0, boolean counted) {
            final int backendMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            final FrameResult result = r.toBuilder().setBackendMs(backendMs).build();
            metrics.record(result.getStatus(), backendMs);
            if (!gone) {
                // StreamObserver is not thread-safe: worker callbacks arrive on several threads.
                synchronized (out) {
                    try {
                        out.onNext(result);
                        answered.incrementAndGet();
                    } catch (RuntimeException e) {   // the Android app disconnected meanwhile
                        gone = true;
                    }
                }
            }
            // Only after the result is written: otherwise another thread could see pending == 0
            // and close the stream before this result went out.
            if (counted) pending.decrementAndGet();
            maybeFinish();
        }

        void clientDone() {
            halfClosed.set(true);
            maybeFinish();
        }

        void clientGone(Throwable t) {
            gone = true;
            if (finished.compareAndSet(false, true)) {
                Log.info("session " + id + " dropped by client (" + Status.fromThrowable(t).getCode() + "), frames "
                        + received.get() + " received / " + answered.get() + " answered");
            }
        }

        /** Close our side only after the client is done sending AND every result went out. */
        private void maybeFinish() {
            if (halfClosed.get() && pending.get() == 0 && finished.compareAndSet(false, true)) {
                synchronized (out) {
                    try {
                        out.onCompleted();
                    } catch (RuntimeException ignored) {
                        // already cancelled
                    }
                }
                Log.info("session " + id + " closed, frames " + received.get() + " received / "
                        + answered.get() + " answered");
            }
        }
    }
}
