package com.soufiane.ar.glasses

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.soufiane.ar.proto.v1.FrameResult
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val TAG = "ARGlasses"
        const val PREFS = "settings"
        const val DEVICE_TOKEN = "emulator-glasses-1"
    }

    private lateinit var settings: AppSettings
    private lateinit var popup: Popup
    private lateinit var debugView: DebugView
    private lateinit var status: TextView
    private lateinit var answer: TextView

    private val pipeLock = Any()
    private var generation = 0
    private var source: FrameSource? = null
    private var closer: (() -> Unit)? = null
    private var cachedSource: Pair<String, FileSource>? = null
    // one setup at a time so the same video is never decoded twice in paralel
    private val setupExecutor = Executors.newSingleThreadExecutor { Thread(it, "pipeline-setup") }

    private var cameraAsked = false
    private lateinit var bar: LinearLayout
    private lateinit var loading: TextView
    private lateinit var pauseButton: Button
    private lateinit var homeButton: Button
    private lateinit var cameraButton: Button
    private lateinit var sourceLabel: TextView
    private var alwaysShowBar = false
    private var builtLang = ""
    private var paused = false
    @Volatile private var timeline: Timeline? = null
    private val hideBar = Runnable { bar.visibility = View.GONE }
    private var lastPopupHold = 0
    private var lastStreamResult = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        answer = TextView(this).apply {
            setTextColor(Color.rgb(160, 160, 160))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(48, 40, 48, 40)
        }
        val popupView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(36, 24, 36, 24)
        }
        popup = Popup(popupView)
        status = TextView(this).apply {
            setTextColor(Color.rgb(90, 90, 90))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(24, 16, 24, 16)
        }
        debugView = DebugView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(debugView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(answer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END))
            addView(popupView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply { setMargins(0, 40, 40, 0) })
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START))
            isLongClickable = true
            setOnLongClickListener { openSettings(); true }
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) showBar(); false }
        }
        val lang0 = AppSettings.load(getSharedPreferences(PREFS, MODE_PRIVATE)).language
        Lang.current = lang0
        builtLang = lang0
        homeButton = barButton(Lang.t("home")) { goHome() }
        cameraButton = barButton(Lang.t("camera")) { switchToCamera() }
        pauseButton = barButton(Lang.t("pause")) { togglePause() }
        sourceLabel = TextView(this).apply {
            setTextColor(Color.rgb(230, 230, 230))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(6, 0, 0, 8)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 12, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(215, 10, 10, 10)); cornerRadius = 24f
            }
            addView(sourceLabel)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(homeButton)
                addView(barButton(Lang.t("videos")) { openVideos() })
                addView(cameraButton)
                addView(pauseButton)
                addView(barButton(Lang.t("settings")) { openSettings() })
            })
        }
        loading = TextView(this).apply {
            setTextColor(Color.rgb(220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(loading, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(20, 0, 0, 56) })
        setContentView(root)
        hideSystemBars(root)
        applyLaunchExtras(intent)
    }

    // only save the extras here because onResume restarts the pipeline anyway
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchExtras(intent)
    }

    private fun applyLaunchExtras(i: Intent?): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val extras = i?.extras
        val title = extras?.getString("title")
        val titled = extras?.getString("source")
        if (title != null && titled != null) prefs.edit().putString("title_$titled", title)
            .putString("title_zh_$titled", extras.getString("title_zh") ?: "").apply()
        extras?.remove("title")
        extras?.remove("title_zh")
        val openSettings = extras?.getBoolean("settings", false) ?: false
        extras?.remove("settings")
        val (s, changed) = AppSettings.load(prefs).withExtras(extras)
        if (changed.isNotEmpty()) {
            s.save(prefs)
            Log.i(TAG, "SETTINGS_SAVED from=launch changed=$changed ${s.describe()}")
        }
        i?.replaceExtras(Bundle())
        if (openSettings) openSettings()
        return changed.isNotEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (AppSettings.load(getSharedPreferences(PREFS, MODE_PRIVATE)).language != builtLang) {
            recreate()
            return
        }
        paused = false
        pauseButton.text = Lang.t("pause")
        startPipeline()
        showBar()
    }

    private fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        minHeight = 0; minimumHeight = 0
        setPadding(28, 14, 28, 14)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(35, 35, 35)); setStroke(2, Color.rgb(110, 110, 110)); cornerRadius = 20f
        }
        setOnFocusChangeListener { v, has ->
            (v.background as android.graphics.drawable.GradientDrawable)
                .setColor(if (has) Color.rgb(85, 85, 85) else Color.rgb(35, 35, 35))
            if (has) showBar()
        }
        setOnClickListener { onClick(); showBar() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, 16, 0) }
    }

    private fun showBar() {
        bar.visibility = View.VISIBLE
        bar.removeCallbacks(hideBar)
        if (!alwaysShowBar) bar.postDelayed(hideBar, 4000)
    }

    // bar stays visible while an image is shown and hides on a black screen
    private fun updateBar() {
        val s = settings
        alwaysShowBar = showsFrames(s)
        cameraButton.visibility = if (s.source == "camera") View.GONE else View.VISIBLE
        val what = Lang.t(if (s.mode == "inspect") "plant_inspection" else "object_stream")
        sourceLabel.text = when {
            s.source == "camera" -> Lang.t("live_camera") + "  ·  $what"
            s.source.startsWith("video:") -> {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val title = prefs.getString("title_zh_${s.source}", null)?.takeIf { Lang.current == "zh" && it.isNotEmpty() }
                    ?: prefs.getString("title_${s.source}", null)
                    ?: s.source.removePrefix("video:").removeSuffix(".mp4").removeSuffix("-6s").replace('-', ' ')
                Lang.t("video_prefix", title) + "  ·  $what"
            }
            else -> Lang.t("test_image")
        }
        showBar()
    }

    private fun switchToCamera() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val s = AppSettings.load(prefs).copy(source = "camera", mode = "inspect")
        s.save(prefs)
        Log.i(TAG, "SETTINGS_SAVED from=button changed=[source] ${s.describe()}")
        paused = false
        pauseButton.text = Lang.t("pause")
        startPipeline()
    }

    private fun openVideos() {
        startActivity(Intent(this, DemoListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val wasHidden = bar.visibility != View.VISIBLE
            showBar()
            if (wasHidden && event.keyCode != KeyEvent.KEYCODE_MENU) {
                homeButton.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun goHome() {
        startActivity(Intent(this, WelcomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            stopPipeline("paused")
            pauseButton.text = Lang.t("play")
            answer.setTextColor(Color.rgb(160, 160, 160))
            answer.text = Lang.t("paused")
        } else {
            pauseButton.text = Lang.t("pause")
            startPipeline()
        }
        Log.i(TAG, "PAUSE paused=$paused")
    }

    // stop here and not in onStop
    // onStop only comes once the next screen is drawn and frames still go out meanwhile
    override fun onPause() {
        stopPipeline("screen left")
        super.onPause()
    }

    override fun onDestroy() {
        setupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun showsFrames(s: AppSettings) =
        s.debug || s.source.startsWith("video:") || (s.source == "camera" && s.showCamera)

    private fun isCurrent(gen: Int) = synchronized(pipeLock) { gen == generation }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun startPipeline() {
        stopPipeline("restart")
        settings = AppSettings.load(getSharedPreferences(PREFS, MODE_PRIVATE))
        Lang.current = settings.language
        val gen = synchronized(pipeLock) { generation }
        Log.i(TAG, "CONFIG ${settings.describe()}")
        status.visibility = if (settings.debug) View.VISIBLE else View.GONE
        val showFrames = settings.debug || settings.source.startsWith("video:") ||
            (settings.source == "camera" && settings.showCamera)
        debugView.visibility = if (showFrames) View.VISIBLE else View.INVISIBLE
        debugView.boxes = settings.debug || settings.showBoxes
        debugView.text = settings.debug
        timeline = null
        answer.text = ""
        connLine = null
        connWasLost = false
        lastPopupHold = 0
        lastStreamResult = -1L
        val s = settings
        when {
            s.source.startsWith("video:") -> startVideo(gen, s, s.source.removePrefix("video:"))
            s.source == "camera" -> startCamera(gen, s)
            else -> startSingleImage(gen, s)
        }
        updateBar()
    }

    private fun stopPipeline(why: String) {
        val (src, close) = synchronized(pipeLock) {
            generation++
            val r = source to closer
            source = null
            closer = null
            r
        }
        popup.clear()
        src?.stop()
        close?.invoke()
        if (src != null || close != null) Log.i(TAG, "PIPELINE_STOP reason=\"$why\"")
    }

    private inline fun ifStillCurrent(gen: Int, start: () -> Unit): Boolean = synchronized(pipeLock) {
        if (gen != generation) return false
        start()
        true
    }

    private fun startVideo(gen: Int, s: AppSettings, name: String) {
        val file = File(getExternalFilesDir(null), name)
        if (!file.exists()) {
            setStatus(Lang.t("video_not_found", file.name), error = true)
            return
        }
        setStatus("loading $name ...")
        setupExecutor.execute {
            if (!isCurrent(gen)) {
                Log.i(TAG, "PIPELINE_DROPPED (screen left before loading)")
                return@execute
            }
            timeline = Timeline.forVideo(file)
            if (timeline != null) Log.i(TAG, "TIMELINE ${file.name}: ${timeline!!.holds.size} holds with expected answers")
            val key = "${file.absolutePath}@${s.fps}"
            val src = cachedSource?.takeIf { it.first == key }?.second ?: FileSource(file, File(cacheDir, "frames").apply { mkdirs() }).also {
                runOnUiThread { loading.text = Lang.t("loading_video", 0); loading.visibility = View.VISIBLE }
                var lastPct = -1
                it.load(s.fps.toDouble()) { pct ->
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread { loading.text = Lang.t("loading_video", pct) }
                    }
                }
                runOnUiThread { loading.visibility = View.GONE }
                cachedSource = key to it
            }
            startWith(gen, s, src)
        }
    }

    private fun startCamera(gen: Int, s: AppSettings) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraAsked) {
                cameraAsked = true
                Log.i(TAG, "CAMERA_PERMISSION requested")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            } else {
                setStatus(Lang.t("camera_refused"), error = true)
            }
            return
        }
        setupExecutor.execute {
            try {
                startWith(gen, s, CameraSource(this))
            } catch (e: Exception) {
                Log.e(TAG, "CAMERA_ERROR ${e.javaClass.simpleName}: ${e.message}")
                setStatus("camera error: ${e.message}", error = true)
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        val ok = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "CAMERA_PERMISSION granted=$ok")
        if (!ok) setStatus(Lang.t("camera_refused"), error = true)
    }

    private fun startWith(gen: Int, s: AppSettings, src: FrameSource) {
        val started = ifStillCurrent(gen) {
            if (s.mode == "inspect") startInspecting(gen, s, src) else startStreaming(gen, s, src)
            source = src
        }
        if (started) {
            Log.i(TAG, "PIPELINE_START mode=${s.mode} source=${src.description} server=${s.host}:${s.port}")
            runOnUiThread { setStatus("${s.mode}: ${src.description} -> ${s.host}:${s.port}") }
        } else {
            Log.i(TAG, "PIPELINE_DROPPED (screen left while loading)")
        }
    }

    private var connLine: String? = null
    private var connWasLost = false

    private fun showConnState(gen: Int, st: Connection.State, why: String) = runOnUiThread {
        if (!isCurrent(gen)) return@runOnUiThread
        when (st) {
            Connection.State.CONNECTED -> {
                if(connWasLost) popup.show("conn", Lang.t("server_back"), PopupRules.GREEN)
                connWasLost = false
                connLine = null
            }
            Connection.State.DISCONNECTED -> {
                if (!connWasLost) {
                    if (why.contains("slow network")) popup.show("conn", Lang.t("net_slow_reconnect"), PopupRules.YELLOW)
                    else popup.show("conn", Lang.t("no_connection"), PopupRules.RED)
                }
                connWasLost = true
                connLine = Lang.t("no_server")
            }
            Connection.State.CONNECTING -> if (connWasLost) connLine = Lang.t("reconnecting")
        }
        answer.setTextColor(if (connLine != null) PopupRules.RED else Color.rgb(160, 160, 160))
        answer.text = connLine ?: ""
    }

    private fun startStreaming(gen: Int, s: AppSettings, src: FrameSource) {
        lateinit var ctrl: StreamController
        val conn = Connection(s.host, s.port, DEVICE_TOKEN, { r, rtt -> ctrl.onResult(r, rtt) }) { st, why ->
            if (st == Connection.State.DISCONNECTED) ctrl.onStreamLost()
            showConnState(gen, st, why)
        }
        val stable = PopupRules.StableTop(s.minConfFor(s.model), s.popupClasses)
        ctrl = StreamController(conn::send, s.model, maxInFlight = 2,
            onLost = { conn.reset("no answer in time (slow network)") }) { snap ->
            if (showsFrames(s)) debugView.show(snap)
            val r = snap.result
            if (r != null && r.frameId != lastStreamResult) {
                lastStreamResult = r.frameId
                val show = stable.next(r.detectionsList.map { it.label to it.confidence })
                if (show != null) runOnUiThread {
                    if (isCurrent(gen)) popup.show(show.first, "${Lang.label(show.first)}  ${PopupRules.f2(show.second)}", PopupRules.GREEN)
                }
            }
        }
        closer = conn::close
        conn.start()
        src.start(s.fps.toDouble(), ctrl::onFrame)
    }

    private fun startInspecting(gen: Int, s: AppSettings, src: FrameSource) {
        lateinit var ctrl: InspectController<android.graphics.Bitmap>
        val conn = Connection(s.host, s.port, DEVICE_TOKEN, { r, rtt -> ctrl.onResult(r, rtt) }) { st, why ->
            if (st == Connection.State.DISCONNECTED) ctrl.onStreamLost()
            showConnState(gen, st, why)
        }
        ctrl = InspectController.forBitmaps(conn::send, s.votes, s.crops,
            onLost = { conn.reset("no answer in time (slow network)") }) { snap ->
            if (showsFrames(s)) debugView.show(snap)
            runOnUiThread { if (isCurrent(gen)) showInspection(snap, s) }
        }
        closer = conn::close
        conn.start()
        src.start(s.fps.toDouble()) { f, pos -> if (conn.state == Connection.State.CONNECTED) ctrl.onFrame(f, pos) }
    }

    private fun showInspection(snap: InspectController.Snapshot<*>, s: AppSettings) {
        if (connLine == null) answer.text = snap.checking ?: ""
        val v = snap.verdict ?: return
        if (snap.verdictHold == lastPopupHold) return
        lastPopupHold = snap.verdictHold
        if (connWasLost) return
        val p = PopupRules.forVerdict(v, s.minConfFor("plant"))
        Log.i(TAG, "POPUP_VERDICT hold=${snap.verdictHold} kind=${v.kind} label=${v.label} sure=${p.sure}")
        val expected = timeline?.expectedAt(snap.verdictPosMs)
        val text = if (expected != null) {
            val line = Timeline.verdictLine(expected, v.label)
            Log.i(TAG, "POPUP_EXPECTED hold=${snap.verdictHold} pos_ms=${snap.verdictPosMs} said=${v.label} $line")
            p.text + "\n" + line
        } else p.text
        popup.show("hold${snap.verdictHold}", text, p.color)
    }

    private fun startSingleImage(gen: Int, s: AppSettings) {
        val image = assets.open("test-leaf.png").use { it.readBytes() }
        ifStillCurrent(gen) {
            val c = BackendClient(s.host, s.port, DEVICE_TOKEN, ::showResult) { e ->
                Log.e(TAG, "RESULT_ERROR ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { setStatus(Lang.t("backend_error", e.message), error = true) }
            }
            closer = c::close
            c.send(1, image, listOf("plant"))
        }
        Log.i(TAG, "PIPELINE_START mode=single-image server=${s.host}:${s.port}")
        setStatus("sending test-leaf.png (${image.size / 1024} KB) as a 'plant' frame to ${s.host}:${s.port} ...")
    }

    private fun showResult(r: FrameResult, roundTripMs: Long) {
        val dets = r.detectionsList.sortedByDescending { it.confidence }
        val log = buildString {
            append("RESULT status=${r.status} worker=${r.workerId} infer_ms=${r.inferMs} ")
            append("backend_ms=${r.backendMs} round_trip_ms=$roundTripMs detections=")
            append(dets.joinToString(";") { "${it.label}|${"%.4f".format(it.confidence)}" })
        }
        Log.i(TAG, log)
        runOnUiThread {
            answer.text = if (dets.isEmpty()) "no disease found (${r.status})"
            else dets.take(3).joinToString("\n") { "${it.label}  ${"%.2f".format(it.confidence)}" }
            setStatus("answer from ${r.workerId}: ${r.status}, inference ${r.inferMs} ms, " +
                "backend ${r.backendMs} ms, round trip $roundTripMs ms")
        }
    }

    private fun setStatus(text: String, error: Boolean = false) {
        runOnUiThread {
            status.text = text
            if (error) {
                status.visibility = View.VISIBLE
                status.setTextColor(Color.rgb(255, 120, 120))
                Log.w(TAG, "STATUS_ERROR $text")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(view: View) {
        view.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}
