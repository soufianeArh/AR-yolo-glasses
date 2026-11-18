package com.soufiane.ar.glasses

import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DemoRepository(private val dir: File, private val host: String, private val apiPort: Int) {

    data class Demo(
        val id: String, val title: String, val crop: String, val mode: String, val file: String,
        val sizeBytes: Long, val hasTimeline: Boolean,
        val onServer: Boolean,
        val local: Boolean,
        val titleZh: String = "",
    ) {
        fun shownTitle() = if (Lang.current == "zh" && titleZh.isNotEmpty()) titleZh else title
    }

    data class Listing(val demos: List<Demo>, val serverError: String?)

    private val base get() = "http://$host:$apiPort"

    // server down still lists the videos already on the device
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

    fun fetch(d: Demo, onProgress: (Long, Long) -> Unit): File {
        val target = File(dir, d.file)
        val timelineFile = File(dir, d.file.removeSuffix(".mp4") + ".timeline.json")
        if (d.local) {
            if (d.hasTimeline && d.onServer && !timelineFile.isFile) {
                try {
                    timelineFile.writeText(get("$base/demos/${d.id}/timeline"))
                } catch (e: Exception) {
                    Log.w(MainActivity.TAG, "DEMOS no timeline for ${d.id}: ${e.message}")
                }
            }
            return target
        }
        // download to a part file first so a cut download is never played
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

        // a cut download doesnt count as local
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
