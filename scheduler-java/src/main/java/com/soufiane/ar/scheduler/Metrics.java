package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.ResultStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Throughput and latency, logged every few seconds.
 * Latency here = backend time: from the scheduler receiving a frame to sending its result.
 */
final class Metrics {
    private final Map<ResultStatus, LongAdder> totals = new EnumMap<>(ResultStatus.class);
    private final ConcurrentLinkedQueue<Integer> window = new ConcurrentLinkedQueue<>();  // backend ms, OK only
    private final Map<ResultStatus, LongAdder> windowCounts = new EnumMap<>(ResultStatus.class);
    private final LongAdder retries = new LongAdder();
    private final LongAdder windowRetries = new LongAdder();
    private final Map<String, Long> lastSent = new HashMap<>();  // per worker, for per-window deltas
    private long windowStartNanos = System.nanoTime();
    private ResultPublisher publisher;   // null = not publishing

    void setPublisher(ResultPublisher publisher) {
        this.publisher = publisher;
    }

    private String publisherState() {
        if (publisher == null) return "";
        return " | redis published=" + publisher.published.get() + " buffered=" + publisher.buffered()
                + " dropped=" + publisher.droppedBufferFull.get();
    }

    Metrics() {
        for (ResultStatus s : ResultStatus.values()) {
            if (s == ResultStatus.UNRECOGNIZED) continue;
            totals.put(s, new LongAdder());
            windowCounts.put(s, new LongAdder());
        }
    }

    void record(ResultStatus status, int backendMs) {
        LongAdder t = totals.get(status);
        if (t == null) return;
        t.increment();
        windowCounts.get(status).increment();
        if (status == ResultStatus.OK) window.add(backendMs);
    }

    void retried() {
        retries.increment();
        windowRetries.increment();
    }

    /** Logs the last window (if anything happened) and resets it. */
    synchronized void report(WorkerRegistry registry) {
        final long now = System.nanoTime();
        final double seconds = (now - windowStartNanos) / 1e9;
        windowStartNanos = now;

        List<Integer> lat = new ArrayList<>();
        for (Integer v; (v = window.poll()) != null; ) lat.add(v);
        long frames = 0;
        StringBuilder statuses = new StringBuilder();
        for (Map.Entry<ResultStatus, LongAdder> e : windowCounts.entrySet()) {
            long n = e.getValue().sumThenReset();
            frames += n;
            if (n > 0) statuses.append(' ').append(e.getKey()).append('=').append(n);
        }
        long r = windowRetries.sumThenReset();
        if (r > 0) statuses.append(" retries=").append(r);
        if (frames == 0) return;

        Collections.sort(lat);
        StringBuilder workers = new StringBuilder();
        for (WorkerRegistry.Worker w : registry.all()) {
            long sent = w.sent.get();
            long delta = sent - lastSent.getOrDefault(w.id, 0L);
            lastSent.put(w.id, sent);
            workers.append(' ').append(w.id).append('[')
                    .append(registry.healthy(w) ? "ok" : "DOWN")
                    .append(" sent ").append(delta)
                    .append(" inflight ").append(w.inFlight.get())
                    .append(" avg ").append(Math.round(w.avgInferMs)).append("ms]");
        }
        Log.info(String.format("last %.1fs: %d frames (%.1f fps)%s | backend p50=%s p95=%s p99=%s ms |%s%s",
                seconds, frames, frames / seconds, statuses, pct(lat, 50), pct(lat, 95), pct(lat, 99), workers,
                publisherState()));
    }

    String totals() {
        StringBuilder sb = new StringBuilder();
        totals.forEach((s, n) -> {
            if (n.sum() > 0) sb.append(' ').append(s).append('=').append(n.sum());
        });
        if (retries.sum() > 0) sb.append(" retries=").append(retries.sum());
        if (sb.length() == 0) sb.append(" (no frames)");
        return sb + publisherState();
    }

    private static String pct(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return "-";
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return Integer.toString(sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1))));
    }
}
