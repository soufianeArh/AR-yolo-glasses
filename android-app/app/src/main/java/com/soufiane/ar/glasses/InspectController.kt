package com.soufiane.ar.glasses

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.soufiane.ar.proto.v1.FrameResult
import com.soufiane.ar.proto.v1.ResultStatus
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Step 8.4: plant inspection, "look and hold" - the core feature.
 *
 *   MOVING ──(view steady for [steadyFrames] frames)──► CHECKING ──(votes done)──► SHOWING
 *     ▲                                                     │                        │
 *     └──────────────────(view moves)───────────────────────┴────────────────────────┘
 *
 * - Steady = the change between consecutive 40×22 grey thumbnails is below [steadyThreshold]
 *   (0..255). Calibrated on the look-and-hold videos: holding (with hand tremor) p95 2.5,
 *   moving median 45 -> threshold 5, 4 frames (0.5 s at 8 fps).
 * - CHECKING sends ONE frame at a time to the "plant" model (each must fit the plant deadline),
 *   up to [voteFrames] frames while the view stays steady: one frame alone is unstable with the
 *   current model (Step 7: 8/14), so the answer is a VOTE.
 * - SHOWING: the verdict stays; no new inspection of the same leaf until the view moves.
 *
 * Generic over the frame type [F] so the logic runs in plain JVM unit tests (app/src/test);
 * the app uses [forBitmaps]. Time comes from [clock] for the same reason.
 *
 * Logcat (tag ARGlasses), for tests: INSPECT_START / INSPECT_VOTE / INSPECT_RESULT lines.
 */
