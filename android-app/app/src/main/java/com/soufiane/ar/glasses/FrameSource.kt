package com.soufiane.ar.glasses

import android.graphics.Bitmap

// the controllers dont care where the frames come from
//Both the video (FileSource) and the camera (CameraSource) implement it//
interface FrameSource {
    // short name for the logs like the video file or the camera size
    val description: String

    // calls onFrame on a background thread for every frame
    // position is the time in the video or the time since the camera started
    fun start(fps: Double, onFrame: (Bitmap, Long) -> Unit)

    // closes the camera or stops the video thread
    fun stop()
}
