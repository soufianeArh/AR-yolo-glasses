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

/**
 * The AR glasses app. A fully black screen (see-through on optical AR glasses) + the popup.
 *
 * Settings (8.6, [AppSettings]): saved between launches; edit them on the settings screen
 * (LONG-PRESS anywhere, or the menu key) or with launch extras, which are saved too:
 *   adb shell am start -n com.soufiane.ar.glasses/.MainActivity
 *     --es host <ip> --ei port <port>   the Java scheduler (WSL's IP on the Windows 10 dev PC:
 *                                       10.0.2.2 does not reach WSL there)
 *     --es source camera | test-image | video:<file in /sdcard/Android/data/com.soufiane.ar.glasses/files/>
 *     --es mode inspect | stream        plant look-and-hold (8.4) | every frame (8.3)
 *     --es model default | plant        stream mode's model
 *     --ei fps 8  --ei votes 2  --ez debug true  --ef minconf 0.4  --es popup_classes "person,cow"
 *     --es crops "corn,tomato,potato"   inspect: only these crops (empty = any plant)
 *     --ez settings true                open the settings screen
 *
 * 8.9: opened from the welcome / demo screens. Videos are always shown (demos); the camera image is
 * shown unless the setting "show camera" is off (optical see-through glasses: black = clear view).
 * Bottom-left bar: what is playing + Home / Videos / Camera / Pause / Settings. Always visible while
 * an image is shown; on a black (glasses) screen it appears on a tap or any key and hides after 4 s. Look-and-hold videos with a timeline add "expected: ... ✓/✗" under
 * each verdict. Back returns to the previous screen.
 *
 * Lifecycle: the pipeline (camera source + gRPC stream) runs only while the app is in front:
 * onResume starts it, onPause stops it (the scheduler sees the session close). onPause, not onStop:
 * onStop only comes once the next screen has drawn - 3.7 s after HOME on the loaded emulator, with a
 * frame still sent meanwhile (8.6 test). Answers of a stopped pipeline are dropped. Loaded videos
 * are kept, so coming back does not decode them again.
 *
 * Logcat (tag ARGlasses): CONFIG / SETTINGS_SAVED / PIPELINE_START / PIPELINE_STOP, plus the
 * RESULT / STATS / INSPECT_* / POPUP_* lines of each mode.
 */
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

    // The running pipeline. [generation] changes on every stop, so a setup thread that finishes
    // after the screen was left drops its work instead of streaming in the background.
    private val pipeLock = Any()
    private var generation = 0
    private var source: FrameSource? = null
    private var closer: (() -> Unit)? = null                         // closes the connection
    private var cachedSource: Pair<String, FileSource>? = null          // "file@fps" -> decoded frames
    // Setups run one at a time: leaving and coming back while a video decodes then waits for that
    // decode and reuses it, instead of decoding the same video twice in parallel (8.6 test: ~95 s).
    private val setupExecutor = Executors.newSingleThreadExecutor { Thread(it, "pipeline-setup") }

    private var cameraAsked = false
    private lateinit var bar: LinearLayout
    private lateinit var loading: TextView                         // 8.9: "Loading video... 45%" (demos)
    private lateinit var pauseButton: Button
    private lateinit var homeButton: Button
    private lateinit var cameraButton: Button
    private lateinit var sourceLabel: TextView
    private var alwaysShowBar = false
    private var paused = false
    @Volatile private var timeline: Timeline? = null
    private val hideBar = Runnable { bar.visibility = View.GONE }
    private var lastPopupHold = 0
    private var lastStreamResult = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)     // glasses: never sleep while in use

        answer = TextView(this).apply {                  // single image (8.2) / "checking leaf..." indicator
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
            setOnLongClickListener { openSettings(); true }                  // glasses touchpad: long-press
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) showBar(); false }
        }
        homeButton = barButton("⌂  Home") { goHome() }
        cameraButton = barButton("◉  Camera") { switchToCamera() }
        pauseButton = barButton("❚❚  Pause") { togglePause() }
        sourceLabel = TextView(this).apply {
            setTextColor(Color.rgb(230, 230, 230))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)                         // readable on any image
            setPadding(6, 0, 0, 8)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 12, 14)
            // A solid dark panel: readable over any image (bright slides, sky, text).
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(215, 10, 10, 10)); cornerRadius = 24f
            }
            addView(sourceLabel)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(homeButton)
                addView(barButton("▶  Videos") { openVideos() })
                addView(cameraButton)
                addView(pauseButton)
                addView(barButton("⚙  Settings") { openSettings() })
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

    /**
     * `am start` on the running app (singleTop): save the new extras only. Android pauses the app
     * before onNewIntent and resumes it after, and onResume restarts the pipeline with them.
     * (Restarting here too started it TWICE: one stream left running untracked - 8.6 test.)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchExtras(intent)
    }

    /** Saves the launch extras into the settings, then forgets them (so the settings screen wins later). */
    private fun applyLaunchExtras(i: Intent?): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val extras = i?.extras
        // 8.9: the demo list sends the video's title; remembered per source, for the bar.
        val title = extras?.getString("title")
        val titled = extras?.getString("source")
        if (title != null && titled != null) prefs.edit().putString("title_$titled", title).apply()
        extras?.remove("title")
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
        paused = false
        pauseButton.text = "❚❚  Pause"
        startPipeline()
        showBar()
    }

    // ------------------------------------------------------------------ icons (8.9)

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

    /** Shows the bar: for good while an image is shown, else for 4 s (any tap or key). */
    private fun showBar() {
        bar.visibility = View.VISIBLE
        bar.removeCallbacks(hideBar)
        if (!alwaysShowBar) bar.postDelayed(hideBar, 4000)
    }

    /** What is playing + which buttons make sense; called whenever the pipeline (re)starts. */
    private fun updateBar() {
        val s = settings
        alwaysShowBar = showsFrames(s)
        cameraButton.visibility = if (s.source == "camera") View.GONE else View.VISIBLE   // only moves that change something
        val what = if (s.mode == "inspect") "plant inspection" else "object stream"
        sourceLabel.text = when {
            s.source == "camera" -> "Live camera  ·  $what"
            s.source.startsWith("video:") -> {
                val title = getSharedPreferences(PREFS, MODE_PRIVATE).getString("title_${s.source}", null)
                    ?: s.source.removePrefix("video:").removeSuffix(".mp4").removeSuffix("-6s").replace('-', ' ')
                "Video: $title  ·  $what"
            }
            else -> "Test image"
        }
        showBar()
    }

    /** Live camera inspection, from wherever we are. */
    private fun switchToCamera() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val s = AppSettings.load(prefs).copy(source = "camera", mode = "inspect")
        s.save(prefs)
        Log.i(TAG, "SETTINGS_SAVED from=button changed=[source] ${s.describe()}")
        paused = false
        pauseButton.text = "❚❚  Pause"
        startPipeline()
    }

    /** Back to the demo list (the existing one if it is below us: no pile of screens). */
    private fun openVideos() {
        startActivity(Intent(this, DemoListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val wasHidden = bar.visibility != View.VISIBLE
            showBar()
            if (wasHidden && event.keyCode != KeyEvent.KEYCODE_MENU) {   // first key only wakes the icons
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
            pauseButton.text = "▶  Play"
            answer.setTextColor(Color.rgb(160, 160, 160))
            answer.text = "paused"
        } else {
            pauseButton.text = "❚❚  Pause"
            startPipeline()
        }
        Log.i(TAG, "PAUSE paused=$paused")
    }

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

    /** False once the pipeline [gen] was stopped: its late answers must not reach the screen. */
    private fun isCurrent(gen: Int) = synchronized(pipeLock) { gen == generation }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))           // onPause -> pipeline stops
    }

    // ------------------------------------------------------------------ pipeline

    private fun startPipeline() {
        stopPipeline("restart")                       // never two pipelines, whatever calls this twice
        settings = AppSettings.load(getSharedPreferences(PREFS, MODE_PRIVATE))
        val gen = synchronized(pipeLock) { generation }
        Log.i(TAG, "CONFIG ${settings.describe()}")
        status.visibility = if (settings.debug) View.VISIBLE else View.GONE
        // Videos are demos: always shown. The camera: shown unless switched off for see-through glasses.
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

    /** Runs [start] only if the screen was not left meanwhile; returns false if dropped. */
    private inline fun ifStillCurrent(gen: Int, start: () -> Unit): Boolean = synchronized(pipeLock) {
        if (gen != generation) return false
        start()
        true
    }

    private fun startVideo(gen: Int, s: AppSettings, name: String) {
        val file = File(getExternalFilesDir(null), name)
        if (!file.exists()) {
            setStatus("video not found: $file  (adb push it there)", error = true)
            return
        }
        setStatus("loading $name ...")
        setupExecutor.execute {
            if (!isCurrent(gen)) {                                       // left before it even began
                Log.i(TAG, "PIPELINE_DROPPED (screen left before loading)")
                return@execute
            }
            timeline = Timeline.forVideo(file)
            if (timeline != null) Log.i(TAG, "TIMELINE ${file.name}: ${timeline!!.holds.size} holds with expected answers")
            val key = "${file.absolutePath}@${s.fps}"
            val src = cachedSource?.takeIf { it.first == key }?.second ?: FileSource(file, File(cacheDir, "frames").apply { mkdirs() }).also {
                runOnUiThread { loading.text = "Loading video..."; loading.visibility = View.VISIBLE }
                var lastPct = -1
                it.load(s.fps.toDouble()) { pct ->
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread { loading.text = "Loading video...  $pct%\n(first time only)" }
                    }
                }
                runOnUiThread { loading.visibility = View.GONE }
                cachedSource = key to it
            }
            startWith(gen, s, src)
        }
    }

    /** 8.7: the device camera (emulator: the PC webcam). Asks for the permission the first time. */
    private fun startCamera(gen: Int, s: AppSettings) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraAsked) {                       // once: a refusal must not bring the dialog back forever
                cameraAsked = true
                Log.i(TAG, "CAMERA_PERMISSION requested")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)  // onResume runs again after the answer
            } else {
                setStatus("camera permission refused - choose another source in settings", error = true)
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
        if (!ok) setStatus("camera permission refused - choose another source in settings", error = true)
    }

    /** Starts the chosen mode on [src] unless the screen was left meanwhile. */
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

    // ------------------------------------------------------------------ connection (8.7)

    private var connLine: String? = null          // shown bottom-right while the server is unreachable
    private var connWasLost = false

    /** What the wearer sees when the server goes away and comes back. */
    private fun showConnState(gen: Int, st: Connection.State, why: String) = runOnUiThread {
        if (!isCurrent(gen)) return@runOnUiThread
        when (st) {
            Connection.State.CONNECTED -> {
                if (connWasLost) popup.show("conn", "Server back", PopupRules.GREEN)
                connWasLost = false
                connLine = null
            }
            Connection.State.DISCONNECTED -> {
                if (!connWasLost) {
                    if (why.contains("slow network")) popup.show("conn", "Network too slow\nreconnecting...", PopupRules.YELLOW)
                    else popup.show("conn", "No connection to the server\nretrying...", PopupRules.RED)
                }
                connWasLost = true
                connLine = "no server - " + why.substringAfterLast("; ", "retrying")
            }
            Connection.State.CONNECTING -> if (connWasLost) connLine = "reconnecting..."
        }
        answer.setTextColor(if (connLine != null) PopupRules.RED else Color.rgb(160, 160, 160))
        answer.text = connLine ?: ""
    }

    /** 8.3: every frame to [AppSettings.model]; popup = a stable top object. Call under [pipeLock]. */
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
                    if (isCurrent(gen)) popup.show(show.first, "${show.first}  ${PopupRules.f2(show.second)}", PopupRules.GREEN)
                }
            }
        }
        closer = conn::close
        conn.start()
        src.start(s.fps.toDouble(), ctrl::onFrame)
    }

    /** 8.4: plant inspection "look and hold". Call under [pipeLock]. */
    private fun startInspecting(gen: Int, s: AppSettings, src: FrameSource) {
        lateinit var ctrl: InspectController<android.graphics.Bitmap>
        val conn = Connection(s.host, s.port, DEVICE_TOKEN, { r, rtt -> ctrl.onResult(r, rtt) }) { st, why ->
            if (st == Connection.State.DISCONNECTED) ctrl.onStreamLost()       // a check in progress ends
            showConnState(gen, st, why)
        }
        ctrl = InspectController.forBitmaps(conn::send, s.votes, s.crops,
            onLost = { conn.reset("no answer in time (slow network)") }) { snap ->
            if (showsFrames(s)) debugView.show(snap)
            runOnUiThread { if (isCurrent(gen)) showInspection(snap, s) }
        }
        closer = conn::close
        conn.start()
        // No server: no inspection (a check would only fail). Frames are skipped until it is back.
        src.start(s.fps.toDouble()) { f, pos -> if (conn.state == Connection.State.CONNECTED) ctrl.onFrame(f, pos) }
    }

    /** The wearer's view: a small "checking..." while the plant model works, then the popup. */
    private fun showInspection(snap: InspectController.Snapshot<*>, s: AppSettings) {
        if (connLine == null) answer.text = snap.checking ?: ""
        val v = snap.verdict ?: return
        if (snap.verdictHold == lastPopupHold) return                  // already shown for this hold
        lastPopupHold = snap.verdictHold
        if (connWasLost) return                                     // "no connection" says it already
        val p = PopupRules.forVerdict(v, s.minConfFor("plant"))
        Log.i(TAG, "POPUP_VERDICT hold=${snap.verdictHold} kind=${v.kind} label=${v.label} sure=${p.sure}")
        // 8.9: test videos know the right answer of each leaf - show it under the verdict.
        val expected = timeline?.expectedAt(snap.verdictPosMs)
        val text = if (expected != null) {
            val line = Timeline.verdictLine(expected, v.label)
            Log.i(TAG, "POPUP_EXPECTED hold=${snap.verdictHold} pos_ms=${snap.verdictPosMs} said=${v.label} $line")
            p.text + "\n" + line
        } else p.text
        popup.show("hold${snap.verdictHold}", text, p.color)
    }

    /** 8.2: send one bundled leaf image, model plant. */
    private fun startSingleImage(gen: Int, s: AppSettings) {
        val image = assets.open("test-leaf.png").use { it.readBytes() }
        ifStillCurrent(gen) {
            val c = BackendClient(s.host, s.port, DEVICE_TOKEN, ::showResult) { e ->
                Log.e(TAG, "RESULT_ERROR ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { setStatus("backend error: ${e.message}", error = true) }
            }
            closer = c::close
            c.send(1, image, listOf("plant"))
        }
        Log.i(TAG, "PIPELINE_START mode=single-image server=${s.host}:${s.port}")
        setStatus("sending test-leaf.png (${image.size / 1024} KB) as a 'plant' frame to ${s.host}:${s.port} ...")
    }

    private fun showResult(r: FrameResult, roundTripMs: Long) {
        // One line per detection, highest first - and the same as one log line for tests.
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

    /** Status line (debug only); errors are always shown, the wearer must know something is wrong. */
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
