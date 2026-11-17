package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.WorkerHeartbeat;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scheduling rules, with a fake clock and in-process channels (no network, no sleeping). */
class WorkerRegistryTest {

    private static final int TIMEOUT_MS = 3000;
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private WorkerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkerRegistry(TIMEOUT_MS,
                address -> InProcessChannelBuilder.forName("test-" + address).build(), now::get);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private void advanceMs(long ms) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    private boolean heartbeat(String id, String address, String... models) {
        return registry.onHeartbeat(WorkerHeartbeat.newBuilder()
                .setWorkerId(id).setAddress(address).addAllModels(List.of(models.length == 0
                        ? new String[]{"default"} : models)).setThreads(2).build()).getAccepted();
    }

    private String reserveId() {
        return registry.reserve("default", null).map(w -> w.id).orElse(null);
    }

    private WorkerRegistry.Worker worker(String id) {
        return registry.get(id).orElseThrow();
    }

    @Test
    void firstHeartbeatRegistersTheWorker() {
        assertEquals(null, reserveId());
        assertTrue(heartbeat("w1", "a:1"));
        assertEquals("w1", reserveId());
    }

    @Test
    void picksTheWorkerWithFewestFramesInFlight() {
        heartbeat("w1", "a:1");
        heartbeat("w2", "a:2");
        heartbeat("w3", "a:3");
        String first = reserveId();
        String second = reserveId();
        String third = reserveId();
        // three idle workers -> three different workers, never the same one twice
        assertEquals(3, List.of(first, second, third).stream().distinct().count());
        // free one of them: it becomes the only least-loaded worker, so it is picked next
        registry.release(worker(second));
        assertEquals(second, reserveId());
    }

    @Test
    void idleWorkersShareTheWorkRoundRobin() {
        heartbeat("w1", "a:1");
        heartbeat("w2", "a:2");
        heartbeat("w3", "a:3");
        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            advanceMs(1);
            WorkerRegistry.Worker w = registry.reserve("default", null).orElseThrow();
            picks.add(w.id);
            registry.release(w);   // answered at once: every worker is always idle
        }
        for (String id : List.of("w1", "w2", "w3")) {
            assertEquals(2, picks.stream().filter(id::equals).count(), "picks: " + picks);
        }
    }

    @Test
    void retryNeverGoesBackToTheWorkerThatFailed() {
        heartbeat("w1", "a:1");
        heartbeat("w2", "a:2");
        WorkerRegistry.Worker failed = worker("w1");
        for (int i = 0; i < 5; i++) {
            WorkerRegistry.Worker w = registry.reserve("default", failed).orElseThrow();
            assertEquals("w2", w.id);
            registry.release(w);
        }
        // only the failed worker exists -> nothing to retry on
        heartbeat("w2", "a:2");
        advanceMs(TIMEOUT_MS + 1);
        heartbeat("w1", "a:1");
        assertTrue(registry.reserve("default", failed).isEmpty());
    }

    @Test
    void onlyWorkersWithTheModelArePicked() {
        heartbeat("w1", "a:1", "default");
        heartbeat("w2", "a:2", "fire");
        assertEquals("w2", registry.reserve("fire", null).orElseThrow().id);
        assertTrue(registry.reserve("plant", null).isEmpty());
    }

    @Test
    void silentWorkerBecomesUnhealthyThenComesBack() {
        heartbeat("w1", "a:1");
        advanceMs(TIMEOUT_MS - 1);
        assertEquals("w1", reserveId());
        registry.release(worker("w1"));
        advanceMs(2);                             // now past the timeout
        assertFalse(registry.healthy(worker("w1")));
        assertEquals(null, reserveId());
        heartbeat("w1", "a:1");                   // heartbeats resume
        assertEquals("w1", reserveId());
    }

    @Test
    void suspendedWorkerGetsNoFramesUntilItsNextHeartbeat() {
        heartbeat("w1", "a:1");
        heartbeat("w2", "a:2");
        registry.suspend(worker("w1"), "test");
        for (int i = 0; i < 4; i++) {
            WorkerRegistry.Worker w = registry.reserve("default", null).orElseThrow();
            assertEquals("w2", w.id);
            registry.release(w);
        }
        heartbeat("w1", "a:1");
        assertTrue(registry.healthy(worker("w1")));
    }

    @Test
    void sameIdFromAnotherAddressIsRejectedWhileTheFirstIsAlive() {
        assertTrue(heartbeat("w1", "a:1"));
        assertFalse(heartbeat("w1", "a:9"));      // duplicate id, first one still alive
        assertEquals("a:1", worker("w1").address);
        advanceMs(TIMEOUT_MS + 1);                // first one died
        assertTrue(heartbeat("w1", "a:9"));       // same id restarted on another port: accepted
        assertEquals("a:9", worker("w1").address);
    }

    @Test
    void concurrentReservationsAreBalanced() throws Exception {
        for (int i = 1; i <= 4; i++) heartbeat("w" + i, "a:" + i);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return registry.reserve("default", null).orElseThrow();
            }));
        }
        start.countDown();
        for (var f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        // 8 frames at the same instant on 4 idle workers: exactly 2 each, never 3 on one
        for (int i = 1; i <= 4; i++) {
            assertEquals(2, worker("w" + i).inFlight.get(), "w" + i);
        }
    }
}
