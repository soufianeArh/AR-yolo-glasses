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

// copies every result to a redis stream from its own thread
// so the glasses never wait for redis
final class ResultPublisher implements AutoCloseable {

    // result bytes plus the device and when the scheduler recieved the frame
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

    // called from the grpc threads so it must never block
    void publish(FrameResult result, String device, long receivedMs) {
        Item item = new Item(result.toByteArray(), device, receivedMs);
        // redis is down or slow so drop the oldest result and never block the glasses
        while (!buffer.offer(item)) {
            if (buffer.poll() != null) droppedBufferFull.incrementAndGet();
        }
    }

    int buffered() {
        return buffer.size();
    }

    // background thread that sends the buffer to redis in batches
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
                    // take up to 256 results at once
                    buffer.drainTo(batch, 255);
                }
                if (jedis == null) jedis = new Jedis(host, port, 2000);
                // one network round trip for the whole batch
                Pipeline p = jedis.pipelined();
                XAddParams params = XAddParams.xAddParams().maxLen(maxLen).approximateTrimming();
                for (Item it : batch) {
                    // approximate maxlen trims the stream so redis never grows forever
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
                if (connected || !warned) {
                    Log.info("redis " + host + ":" + port + " unreachable (" + e.getClass().getSimpleName()
                            + "), results kept in memory (" + (buffer.size() + batch.size()) + " now), retrying");
                    warned = true;
                }
                connected = false;
                if (jedis != null) {
                    try { jedis.close(); } catch (RuntimeException ignored) {  }
                    jedis = null;
                }
                if (!running) return;
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                //redis is down so wait a bit longer each time up to 5s
                backoffMs = Math.min(backoffMs * 2, 5000);
            }
        }
        if (jedis != null) jedis.close();
    }

    //gives the thread 3 s to flush what is left
    @Override
    public void close() {
        running = false;
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
