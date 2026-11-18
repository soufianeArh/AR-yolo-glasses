package com.soufiane.ar.glasses

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.soufiane.ar.proto.v1.FrameResult
import com.soufiane.ar.proto.v1.ResultStatus
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams frames from a FrameSource to the backend, like the real app on the glasses:
 *   - each frame is JPEG-encoded (quality 80, already <= 640 px wide)
 *   - at most [maxInFlight] frames wait for an answer; while full, new frames are DROPPED
 *     (newest frame wins - never a queue of stale frames)
 *   - results come back in any order and are matched by frame_id
 *
 *   - a frame with no answer after [lostAfterMs] is counted as LOST and frees its slot: otherwise
 *     a backend that silently disappears would leave the app stuck with no free slot forever
 *     (seen in 8.3: 2 frames never answered -> every later frame dropped)
 *
 * Writes "STATS ..." to logcat every 5 s (tag ARGlasses) so tests can compare with the scheduler.
 */
class StreamController(
    /** Sends one frame; false = not connected or network busy (8.7), the frame is dropped. */
    private val send: (Long, ByteArray, List<String>) -> Boolean,
    private val model: String,
    private val maxInFlight: Int = 2,
    private val lostAfterMs: Long = 5000,
    /** Frames got no answer in time (8.7: the app then resets the connection - stale frames dropped). */
    private val onLost: () -> Unit = {},
    private val onUpdate: (Snapshot) -> Unit,
) {
    /** What the debug view shows. */
    data class Snapshot(
        val frame: Bitmap?, val frameId: Long,
        val result: FrameResult?, val resultAgeMs: Long,
        val stats: String,
    )

    private val nextId = AtomicLong(1)
    private val inFlight = AtomicInteger(0)
    private val sent = AtomicLong(0)
    private val droppedOnClient = AtomicLong(0)
    private val lost = AtomicLong(0)
    private val noServer = AtomicLong(0)            // not sent: no server or network busy
    private val pending = HashMap<Long, Long>()         // frame_id -> sent at (elapsedRealtime)
    private val statusCounts = HashMap<ResultStatus, Long>()
    private val latencies = ArrayDeque<Long>()          // last 200 OK round trips (ms)
    private val okTimes = ArrayDeque<Long>()            // when OK results arrived, for OK fps
    private var lastFrame: Bitmap? = null
    private var lastFrameId = 0L
    private var lastResult: FrameResult? = null
    private var lastResultAt = 0L
    private var lastLog = SystemClock.elapsedRealtime()
    private val lock = Any()

    /** Called by the FrameSource for every camera frame. */
    fun onFrame(bitmap: Bitmap, @Suppress("UNUSED_PARAMETER") positionMs: Long = 0) {
        val now = SystemClock.elapsedRealtime()
        val anyLost = synchronized(lock) {
            lastFrame = bitmap
            // Frames that never got an answer: give their slot back.
            val expired = pending.filterValues { now - it > lostAfterMs }.keys.toList()
            for (id in expired) {
                pending.remove(id)
                inFlight.decrementAndGet()
                lost.incrementAndGet()
                Log.w(MainActivity.TAG, "LOST frame=$id (no answer after $lostAfterMs ms)")
            }
            expired.isNotEmpty()
        }
        if (anyLost) onLost()
        if (inFlight.get() >= maxInFlight) {          // backend busy: drop, don't queue
            droppedOnClient.incrementAndGet()
            publish()
            return
        }
        val jpeg = ByteArrayOutputStream(48 * 1024).also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        val id = nextId.getAndIncrement()
        inFlight.incrementAndGet()
        synchronized(lock) {
            lastFrameId = id
            pending[id] = now
        }
        if (send(id, jpeg, listOf(model))) {
            sent.incrementAndGet()
        } else {                                      // not connected / network busy: drop, never queue
            synchronized(lock) { pending.remove(id) }
            inFlight.decrementAndGet()
            noServer.incrementAndGet()
        }
        publish()
    }

    /** Called by the BackendClient for every answer. */
    fun onResult(r: FrameResult, roundTripMs: Long) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            // An answer for a frame already counted as lost must not free a second slot.
            if (pending.remove(r.frameId) == null) return
            inFlight.decrementAndGet()
            statusCounts[r.status] = (statusCounts[r.status] ?: 0) + 1
            if (r.status == ResultStatus.OK) {
                latencies.addLast(roundTripMs)
                while (latencies.size > 200) latencies.removeFirst()
                okTimes.addLast(now)
                lastResult = r
                lastResultAt = now
            }
            while (okTimes.isNotEmpty() && now - okTimes.first() > 5000) okTimes.removeFirst()
        }
        val top = r.detectionsList.maxByOrNull { it.confidence }
        Log.d(MainActivity.TAG, "RESULT frame=${r.frameId} status=${r.status} rtt=$roundTripMs " +
            "backend=${r.backendMs} n=${r.detectionsCount} top=${top?.label ?: "-"}")
        publish()
    }

    /** The stream broke (backend gone): nothing in flight will ever come back. */
    fun onStreamLost() {
        synchronized(lock) {
            lost.addAndGet(pending.size.toLong())
            pending.clear()
            inFlight.set(0)
        }
    }

    private fun pct(sorted: List<Long>, p: Int): Long? =
        if (sorted.isEmpty()) null else sorted[((p / 100.0) * sorted.size).toInt().coerceAtMost(sorted.size - 1)]

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val snap: Snapshot
        synchronized(lock) {
            val sorted = latencies.sorted()
            val okFps = okTimes.size / 5.0
            val stats = "model=$model sent=${sent.get()} dropped_on_client=${droppedOnClient.get()} lost=${lost.get()} not_sent=${noServer.get()} " +
                "in_flight=${inFlight.get()} ok_fps=${"%.1f".format(okFps)} " +
                "rtt_p50=${pct(sorted, 50) ?: "-"} rtt_p95=${pct(sorted, 95) ?: "-"} " +
                "statuses=${statusCounts.entries.joinToString(",") { "${it.key}=${it.value}" }}"
            snap = Snapshot(lastFrame, lastFrameId, lastResult,
                if (lastResultAt == 0L) -1 else now - lastResultAt, stats)
            if (now - lastLog >= 5000) {
                lastLog = now
                Log.i(MainActivity.TAG, "STATS $stats")
            }
        }
        onUpdate(snap)
    }
}
