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

class FileSource(private val file: File, private val cacheDir: File? = null, private val maxWidth: Int = 640) : FrameSource {

    private var frames: List<ByteArray> = emptyList()
    private var durationMs = 0L
    @Volatile private var running = false
    private var worker: Thread? = null

    override val description: String
        get() = "video ${file.name} (${durationMs / 1000} s, ${frames.size} frames)"

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

    // decoding is slow on the emulator so the frames are kept on disk
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
                // real time position like a camera so a slow frame is skipped not delayed
                val index = (((now - t0) / periodMs).toLong() % frames.size).toInt()
                val jpeg = frames[index]
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null) onFrame(bmp, (index * periodMs).toLong())
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
