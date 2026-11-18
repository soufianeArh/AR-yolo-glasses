package com.soufiane.ar.glasses

import android.graphics.Bitmap

// the controllers dont care where the frames come from
interface FrameSource {
    val description: String

    fun start(fps: Double, onFrame: (Bitmap, Long) -> Unit)

    fun stop()
}
