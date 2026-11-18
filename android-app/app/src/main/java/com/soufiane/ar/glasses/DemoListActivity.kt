package com.soufiane.ar.glasses

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Step 8.9: the demo videos. The list comes from the server (GET /demos) plus the videos already
 * on the device; picking one downloads it once (with progress), then starts it: inspect mode for
 * the leaf videos, stream mode for the object videos. Works with the arrow keys + Enter too.
 */
class DemoListActivity : Activity() {

    private lateinit var header: TextView
    private lateinit var list: ListView
    private lateinit var repo: DemoRepository
    private lateinit var dir: File
    private var demos: List<DemoRepository.Demo> = emptyList()
    private val thumbs = ConcurrentHashMap<String, Bitmap>()
    private val durations = ConcurrentHashMap<String, Long>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "demos") }
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = AppSettings.load(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE))
        dir = getExternalFilesDir(null)!!
        repo = DemoRepository(dir, s.host, s.apiPort)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 32, 48, 16)
        }
        col.addView(TextView(this).apply {
            text = "Demo videos"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(Color.WHITE)
        })
        header = TextView(this).apply {
            text = "loading the list..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.rgb(170, 170, 170))
            setPadding(0, 6, 0, 16)
        }
        col.addView(header)
        list = ListView(this).apply {
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ -> pick(demos[pos]) }
            itemsCanFocus = false
            isFocusable = true
        }
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() = worker.execute {
        val l = repo.list()
        runOnUiThread {
            demos = l.demos
            header.text = when {
                l.serverError != null -> "Demo server not reachable - showing the videos already on this device. " +
                    "(${l.serverError})"
                else -> "Pick a video: it plays as if it were the camera, and the glasses inspect it."
            }
            (list.adapter as BaseAdapter).notifyDataSetChanged()
            list.requestFocus()
        }
        Log.i(MainActivity.TAG, "DEMOS listed ${l.demos.size}: " + l.demos.joinToString { "${it.id}(${if (it.local) "local" else "server"})" })
        for (d in l.demos.filter { it.local }) thumbnail(d)
    }

    /** First frame at 1 s + duration, for videos already on the device. */
    private fun thumbnail(d: DemoRepository.Demo) {
        if (thumbs.containsKey(d.file)) return
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(File(dir, d.file).absolutePath)
            durations[d.file] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                thumbs[d.file] = Bitmap.createScaledBitmap(f, 256, (256 * f.height / f.width.coerceAtLeast(1)), true)
                if (f != thumbs[d.file]) f.recycle()
            }
        } catch (e: Exception) {
            Log.w(MainActivity.TAG, "DEMOS no thumbnail for ${d.file}: ${e.message}")
        } finally {
            r.release()
        }
        runOnUiThread { (list.adapter as BaseAdapter).notifyDataSetChanged() }
    }

    private fun pick(d: DemoRepository.Demo) {
        if (busy) return
        busy = true
        Log.i(MainActivity.TAG, "DEMO_PICKED id=${d.id} local=${d.local}")
        worker.execute {
            try {
                var lastPct = -1
                val file = repo.fetch(d) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread { header.text = "downloading ${d.title}: $pct% of ${total / 1_000_000} MB" }
                    }
                }
                thumbnail(d.copy(local = true))
                runOnUiThread {
                    header.text = "starting ${d.title}..."
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra("source", "video:${file.name}").putExtra("mode", d.mode).putExtra("title", d.title))
                }
            } catch (e: Exception) {
                Log.e(MainActivity.TAG, "DEMO_ERROR id=${d.id} ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { header.text = "could not get ${d.title}: ${e.message}" }
            } finally {
                busy = false
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = demos.size
        override fun getItem(p: Int) = demos[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val d = demos[p]
            val row = LinearLayout(this@DemoListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 14, 8, 14)
            }
            row.addView(ImageView(this@DemoListActivity).apply {
                val t = thumbs[d.file]
                if (t != null) setImageBitmap(t) else setBackgroundColor(Color.rgb(35, 35, 35))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(256, 144))
            val texts = LinearLayout(this@DemoListActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 0, 0, 0)
            }
            texts.addView(TextView(this@DemoListActivity).apply {
                text = d.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
            })
            val secs = durations[d.file]?.let { " · ${it / 1000} s" } ?: ""
            val where = if (d.local) "on this device" else "tap to download (${"%.1f".format(d.sizeBytes / 1e6)} MB)"
            texts.addView(TextView(this@DemoListActivity).apply {
                text = "${if (d.crop == "-") "objects" else d.crop} · ${if (d.mode == "inspect") "plant inspection" else "object stream"}$secs · $where" +
                    if (d.hasTimeline) " · shows the expected answers" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (d.local) Color.rgb(120, 255, 120) else Color.rgb(255, 200, 90))
            })
            row.addView(texts)
            return row
        }
    }
}
