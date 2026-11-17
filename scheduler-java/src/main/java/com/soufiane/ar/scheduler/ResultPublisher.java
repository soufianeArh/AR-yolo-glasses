package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.FrameResult;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.XAddParams;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends a copy of every frame result to a Redis Stream for the Python control plane.
 *
 * NEVER slows the fast path: {@link #publish} only puts the result in a bounded in-memory buffer
 * and returns. One background thread writes the buffer to Redis in batches. If Redis is down,
 * results wait in the buffer and go out when it is back; if the buffer fills up, the OLDEST
 * results are dropped (counted and logged) - the popup on the glasses never waits for Redis.
 *
 * Stream entry fields:
 *   pb           the FrameResult, protobuf bytes (the shared contract)
 *   device       short id of the device (hash of its token, never the token itself)
 *   received_ms  when the scheduler received the frame (epoch ms)
 */
final class ResultPublisher implements AutoCloseable {

    private record Item(byte[] pb, String device, long receivedMs) {}

    private static final byte[] F_PB = "pb".getBytes(StandardCharsets.UTF_8);
    private static final byte[] F_DEVICE = "device".getBytes(StandardCharsets.UTF_8);
    private static final byte[] F_RECEIVED = "received_ms".getBytes(StandardCharsets.UTF_8);

    private final String host;
    private final int port;
    private final byte[] stream;
    private final long maxLen;
    private final ArrayBlockingQueue<Item> buffer;
    private final Thread thread;
    private volatile boolean running = true;

    final AtomicLong published = new AtomicLong();
    final AtomicLong droppedBufferFull = new AtomicLong();

    /** @param address "host:port" */
    ResultPublisher(String address, String stream, int bufferSize, long streamMaxLen) {
        int colon = address.lastIndexOf(':');
        this.host = address.substring(0, colon);
        this.port = Integer.parseInt(address.substring(colon + 1));
        this.stream = stream.getBytes(StandardCharsets.UTF_8);
        this.maxLen = streamMaxLen;
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
        this.thread = new Thread(this::loop, "redis-publisher");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Non-blocking. Called on the fast path for every result. */
    void publish(FrameResult result, String device, long receivedMs) {
        Item item = new Item(result.toByteArray(), device, receivedMs);
        while (!buffer.offer(item)) {           // full: drop the oldest, keep the newest
            if (buffer.poll() != null) droppedBufferFull.incrementAndGet();
        }
    }

    int buffered() {
        return buffer.size();
    }

    private void loop() {
        List<Item> batch = new ArrayList<>(256);
        Jedis jedis = null;
        boolean connected = false, warned = false;
        long backoffMs = 250;
        while (running || !buffer.isEmpty()) {
            try {
                if (batch.isEmpty()) {
                    Item first = buffer.poll(200, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                    batch.add(first);
                    buffer.drainTo(batch, 255);
                }
                if (jedis == null) jedis = new Jedis(host, port, 2000);
                Pipeline p = jedis.pipelined();
                XAddParams params = XAddParams.xAddParams().maxLen(maxLen).approximateTrimming();
                for (Item it : batch) {
                    p.xadd(stream, params, Map.of(
                            F_PB, it.pb(),
                            F_DEVICE, it.device().getBytes(StandardCharsets.UTF_8),
                            F_RECEIVED, Long.toString(it.receivedMs()).getBytes(StandardCharsets.UTF_8)));
                }
                p.sync();
                published.addAndGet(batch.size());
                batch.clear();
                backoffMs = 250;
                if (!connected) {
                    Log.info("redis " + host + ":" + port + " reachable, publishing results to stream '"
                            + new String(stream, StandardCharsets.UTF_8) + "'");
                    connected = true;
                    warned = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Redis down or the connection broke. Keep the batch, reconnect after a pause.
                if (connected || !warned) {
                    Log.info("redis " + host + ":" + port + " unreachable (" + e.getClass().getSimpleName()
                            + "), results kept in memory (" + (buffer.size() + batch.size()) + " now), retrying");
                    warned = true;
                }
                connected = false;
                if (jedis != null) {
                    try { jedis.close(); } catch (RuntimeException ignored) { /* already broken */ }
                    jedis = null;
                }
                if (!running) return;   // shutting down with Redis unreachable: give up
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5000);
            }
        }
        if (jedis != null) jedis.close();
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(3000);   // let the last results out if Redis is reachable
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
