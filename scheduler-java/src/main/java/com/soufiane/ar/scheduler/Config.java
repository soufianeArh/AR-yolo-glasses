package com.soufiane.ar.scheduler;

/** Command-line options. */
record Config(int port, int deadlineMs, int heartbeatTimeoutMs, int reportSeconds,
              int sessionMaxInFlight, int minRetryMs) {

    static final String USAGE = """
            usage: scheduler-java [--port 50051] [--deadline-ms 300] [--heartbeat-timeout-ms 3000]
                                  [--report-seconds 5] [--session-max-in-flight 2] [--min-retry-ms 50]
              --port                   one port for both the Android app (FrameStream) and
                                       the C++ workers' heartbeats (WorkerControl)
              --deadline-ms            max time a frame may spend in the backend; later = dropped
              --heartbeat-timeout-ms   a worker silent for this long is marked unhealthy
              --report-seconds         how often to log throughput / latency (0 = off)
              --session-max-in-flight  frames one Android app session may have in the backend at once;
                                       more are answered SESSION_LIMIT right away
              --min-retry-ms           retry a failed frame on another worker only if at least this
                                       much of its deadline is left""";

    static Config parse(String[] args) {
        int port = 50051, deadline = 300, hbTimeout = 3000, report = 5, sessionMax = 2, minRetry = 50;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value after " + a);
            int v = Integer.parseInt(args[++i]);
            switch (a) {
                case "--port" -> port = v;
                case "--deadline-ms" -> deadline = v;
                case "--heartbeat-timeout-ms" -> hbTimeout = v;
                case "--report-seconds" -> report = v;
                case "--session-max-in-flight" -> sessionMax = v;
                case "--min-retry-ms" -> minRetry = v;
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        return new Config(port, deadline, hbTimeout, report, sessionMax, minRetry);
    }
}