class InspectController<F : Any>(
    /** Sends one frame; false = could not be sent (no server, network busy): the check ends. */
    private val send: (frameId: Long, image: ByteArray, models: List<String>) -> Boolean,
    private val thumb: (F) -> IntArray,
    private val encode: (F) -> ByteArray,
    private val voteFrames: Int = 2,
    private val steadyThreshold: Double = 5.0,
    private val steadyFrames: Int = 4,
    private val lostAfterMs: Long = 12000,      // must be longer than the backend's plant deadline (8 s on the dev laptop)
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** A frame got no answer within [lostAfterMs] (8.7: the app then resets the connection). */
    private val onLost: () -> Unit = {},
    private val onUpdate: (Snapshot<F>) -> Unit,
) {
    enum class State { MOVING, CHECKING, SHOWING }
    enum class Kind { DISEASE, HEALTHY, NONE, UNCERTAIN, FAILED }

    data class Verdict(val kind: Kind, val label: String, val confidence: Float, val agree: Int, val frames: Int,
                       val second: String?, val note: String? = null) {
        /** What the wearer reads. */
        val text: String
            get() = when (kind) {
                Kind.DISEASE -> "$label  ${PopupRules.f2(confidence)}"
                Kind.HEALTHY -> "Healthy leaf ($label)"
                Kind.NONE -> "No leaf disease found"
                Kind.UNCERTAIN -> "Unsure: $label or $second?"
                Kind.FAILED -> if (note != null) "$note - try again" else "Check failed - try again"
            }
    }

    data class Snapshot<F>(val state: State, val change: Double, val frame: F?, val lastResult: FrameResult?,
                           val verdict: Verdict?, val verdictHold: Int, val checking: String?, val debug: String)

    private val lock = Any()
    private var state = State.MOVING
    private var prevThumb: IntArray? = null
    private var steadyCount = 0
    private var change = 0.0
    private var hold = 0
    private var holdStartPos = 0L
    private var holdStartTime = 0L
    private var movedDuringCheck = false
    private var pendingId = -1L
    private var pendingSince = 0L
    private val votes = ArrayList<FrameResult>()
    private var nextFrameId = 1L
    private var lastFrame: F? = null
    private var lastResult: FrameResult? = null
    private var verdict: Verdict? = null
    private var verdictHold = 0                                  // which hold the verdict belongs to

    val currentState: State get() = synchronized(lock) { state }

    fun onFrame(frame: F, positionMs: Long) {
        val t = thumb(frame)
        var lostNow = false
        synchronized(lock) {
            lastFrame = frame
            change = prevThumb?.let { meanAbsDiff(it, t) } ?: 999.0
            prevThumb = t
            val steady = change < steadyThreshold
            steadyCount = if (steady) steadyCount + 1 else 0

            when (state) {
                State.MOVING -> if (steadyCount >= steadyFrames) startInspection(frame, positionMs)
                State.SHOWING -> if (!steady) state = State.MOVING          // next leaf: allowed again
                State.CHECKING -> {
                    if (!steady) movedDuringCheck = true
                    if (pendingId >= 0 && clock() - pendingSince > lostAfterMs) {   // answer never came
                        Log.w(MainActivity.TAG, "INSPECT_LOST hold=$hold frame=$pendingId")
                        pendingId = -1
                        lostNow = true
                        finish("Network too slow")
                    } else if (pendingId < 0 && (movedDuringCheck || votes.size >= voteFrames)) {
                        // Nothing in flight and no more votes coming (the wearer moved on between two
                        // votes): decide with what we have. Without this the check never ended and no
                        // other leaf was ever inspected (found in the 8.5 potato run).
                        finish()
                    } else if (pendingId < 0 && steady && votes.size < voteFrames) {
                        sendFrame(frame, positionMs)                        // next vote, same hold
                    }
                }
            }
        }
        if (lostNow) onLost()
        publish()
    }

    fun onResult(r: FrameResult, roundTripMs: Long) {
        synchronized(lock) {
            if (r.frameId != pendingId) return                              // late answer of an old hold
            pendingId = -1
            lastResult = r
            votes.add(r)
            val top = r.detectionsList.maxByOrNull { it.confidence }
            Log.i(MainActivity.TAG, "INSPECT_VOTE hold=$hold frame=${r.frameId} status=${r.status} " +
                "top=${top?.label ?: "-"} conf=${top?.confidence?.let { "%.4f".format(it) } ?: "-"} " +
                "backend_ms=${r.backendMs} rtt=$roundTripMs")
            // Done when enough votes, the wearer moved on, or this frame failed (no point waiting).
            if (votes.size >= voteFrames || movedDuringCheck || r.status != ResultStatus.OK) finish()
        }
        publish()
    }

    fun onStreamLost() {
        synchronized(lock) {
            if (state == State.CHECKING) {
                pendingId = -1
                finish()
            }
        }
        publish()
    }

    // ------------------------------------------------------------------ inspection

    private fun startInspection(frame: F, positionMs: Long) {
        hold++
        state = State.CHECKING
        movedDuringCheck = false
        votes.clear()
        holdStartPos = positionMs
        holdStartTime = clock()
        Log.i(MainActivity.TAG, "INSPECT_START hold=$hold pos_ms=$positionMs")
        sendFrame(frame, positionMs)
    }

    private fun sendFrame(frame: F, positionMs: Long) {
        val image = encode(frame)
        pendingId = nextFrameId++
        pendingSince = clock()
        Log.d(MainActivity.TAG, "INSPECT_SEND hold=$hold frame=$pendingId pos_ms=$positionMs bytes=${image.size}")
        if (!send(pendingId, image, listOf("plant"))) {
            pendingId = -1
            finish(if (votes.isEmpty()) "Network too slow" else null)   // decide with the votes we have
        }
    }

    private fun finish(note: String? = null) {
        val v = vote(votes).let { if (it.kind == Kind.FAILED && note != null) it.copy(note = note) else it }
        verdict = v
        verdictHold = hold
        state = if (steadyCount > 0 && !movedDuringCheck) State.SHOWING else State.MOVING
        Log.i(MainActivity.TAG, "INSPECT_RESULT hold=$hold start_pos_ms=$holdStartPos kind=${v.kind} " +
            "label=${v.label} conf=${"%.4f".format(v.confidence)} agree=${v.agree}/${v.frames} " +
            "second=${v.second ?: "-"} latency_ms=${clock() - holdStartTime}")
    }

    // ------------------------------------------------------------------ helpers

    private fun publish() {
        val snap = synchronized(lock) {
            Snapshot(state, change, lastFrame, lastResult, verdict, verdictHold,
                if (state == State.CHECKING) "checking leaf... (${votes.size}/$voteFrames)" else null,
                "INSPECT  state=$state  change=${"%.1f".format(change)} (steady < $steadyThreshold, " +
                    "$steadyCount/$steadyFrames)  hold=$hold  votes=${votes.size}/$voteFrames")
        }
        onUpdate(snap)
    }

    companion object {
        private val DISEASE_WORDS = listOf("blight", "spot", "rust", "virus", "mold", "mildew", "scab", "rot",
            "mites", "bacterial")

        fun isDisease(label: String) = DISEASE_WORDS.any { label.lowercase().contains(it) }

        /** The app's controller: Android bitmaps, JPEG q90 (leaf lesions are small: higher quality). */
        fun forBitmaps(
            send: (Long, ByteArray, List<String>) -> Boolean,
            voteFrames: Int,
            onLost: () -> Unit,
            onUpdate: (Snapshot<Bitmap>) -> Unit,
        ) = InspectController<Bitmap>(send, ::thumbnail, { b ->
            ByteArrayOutputStream(64 * 1024).also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }, voteFrames = voteFrames, onLost = onLost, onUpdate = onUpdate)

        /**
         * The vote: each OK frame gives its most confident detection; labels are weighted by
         * confidence. The winner needs more than half of the frames that saw something,
         * otherwise the answer is "unsure: A or B".
         */
        fun vote(results: List<FrameResult>): Verdict {
            val ok = results.filter { it.status == ResultStatus.OK }
            if (ok.isEmpty()) return Verdict(Kind.FAILED, "-", 0f, 0, results.size, null)
            val tops = ok.mapNotNull { r -> r.detectionsList.maxByOrNull { it.confidence } }
            if (tops.isEmpty()) return Verdict(Kind.NONE, "-", 0f, 0, ok.size, null)
            val byLabel = tops.groupBy { it.label }.mapValues { (_, d) -> d.sumOf { it.confidence.toDouble() } }
            val ranked = byLabel.entries.sortedByDescending { it.value }
            val winner = ranked.first().key
            val agree = tops.count { it.label == winner }
            val conf = tops.filter { it.label == winner }.maxOf { it.confidence }
            if (agree * 2 <= tops.size && ranked.size > 1) {
                return Verdict(Kind.UNCERTAIN, winner, conf, agree, ok.size, ranked[1].key)
            }
            val kind = if (isDisease(winner)) Kind.DISEASE else Kind.HEALTHY
            return Verdict(kind, winner, conf, agree, ok.size, null)
        }

        /** 40×22 grey thumbnail (ints 0..255). */
        fun thumbnail(b: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(b, 40, 22, true)
            val px = IntArray(40 * 22)
            small.getPixels(px, 0, 40, 0, 0, 40, 22)
            if (small != b) small.recycle()
            for (i in px.indices) {
                val c = px[i]
                px[i] = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
            }
            return px
        }

        fun meanAbsDiff(a: IntArray, b: IntArray): Double {
            var s = 0L
            for (i in a.indices) s += abs(a[i] - b[i])
            return s.toDouble() / a.size
        }
    }
}
