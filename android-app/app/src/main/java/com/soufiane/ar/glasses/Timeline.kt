package com.soufiane.ar.glasses

import org.json.JSONObject
import java.io.File

class Timeline(val holds: List<Hold>) {
    data class Hold(val startMs: Long, val endMs: Long, val expected: String)

    // a bit early still counts since the check starts right after the view stops
    fun expectedAt(positionMs: Long): String? =
        holds.firstOrNull { positionMs >= it.startMs - 100 && positionMs <= it.endMs }?.expected

    companion object {
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

        fun verdictLine(expected: String, said: String): String =
            Lang.t("expected", Lang.label(expected)) + "  " + if (expected == said) "✓" else "✗"
    }
}
