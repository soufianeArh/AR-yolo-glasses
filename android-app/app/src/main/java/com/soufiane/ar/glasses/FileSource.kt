package com.soufiane.ar.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * Plays a video file as if it were the camera, in real time, looping.
 *
 * Frames are taken at the wall-clock position of the video (like a camera: if the app is slow,
 * frames are skipped, never delayed). The video is decoded ahead of time at the target fps and
 * kept as JPEG bytes (~50 KB each, quality 92), decoded again while playing (a few ms per frame).
 * Kept as full bitmaps instead, a 35 s video took ~260 MB of the app's memory (8.6) - too much
 * for the glasses; as JPEGs ~15 MB.
 *
 * Put a video on the emulator with:
 *   adb push testdata/moving/couple-walking-among-cows-41565.mp4 /sdcard/Android/data/com.soufiane.ar.glasses/files/
 */
class FileSource(private val file: File, private val maxWidth: Int = 640) : FrameSource {

    private var frames: List<ByteArray> = emptyList()
    private var durationMs = 0L
    @Volatile private var running = false
    private var worker: Thread? = null

    override val description: String
        get() = "video ${file.name} (${durationMs / 1000} s, ${frames.size} frames)"

    /** Decode the video into frames at [fps]. Slow on the emulator: call it off the UI thread. */
    fun load(fps: Double) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val out = ArrayList<ByteArray>()
            val stepUs = (1_000_000.0 / fps).roundToLong()
            var t = 0L
            while (t < durationMs * 1000) {
                val f = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                val small = shrink(f)
                out.add(ByteArrayOutputStream(64 * 1024).also {
                    small.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }.toByteArray())
                small.recycle()
                t += stepUs
            }
            frames = out
            Log.i(MainActivity.TAG, "FileSource loaded ${file.name}: ${out.size} frames at $fps fps, ${durationMs} ms, " +
                "${out.sumOf { it.size } / 1024} KB in memory")
        } finally {
            r.release()
        }
    }

    private fun shrink(b: Bitmap): Bitmap {
        if (b.width <= maxWidth) return b
        val h = (b.height.toDouble() * maxWidth / b.width).toInt()
        val s = Bitmap.createScaledBitmap(b, maxWidth, h, true)
        if (s != b) b.recycle()
        return s
    }

    override fun start(fps: Double, onFrame: (Bitmap, Long) -> Unit) {
        if (frames.isEmpty()) error("FileSource: call load() first (no frames)")
        running = true
        val periodMs = 1000.0 / fps
        worker = thread(name = "file-source") {
            val t0 = SystemClock.elapsedRealtime()
            var tick = 0L
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val index = (((now - t0) / periodMs).toLong() % frames.size).toInt()   // looping, real time
                val jpeg = frames[index]
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null) onFrame(bmp, (index * periodMs).toLong())   // position in the video
                tick++
                val next = t0 + (tick * periodMs).toLong()
                val wait = next - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
        }
    }

    override fun stop() {
        running = false
        worker?.join(1000)
        worker = null
    }
}
