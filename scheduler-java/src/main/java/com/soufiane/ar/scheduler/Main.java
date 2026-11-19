package com.soufiane.ar.scheduler;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerInterceptors;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {

    // wires everything together and then blocks untill the server stops
    public static void main(String[] args) throws Exception {
        final Config cfg;
        try {
            // bad options print the usage and exit
            cfg = Config.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage() + "\n" + Config.USAGE);
            System.exit(2);
            return;
        }

        WorkerRegistry registry = new WorkerRegistry(cfg.heartbeatTimeoutMs());
        Metrics metrics = new Metrics();
        // no publisher at all with --redis none
        ResultPublisher publisher = cfg.redis().isEmpty() ? null
                : new ResultPublisher(cfg.redis(), cfg.stream(), cfg.redisBuffer(), cfg.streamMaxLen());
        metrics.setPublisher(publisher);
        FrameStreamService frames = new FrameStreamService(registry, metrics, cfg, publisher);

        Server server = Grpc.newServerBuilderForPort(cfg.port(), InsecureServerCredentials.create())
                // virtual threads so thousands of waiting calls cost almost nothing
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .maxInboundMessageSize(8 * 1024 * 1024)
                // the interceptor reads the device token before any frame arrives
                .addService(ServerInterceptors.intercept(frames, new FrameStreamService.AuthInterceptor()))
                .addService(new WorkerControlService(registry))
                .build()
                .start();

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-timer");
            t.setDaemon(true);
            return t;
        });
        // every second the workers without a recent heartbeat are marked unhealthy
        timer.scheduleAtFixedRate(registry::sweep, 1, 1, TimeUnit.SECONDS);
        if (cfg.reportSeconds() > 0) {
            timer.scheduleAtFixedRate(() -> metrics.report(registry), cfg.reportSeconds(), cfg.reportSeconds(),
                    TimeUnit.SECONDS);
        }

        // stop taking frames first and then flush what is left for redis
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
            if (publisher != null) publisher.close();
            Log.info("stopped. totals:" + metrics.totals());
        }, "shutdown"));

        // start-backend.sh waits for this READY line before starting the workers
        Log.info("READY listening on 0.0.0.0:" + cfg.port() + ", frame deadline " + cfg.deadlineMs()
                + " ms" + (cfg.modelDeadlinesMs().isEmpty() ? "" : " (per model: " + cfg.modelDeadlinesMs() + ")")
                + ", heartbeat timeout " + cfg.heartbeatTimeoutMs() + " ms, session cap "
                + cfg.sessionMaxInFlight() + " in flight, results to "
                + (publisher == null ? "nowhere (--redis none)" : "redis " + cfg.redis() + " stream " + cfg.stream()));
        server.awaitTermination();
    }
}
