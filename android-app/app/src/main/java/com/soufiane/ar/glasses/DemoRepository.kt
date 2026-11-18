package com.soufiane.ar.glasses

import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Step 8.9: the demo videos. The catalog comes from the control plane's REST API (GET /demos on
 * the same host as the scheduler, port [AppSettings.apiPort]); a video is downloaded once into
 * the app's files folder, with its timeline, and played from there. Videos already in that folder
 * (adb push, Step 8.3) are listed too, so the list works offline as well.
 * Call everything off the UI thread.
 */
class DemoRepository(private val dir: File, private val host: String, private val apiPort: Int) {

    data class Demo(
        val id: String, val title: String, val crop: String, val mode: String, val file: String,
        val sizeBytes: Long, val hasTimeline: Boolean,
        val onServer: Boolean,        // the server can send it
        val local: Boolean,           // already in the files folder (complete)
        val titleZh: String = "",     // Chinese title from the catalog (empty = use [title])
    ) {
        fun shownTitle() = if (Lang.current == "zh" && titleZh.isNotEmpty()) titleZh else title
    }

    /** Result of [list]: the demos, and why the server part is missing (null = server answered). */
    data class Listing(val demos: List<Demo>, val serverError: String?)

    private val base get() = "http://$host:$apiPort"

    fun list(): Listing {
        val localFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.associate { it.name to it.length() } ?: emptyMap()
        return try {
            val remote = parse(get("$base/demos"))
            Listing(merge(remote, localFiles), null)
        } catch (e: Exception) {
            Log.w(MainActivity.TAG, "DEMOS server not reachable: ${e.javaClass.simpleName}: ${e.message}")
            Listing(merge(emptyList(), localFiles), "${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    /** Downloads the video (and timeline) unless already here; returns the local file. */
    fun fetch(d: Demo, onProgress: (Long, Long) -> Unit): File {
        val target = File(dir, d.file)
        val timelineFile = File(dir, d.file.removeSuffix(".mp4") + ".timeline.json")
        if (d.local) {                                // video already here (download or adb push)
            if (d.hasTimeline && d.onServer && !timelineFile.isFile) {
                try {
                    timelineFile.writeText(get("$base/demos/${d.id}/timeline"))
                } catch (e: Exception) {
                    Log.w(MainActivity.TAG, "DEMOS no timeline for ${d.id}: ${e.message}")   // plays without it
                }
            }
            return target
        }
        val tmp = File(dir, d.file + ".part")
        val c = URL("$base/demos/${d.id}/video").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) error("server answered ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: d.sizeBytes
            var done = 0L
            c.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) error("incomplete download ($done of $total bytes)")
        } finally {
            c.disconnect()
        }
        if (!tmp.renameTo(target)) error("could not save ${target.name}")
        if (d.hasTimeline) timelineFile.writeText(get("$base/demos/${d.id}/timeline"))
        Log.i(MainActivity.TAG, "DEMO_DOWNLOADED id=${d.id} file=${target.name} bytes=${target.length()}")
        return target
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 5000
        try {
            if (c.responseCode != 200) error("server answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun parse(json: String): List<Demo> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Demo(o.getString("id"), o.getString("title"), o.optString("crop", "-"), o.optString("mode", "inspect"),
                    o.getString("file"), o.optLong("size_bytes", 0), o.optBoolean("has_timeline", false),
                    onServer = o.optBoolean("available", false), local = false, titleZh = o.optString("title_zh", ""))
            }
        }

        /**
         * Server catalog + files already here. A server demo counts as local when a file with its name
         * and its full size is here (a cut download does not). Files the server does not know are
         * listed by file name, mode inspect.
         */
        fun merge(remote: List<Demo>, localFiles: Map<String, Long>): List<Demo> {
            val fromServer = remote.map { d ->
                val size = localFiles[d.file]
                d.copy(local = size != null && (d.sizeBytes == 0L || size == d.sizeBytes))
            }
            val known = remote.map { it.file }.toSet()
            val onlyLocal = localFiles.keys.filter { it !in known }.sorted().map { name ->
                Demo("local:$name", name.removeSuffix(".mp4"), "-", "inspect", name, localFiles.getValue(name),
                    hasTimeline = false, onServer = false, local = true)
            }
            return fromServer.filter { it.onServer || it.local } + onlyLocal
        }
    }
}
