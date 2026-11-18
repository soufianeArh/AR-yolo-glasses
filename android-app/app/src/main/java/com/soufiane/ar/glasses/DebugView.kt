package com.soufiane.ar.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.soufiane.ar.proto.v1.FrameResult

/**
 * Development-only view (OFF on the glasses, where the screen stays black and see-through):
 * the current frame, the boxes of the latest answer drawn on it, and status lines.
 *
 * The boxes belong to an OLDER frame than the one shown (the answer takes a round trip), so on a
 * moving video they visibly trail behind - which is exactly why the glasses show a fixed popup
 * instead of boxes.
 */
class DebugView(context: Context) : View(context) {

    /** Draw the detection boxes + labels on the image (setting "show boxes", on by default). */
    @Volatile var boxes = true
    /** Draw the debug text lines (setting "debug"). */
    @Volatile var text = true
    private var frame: Bitmap? = null
    private var result: FrameResult? = null
    private var lines: List<String> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }
    private val labelBg = Paint().apply { color = Color.argb(200, 0, 0, 0) }
    private val labelPaint = Paint().apply { color = Color.YELLOW; textSize = 28f; isAntiAlias = true }
    private val statsPaint = Paint().apply { color = Color.rgb(120, 255, 120); textSize = 26f; isAntiAlias = true }

    /** Streaming mode (8.3). */
    fun show(s: StreamController.Snapshot) {
        frame = s.frame
        result = s.result
        lines = listOf(
            "DEBUG VIEW  frame #${s.frameId} on screen | boxes from frame #${s.result?.frameId ?: "-"} " +
                "(answer ${if (s.resultAgeMs >= 0) "${s.resultAgeMs} ms" else "-"} ago, " +
                "backend ${s.result?.backendMs ?: "-"} ms)",
        ) + s.stats.chunked(110)
        postInvalidate()
    }

    /** Inspection mode (8.4). */
    fun show(s: InspectController.Snapshot<Bitmap>) {
        frame = s.frame
        // Boxes only while the view holds still: once it moves, they would sit on the wrong leaf.
        result = if (s.state == InspectController.State.MOVING) null else s.lastResult
        lines = listOfNotNull(
            "DEBUG VIEW  " + s.debug,
            s.lastResult?.let { "last answer: frame #${it.frameId} ${it.status}, backend ${it.backendMs} ms" },
        )
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val f = frame ?: return

        // Fit the frame in the view, keeping its shape.
        val scale = minOf(width.toFloat() / f.width, height.toFloat() / f.height)
        val w = f.width * scale
        val h = f.height * scale
        val dst = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(f, Rect(0, 0, f.width, f.height), dst, null)

        if (boxes) result?.detectionsList?.forEach { d ->
            val r = RectF(dst.left + d.x1 * w, dst.top + d.y1 * h, dst.left + d.x2 * w, dst.top + d.y2 * h)
            canvas.drawRect(r, boxPaint)
            val text = "${Lang.label(d.label)} ${PopupRules.f2(d.confidence)}"
            val tw = labelPaint.measureText(text)
            // Label above the box; inside it when the box touches the top (it would be cut off).
            val top = if (r.top - 34f < 0f) r.top else r.top - 34f
            canvas.drawRect(r.left, top, r.left + tw + 12f, top + 34f, labelBg)
            canvas.drawText(text, r.left + 6f, top + 26f, labelPaint)
        }

        if (!text) return
        var y = 40f
        for (l in lines) {
            canvas.drawRect(10f, y - 30f, 20f + statsPaint.measureText(l), y + 8f, labelBg)
            canvas.drawText(l, 16f, y, statsPaint)
            y += 38f
        }
    }
}
