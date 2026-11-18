package com.soufiane.ar.glasses

import com.soufiane.ar.glasses.InspectController.Kind

object PopupRules {
    const val ORANGE = 0xFFFF963C.toInt()
    const val GREEN = 0xFF78FF78.toInt()
    const val YELLOW = 0xFFFFE650.toInt()
    const val GREY = 0xFFC8C8C8.toInt()
    const val RED = 0xFFFF7878.toInt()

    data class Content(val text: String, val color: Int, val sure: Boolean)

    // always a dot even on a chinese or french device
    fun f2(x: Float): String = String.format(java.util.Locale.US, "%.2f", x)

    fun forVerdict(v: InspectController.Verdict, minConf: Float): Content {
        val namesSomething = v.kind == Kind.DISEASE || v.kind == Kind.HEALTHY
        val sure = !namesSomething || v.confidence >= minConf
        return when {
            namesSomething && !sure ->
                Content(Lang.t("not_sure", Lang.label(v.label), f2(v.confidence)), YELLOW, false)
            v.kind == Kind.NONE || v.kind == Kind.FAILED || v.kind == Kind.OTHER_PLANT -> Content(v.text, GREY, true)
            else -> Content(
                v.text + if (v.frames > 1) "\n" + Lang.t("frames_agree", v.agree, v.frames) else "",
                when (v.kind) {
                    Kind.DISEASE -> ORANGE
                    Kind.HEALTHY -> GREEN
                    else -> YELLOW
                },
                v.kind != Kind.UNCERTAIN,
            )
        }
    }

    // one odd frame must not flash a popup
    class StableTop(private val minConf: Float, private val allowed: Set<String>, private val needed: Int = 2) {
        private var label: String? = null
        private var count = 0

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
