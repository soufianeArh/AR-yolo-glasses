package com.soufiane.ar.glasses

import android.graphics.Bitmap

/**
 * Where camera frames come from. The rest of the app can't tell the difference:
 *   FileSource     a video file played as if it were the camera (PC testing, Step 8.3)
 *   CameraXSource  the emulator's camera = the PC webcam (Step 8.7)
 *   GooltonSource  the glasses' own camera (Step 9)
 */
interface FrameSource {
    /** A short human-readable description, e.g. "video couple-walking.mp4 (20 s, 8 fps)". */
    val description: String

    /**
     * Start delivering frames at roughly [fps] on a background thread.
     * [onFrame] gets the frame and its position in ms (video position for a file; time since
     * start for a camera) - used to check inspections against the test videos' timelines.
     */
    fun start(fps: Double, onFrame: (Bitmap, Long) -> Unit)

    fun stop()
}
