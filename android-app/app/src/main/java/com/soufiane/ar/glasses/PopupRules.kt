package com.soufiane.ar.glasses

import com.soufiane.ar.glasses.InspectController.Kind

/**
 * Step 8.5: what the popup says and when - pure logic (no Android), covered by unit tests.
 * [Popup] only does the showing / timing / fading.
 */
object PopupRules {
    // ARGB colours (Color.rgb is Android-only; these work in JVM tests too).
    const val ORANGE = 0xFFFF963C.toInt()      // disease
    const val GREEN = 0xFF78FF78.toInt()       // healthy / stream object
    const val YELLOW = 0xFFFFE650.toInt()      // unsure, below threshold
    const val GREY = 0xFFC8C8C8.toInt()        // nothing found, check failed
    const val RED = 0xFFFF7878.toInt()         // no connection to the server

    data class Content(val text: String, val color: Int, val sure: Boolean)

    /** "0.90" whatever the device language (a French device would otherwise show "0,90"). */
    fun f2(x: Float): String = String.format(java.util.Locale.US, "%.2f", x)

    /** Popup for an inspection verdict; below [minConf] a disease/healthy answer becomes "not sure". */
    fun forVerdict(v: InspectController.Verdict, minConf: Float): Content {
        val namesSomething = v.kind == Kind.DISEASE || v.kind == Kind.HEALTHY
        val sure = !namesSomething || v.confidence >= minConf
        return when {
            namesSomething && !sure ->
                Content("Not sure - look closer\n(${v.label} ${f2(v.confidence)})", YELLOW, false)
            v.kind == Kind.NONE || v.kind == Kind.FAILED || v.kind == Kind.OTHER_PLANT -> Content(v.text, GREY, true)
            else -> Content(
                v.text + if (v.frames > 1) "\n${v.agree}/${v.frames} frames agree" else "",
                when (v.kind) {
                    Kind.DISEASE -> ORANGE
                    Kind.HEALTHY -> GREEN
                    else -> YELLOW                                       // UNCERTAIN
                },
                v.kind != Kind.UNCERTAIN,
            )
        }
    }

    /**
     * Stream mode (COCO): the most confident object at or above [minConf] (and in [allowed], if
     * not empty) pops up only once it is the top answer [needed] times in a row - one odd frame
     * must not flash a popup.
     */
    class StableTop(private val minConf: Float, private val allowed: Set<String>, private val needed: Int = 2) {
        private var label: String? = null
        private var count = 0

        /** Returns (label, confidence) to show, or null. */
        fun next(detections: List<Pair<String, Float>>): Pair<String, Float>? {
            val top = detections
                .filter { (l, c) -> c >= minConf && (allowed.isEmpty() || l.lowercase() in allowed) }
                .maxByOrNull { it.second }
            if (top == null) {
                label = null; count = 0
                return null
            }
            count = if (top.first == label) count + 1 else 1
            label = top.first
            return if (count >= needed) top else null
        }
    }
}
