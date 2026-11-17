package com.soufiane.ar.scheduler;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerInterceptors;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Java scheduler (Step 4, v1).
 *
 * One gRPC port serves both:
 *   ar.v1.FrameStream/Stream       Android app <-> scheduler (frames in, results out)
 *   ar.v1.WorkerControl/Heartbeat  C++ workers -> scheduler (registration + load)
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        final Config cfg;
        try {
            cfg = Config.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage() + "\n" + Config.USAGE);
            System.exit(2);
            return;
        }

        WorkerRegistry registry = new WorkerRegistry(cfg.heartbeatTimeoutMs());
        Metrics metrics = new Metrics();
        FrameStreamService frames = new FrameStreamService(registry, metrics, cfg);

        Server server = Grpc.newServerBuilderForPort(cfg.port(), InsecureServerCredentials.create())
                // Virtual threads: handlers never need a thread pool sized by hand.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .maxInboundMessageSize(8 * 1024 * 1024)
                .addService(ServerInterceptors.intercept(frames, new FrameStreamService.AuthInterceptor()))
                .addService(new WorkerControlService(registry))
                .build()
                .start();

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(registry::sweep, 1, 1, TimeUnit.SECONDS);
        if (cfg.reportSeconds() > 0) {
            timer.scheduleAtFixedRate(() -> metrics.report(registry), cfg.reportSeconds(), cfg.reportSeconds(),
                    TimeUnit.SECONDS);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutting down");
            server.shutdown();
            try {
                server.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            server.shutdownNow();
            registry.close();
            Log.info("stopped. totals:" + metrics.totals());
        }, "shutdown"));

        // Tests wait for this exact line.
        Log.info("READY listening on 0.0.0.0:" + cfg.port() + ", frame deadline " + cfg.deadlineMs()
                + " ms, heartbeat timeout " + cfg.heartbeatTimeoutMs() + " ms, session cap "
                + cfg.sessionMaxInFlight() + " in flight");
        server.awaitTermination();
    }
}
