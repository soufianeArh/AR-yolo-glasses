package com.soufiane.ar.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
class FileSource(private val file: File, private val cacheDir: File? = null, private val maxWidth: Int = 640) : FrameSource {

    private var frames: List<ByteArray> = emptyList()
    private var durationMs = 0L
    @Volatile private var running = false
    private var worker: Thread? = null

    override val description: String
        get() = "video ${file.name} (${durationMs / 1000} s, ${frames.size} frames)"

    /**
     * The frames at [fps], reporting 0..100 to [onProgress]; call it off the UI thread.
     * Decoding seeks once per frame (~150 ms each on the emulator: 20-33 s for a 30 s video), so the
     * result is saved in [cacheDir]: from then on the same video loads in well under a second, even
     * after an app restart. (Decoding all frames in sequence, Android 9+, was tried in 8.9: no faster -
     * it decodes every frame of the video, 24-30/s, instead of the 8/s kept.)
     */
    fun load(fps: Double, onProgress: (Int) -> Unit = {}) {
        val t0 = SystemClock.elapsedRealtime()
        val cache = cacheDir?.let { File(it, "${file.name}-${file.length()}-${fps.toInt()}fps.frames") }
        if (cache != null && cache.isFile) {
            try {
                readCache(cache)
                Log.i(MainActivity.TAG, "FileSource loaded ${file.name}: ${frames.size} frames at $fps fps, ${durationMs} ms, " +
                    "${frames.sumOf { it.size } / 1024} KB in memory, from cache in ${SystemClock.elapsedRealtime() - t0} ms")
                return
            } catch (e: Exception) {
                Log.w(MainActivity.TAG, "FileSource: cache unreadable (${e.message}), decoding again")
                cache.delete()
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            frames = loadBySeeking(r, fps, onProgress)
        } finally {
            r.release()
        }
        Log.i(MainActivity.TAG, "FileSource loaded ${file.name}: ${frames.size} frames at $fps fps, ${durationMs} ms, " +
            "${frames.sumOf { it.size } / 1024} KB in memory, decoded in ${SystemClock.elapsedRealtime() - t0} ms")
        if (cache != null) try {
            writeCache(cache)
        } catch (e: Exception) {
            Log.w(MainActivity.TAG, "FileSource: could not save the cache: ${e.message}")
            cache.delete()
        }
    }

    /** Cache file: duration, frame count, then (length, JPEG bytes) per frame. */
    private fun writeCache(f: File) {
        val tmp = File(f.parentFile, f.name + ".part")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
            out.writeLong(durationMs)
            out.writeInt(frames.size)
            for (b in frames) { out.writeInt(b.size); out.write(b) }
        }
        if (!tmp.renameTo(f)) error("rename failed")
    }

    private fun readCache(f: File) {
        DataInputStream(BufferedInputStream(f.inputStream(), 256 * 1024)).use { inp ->
            durationMs = inp.readLong()
            val n = inp.readInt()
            frames = List(n) { ByteArray(inp.readInt()).also { inp.readFully(it) } }
        }
    }

    private fun loadBySeeking(r: MediaMetadataRetriever, fps: Double, onProgress: (Int) -> Unit): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val stepUs = (1_000_000.0 / fps).roundToLong()
        var t = 0L
        while (t < durationMs * 1000) {
            val f = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
            out.add(jpeg(f))
            t += stepUs
            onProgress((t / 10 / durationMs.coerceAtLeast(1)).toInt().coerceIn(0, 100))
        }
        return out
    }

    /** Shrink to [maxWidth] and keep as JPEG (q92); recycles [f]. */
    private fun jpeg(f: Bitmap): ByteArray {
        val small = shrink(f)
        val bytes = ByteArrayOutputStream(64 * 1024).also { small.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        small.recycle()
        return bytes
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
