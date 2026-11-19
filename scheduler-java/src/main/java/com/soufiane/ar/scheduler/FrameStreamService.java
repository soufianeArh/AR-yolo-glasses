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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// the grpc service the glasses talk to
// one stream per pair of glasses with one Session object behind it
final class FrameStreamService extends FrameStreamGrpc.FrameStreamImplBase {

    static final String DEFAULT_MODEL = "default";

    static final Context.Key<String> DEVICE_TOKEN = Context.key("device-token");

    // the token only tells wich device it is and is no real auth yet
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
    private final ResultPublisher publisher;
    private final AtomicLong sessionSeq = new AtomicLong();
    private final String instance = Long.toString(System.currentTimeMillis(), 36);

    FrameStreamService(WorkerRegistry registry, Metrics metrics, Config cfg, ResultPublisher publisher) {
        this.registry = registry;
        this.metrics = metrics;
        this.cfg = cfg;
        this.publisher = publisher;
    }

    // short hash of the token so the real token never ends up in logs or in redis
    static String deviceId(String token) {
        if (token == null || token.isEmpty()) return "anonymous";
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "dev-" + HexFormat.of().formatHex(h, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // called once when a pair of glasses opens its stream
    @Override
    public StreamObserver<Frame> stream(StreamObserver<FrameResult> out) {
        String token = DEVICE_TOKEN.get();
        Session s = new Session("s-" + instance + "-" + sessionSeq.incrementAndGet(), deviceId(token), out);
        Log.info("session " + s.id + " opened (device " + s.device + ")");
        return new StreamObserver<>() {
            @Override public void onNext(Frame frame) { s.dispatch(frame); }
            @Override public void onError(Throwable t) { s.clientGone(t); }
            @Override public void onCompleted() { s.clientDone(); }
        };
    }

    // a busy or unreachable worker is worth one more try on another worker
    private static boolean retryable(Status.Code code) {
        return code == Status.Code.RESOURCE_EXHAUSTED || code == Status.Code.UNAVAILABLE;
    }

    // one per open stream with its own in flight cap
    private final class Session {
        final String id;
        final String device;
        private final StreamObserver<FrameResult> out;
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean halfClosed = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean gone;
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong answered = new AtomicLong();

        Session(String id, String device, StreamObserver<FrameResult> out) {
            this.id = id;
            this.device = device;
            this.out = out;
        }

        void dispatch(Frame incoming) {
            final long t0 = System.nanoTime();
            received.incrementAndGet();
            final Frame frame = incoming.toBuilder().setSessionId(id).build();
            final String model = frame.getModelsCount() == 0 ? DEFAULT_MODEL : frame.getModels(0);

            // too many frames from this pair of glasses already so answer right away
            if (pending.get() >= cfg.sessionMaxInFlight()) {
                send(failure(frame, ResultStatus.SESSION_LIMIT, "",
                        "session has " + cfg.sessionMaxInFlight() + " frames in flight already"), model, t0, false);
                return;
            }
            pending.incrementAndGet();
            attempt(frame, model, t0, null, null);
        }

        private void attempt(Frame frame, String model, long t0, WorkerRegistry.Worker failedWorker,
                             FrameResult previous) {
            // what is left of the deadline after waiting for a free worker
            final long leftMs = cfg.deadlineFor(model) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            if (leftMs <= 0) {
                send(failure(frame, ResultStatus.DROPPED_DEADLINE, "", "deadline used up before a worker took it"), model, t0, true);
                return;
            }
            WorkerRegistry.Worker w = registry.reserve(model, failedWorker).orElse(null);
            if (w == null) {
                send(previous != null ? previous
                        : failure(frame, ResultStatus.NO_WORKER, "", "no healthy worker has model '" + model + "'"),
                        model, t0, true);
                return;
            }

            ListenableFuture<FrameResult> call;
            try {
                // the worker only gets the time that is left so it can give up by itself
                call = w.stub.withDeadlineAfter(leftMs, TimeUnit.MILLISECONDS).detect(frame);
            } catch (RuntimeException e) {
                registry.release(w);
                w.failed.incrementAndGet();
                send(failure(frame, ResultStatus.WORKER_FAILED, w.id, String.valueOf(e.getMessage())), model, t0, true);
                return;
            }

            Futures.addCallback(call, new FutureCallback<>() {
                @Override
                public void onSuccess(FrameResult r) {
                    registry.release(w);
                    w.ok.incrementAndGet();
                    send(r.toBuilder().setSessionId(id).build(), model, t0, true);
                }

                @Override
                public void onFailure(Throwable e) {
                    registry.release(w);
                    Status st = Status.fromThrowable(e);
                    String why = st.getCode() + (st.getDescription() == null ? "" : ": " + st.getDescription());
                    ResultStatus rs = switch (st.getCode()) {
                        case DEADLINE_EXCEEDED -> ResultStatus.DROPPED_DEADLINE;
                        case RESOURCE_EXHAUSTED -> ResultStatus.NO_WORKER;
                        default -> ResultStatus.WORKER_FAILED;
                    };
                    if (st.getCode() == Status.Code.UNAVAILABLE) {
                        w.failed.incrementAndGet();
                        registry.suspend(w, why);
                    }
                    FrameResult fail = failure(frame, rs, w.id, why);

                    final long left = cfg.deadlineFor(model) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    // only one retry and only if enough of the deadline is left
                    if (failedWorker == null && retryable(st.getCode()) && left >= cfg.minRetryMs()) {
                        metrics.retried();
                        attempt(frame, model, t0, w, fail);
                    } else {
                        send(fail, model, t0, true);
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

        // every answer goes back to the glasses and a copy goes to redis
        private void send(FrameResult r, String model, long t0, boolean counted) {
            final int backendMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            final FrameResult result = r.toBuilder().setBackendMs(backendMs).build();
            metrics.record(model, result.getStatus(), backendMs);
            if (publisher != null) {
                publisher.publish(result, device, System.currentTimeMillis() - backendMs);
            }
            if (!gone) {
                synchronized (out) {
                    try {
                        out.onNext(result);
                        answered.incrementAndGet();
                    } catch (RuntimeException e) {
                        gone = true;
                    }
                }
            }
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

        // close the stream only once the client is done and nothing is pending
        private void maybeFinish() {
            if (halfClosed.get() && pending.get() == 0 && finished.compareAndSet(false, true)) {
                synchronized (out) {
                    try {
                        out.onCompleted();
                    } catch (RuntimeException ignored) {
                    }
                }
                Log.info("session " + id + " closed, frames " + received.get() + " received / "
                        + answered.get() + " answered");
            }
        }
    }
}
