package com.soufiane.ar.glasses

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView

/**
 * Step 8.5: the popup the wearer sees, top-right of a black (= see-through) screen.
 *
 * - [show] fades it in, keeps it [holdMs], then fades it out.
 * - No flickering: showing the SAME key again while it is visible only extends the timer (no new
 *   fade-in); a different key replaces the text in place.
 * - Black fill + coloured border: on optical glasses black is transparent, so only the border and
 *   the text are visible, over the real plant.
 *
 * Logcat (tag ARGlasses): POPUP_SHOW / POPUP_EXTEND / POPUP_HIDE with times, for tests.
 * Call everything on the UI thread.
 */
class Popup(
    private val view: TextView,
    private val holdMs: Long = 3000,
    private val fadeInMs: Long = 150,
    private val fadeOutMs: Long = 500,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var key: String? = null
    private var shownAt = 0L
    private var fading = false
    private val hide = Runnable { fadeOut() }

    init {
        view.visibility = View.INVISIBLE
        view.alpha = 0f
    }

    fun show(key: String, text: CharSequence, color: Int) {
        handler.removeCallbacks(hide)
        val visible = this.key != null
        when {
            visible && key == this.key && !fading -> {
                Log.i(MainActivity.TAG, "POPUP_EXTEND key=$key")
            }
            else -> {
                this.key = key
                shownAt = SystemClock.elapsedRealtime()
                view.text = text
                view.setTextColor(color)
                view.background = GradientDrawable().apply {
                    setColor(0xFF000000.toInt())
                    setStroke(4, color)
                    cornerRadius = 24f
                }
                view.animate().cancel()
                fading = false
                if (!visible) view.alpha = 0f
                view.visibility = View.VISIBLE
                view.animate().alpha(1f).setDuration(fadeInMs).start()
                Log.i(MainActivity.TAG, "POPUP_SHOW key=$key text=${text.toString().replace('\n', ' ')}")
            }
        }
        handler.postDelayed(hide, holdMs)
    }

    private fun fadeOut() {
        val k = key ?: return
        fading = true
        view.animate().alpha(0f).setDuration(fadeOutMs).withEndAction {
            if (key == k && fading) {                                // not shown again meanwhile
                view.visibility = View.INVISIBLE
                Log.i(MainActivity.TAG, "POPUP_HIDE key=$k shown_ms=${SystemClock.elapsedRealtime() - shownAt}")
                key = null
                fading = false
            }
        }.start()
    }

    fun clear() {
        handler.removeCallbacks(hide)
        view.animate().cancel()
        view.visibility = View.INVISIBLE
        key = null
        fading = false
    }
}
