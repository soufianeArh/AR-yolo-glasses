package com.soufiane.ar.scheduler;

import java.util.Map;
import java.util.TreeMap;

/** Command-line options. */
record Config(int port, int deadlineMs, Map<String, Integer> modelDeadlinesMs, int heartbeatTimeoutMs,
              int reportSeconds, int sessionMaxInFlight, int minRetryMs,
              String redis, String stream, int redisBuffer, long streamMaxLen) {

    static final String USAGE = """
            usage: scheduler-java [--port 50051] [--deadline-ms 300] [--model-deadline plant=3500,...]
                                  [--heartbeat-timeout-ms 3000] [--report-seconds 5]
                                  [--session-max-in-flight 2] [--min-retry-ms 50]
                                  [--redis 127.0.0.1:6379 | --redis none] [--stream ar:results]
                                  [--redis-buffer 10000] [--stream-max-len 100000]
              --port                   one port for both the Android app (FrameStream) and
                                       the C++ workers' heartbeats (WorkerControl)
              --deadline-ms            max time a frame may spend in the backend; later = dropped
              --model-deadline         a different deadline for some models (default: plant=3500;
                                       a "look and hold" plant inspection can wait seconds,
                                       a live COCO frame can't)
              --heartbeat-timeout-ms   a worker silent for this long is marked unhealthy
              --report-seconds         how often to log throughput / latency (0 = off)
              --session-max-in-flight  frames one Android app session may have in the backend at once;
                                       more are answered SESSION_LIMIT right away
              --min-retry-ms           retry a failed frame on another worker only if at least this
                                       much of its deadline is left
              --redis                  where to publish a copy of every result (none = don't publish)
              --stream                 Redis Stream name
              --redis-buffer           results kept in memory while Redis is unreachable
              --stream-max-len         Redis keeps about this many entries (older ones trimmed)""";

    /** The deadline for frames asking for this model. */
    int deadlineFor(String model) {
        return modelDeadlinesMs.getOrDefault(model, deadlineMs);
    }

    static Map<String, Integer> parseModelDeadlines(String spec) {
        Map<String, Integer> out = new TreeMap<>();
        for (String part : spec.split(",")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank()) {
                throw new IllegalArgumentException("--model-deadline expects model=ms[,model=ms], got " + part);
            }
            int ms = Integer.parseInt(kv[1].trim());
            if (ms <= 0) throw new IllegalArgumentException("deadline must be > 0: " + part);
            out.put(kv[0].trim(), ms);
        }
        return out;
    }

    /**
     * Built-in per-model deadlines, overridable with --model-deadline. "plant" (the plant-disease
     * model, a "look and hold" inspection) takes ~1.6-2.5 s on the 4-core laptop; 2.5 s proved too
     * tight under load (Step 7), so 3.5 s.
     */
    static final Map<String, Integer> DEFAULT_MODEL_DEADLINES = Map.of("plant", 3500);

    static Config parse(String[] args) {
        int port = 50051, deadline = 300, hbTimeout = 3000, report = 5, sessionMax = 2, minRetry = 50;
        Map<String, Integer> modelDeadlines = new TreeMap<>(DEFAULT_MODEL_DEADLINES);
        String redis = "127.0.0.1:6379", stream = "ar:results";
        int redisBuffer = 10_000;
        long streamMaxLen = 100_000;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value after " + a);
            String v = args[++i];
            switch (a) {
                case "--port" -> port = Integer.parseInt(v);
                case "--deadline-ms" -> deadline = Integer.parseInt(v);
                case "--model-deadline" -> modelDeadlines.putAll(parseModelDeadlines(v));
                case "--heartbeat-timeout-ms" -> hbTimeout = Integer.parseInt(v);
                case "--report-seconds" -> report = Integer.parseInt(v);
                case "--session-max-in-flight" -> sessionMax = Integer.parseInt(v);
                case "--min-retry-ms" -> minRetry = Integer.parseInt(v);
                case "--redis" -> redis = v.equals("none") ? "" : v;
                case "--stream" -> stream = v;
                case "--redis-buffer" -> redisBuffer = Integer.parseInt(v);
                case "--stream-max-len" -> streamMaxLen = Long.parseLong(v);
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        return new Config(port, deadline, Map.copyOf(modelDeadlines), hbTimeout, report, sessionMax, minRetry,
                redis, stream, redisBuffer, streamMaxLen);
    }
}
