package com.soufiane.ar.glasses

import org.json.JSONObject
import java.io.File

/**
 * Step 8.9: the expected answers of a look-and-hold test video (made by tools/make_plant_videos.py):
 * for every hold, its time window and the disease on the leaf. The demo screen shows
 * "expected: ... ✓/✗" under each popup with it.
 */
class Timeline(val holds: List<Hold>) {
    data class Hold(val startMs: Long, val endMs: Long, val expected: String)

    /** The expected answer of the hold that contains [positionMs] (a little early counts: 100 ms). */
    fun expectedAt(positionMs: Long): String? =
        holds.firstOrNull { positionMs >= it.startMs - 100 && positionMs <= it.endMs }?.expected

    companion object {
        /** `<video>.timeline.json` next to `<video>.mp4`; null if there is none or it is unreadable. */
        fun forVideo(video: File): Timeline? {
            val f = File(video.parentFile, video.name.removeSuffix(".mp4") + ".timeline.json")
            if (!f.isFile) return null
            return try {
                parse(f.readText())
            } catch (_: Exception) {
                null
            }
        }

        fun parse(json: String): Timeline {
            val arr = JSONObject(json).getJSONArray("holds")
            return Timeline((0 until arr.length()).map { i ->
                val h = arr.getJSONObject(i)
                Hold((h.getDouble("hold_start_s") * 1000).toLong(), (h.getDouble("hold_end_s") * 1000).toLong(),
                    h.getString("expected"))
            })
        }

        /** "expected: Corn rust leaf ✓" - the line under the popup. */
        fun verdictLine(expected: String, said: String): String =
            "expected: $expected  " + if (expected == said) "✓" else "✗"
    }
}
