package com.soufiane.ar.glasses

import android.content.SharedPreferences
import android.os.Bundle

/**
 * Step 8.6: the app's settings, saved between launches (SharedPreferences "settings").
 *
 * Where values come from, last wins: defaults < saved < launch extras. Launch extras are SAVED, so
 * one adb command configures the glasses for good (they may have no keyboard):
 *   adb shell am start -n com.soufiane.ar.glasses/.MainActivity --es host 172.22.36.122
 * After that a plain launch (or the launcher icon) uses host 172.22.36.122.
 * The settings screen (long-press anywhere / menu key) edits the same values.
 */
data class AppSettings(
    val host: String = "10.0.2.2",
    val port: Int = 50051,
    /** "test-image" (8.2: one bundled leaf), "camera" (8.7) or "video:<file in the app's files folder>". */
    val source: String = "test-image",
    /** "inspect" (plant, look and hold) or "stream" (every frame, [model]). */
    val mode: String = "inspect",
    /** Stream mode's model: "default" (COCO) or "plant". Inspect always uses "plant". */
    val model: String = "default",
    val fps: Int = 8,
    val votes: Int = 2,
    val debug: Boolean = false,
    /** Popup threshold; negative = per model (MIN_CONFIDENCE). */
    val minConf: Float = -1f,
    /** Stream mode: object classes allowed to pop up (lower case); empty = all. */
    val popupClasses: Set<String> = emptySet(),
    /** Inspect mode: the crops we inspect (first word of the plant label); empty = any plant. */
    val crops: Set<String> = DEFAULT_CROPS,
    /** 8.9: the control plane's REST API (demo videos), same host as the scheduler. */
    val apiPort: Int = 8090,
    /** 8.9: "welcome" (the entry screen) or "inspect" (glasses: straight into inspection). */
    val startScreen: String = "welcome",
    /** Draw the camera image behind the popup. On (emulator, phone, video see-through screens);
     *  OFF on optical see-through glasses, where the wearer already sees the real world. */
    val showCamera: Boolean = true,
    /** Draw the detection boxes (+ label, confidence) on the shown image. */
    val showBoxes: Boolean = true,
) {
    fun minConfFor(model: String): Float = if (minConf >= 0f) minConf else MIN_CONFIDENCE[model] ?: 0.5f

    /** Keeps values in range; unknown words fall back to the default. */
    fun validated(): AppSettings {
        val d = AppSettings()
        return copy(
            host = host.trim().ifEmpty { d.host },
            port = port.takeIf { it in 1..65535 } ?: d.port,
            source = source.trim().takeIf { it == "test-image" || it == "camera" || (it.startsWith("video:") && it.length > 6) } ?: d.source,
            mode = mode.takeIf { it in MODES } ?: d.mode,
            model = model.takeIf { it in MODELS } ?: d.model,
            fps = fps.coerceIn(1, 30),
            votes = votes.coerceIn(1, 5),
            minConf = if (minConf < 0f) -1f else minConf.coerceAtMost(1f),
            apiPort = apiPort.takeIf { it in 1..65535 } ?: d.apiPort,
            startScreen = startScreen.takeIf { it in START_SCREENS } ?: d.startScreen,
        )
    }

    /** One line for logcat (tests read it). */
    fun describe() = "host=$host port=$port source=$source mode=$mode model=$model fps=$fps votes=$votes " +
        "debug=$debug minconf=${if (minConf < 0f) "per-model" else minConf} " +
        "popup_classes=${if (popupClasses.isEmpty()) "all" else popupClasses.sorted().joinToString(",")} " +
        "crops=${if (crops.isEmpty()) "any" else crops.sorted().joinToString(",")} api_port=$apiPort start_screen=$startScreen show_camera=$showCamera show_boxes=$showBoxes"

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("host", host).putInt("port", port).putString("source", source)
            .putString("mode", mode).putString("model", model).putInt("fps", fps).putInt("votes", votes)
            .putBoolean("debug", debug).putFloat("minconf", minConf)
            .putString("popup_classes", popupClasses.sorted().joinToString(","))
            .putString("crops", crops.sorted().joinToString(","))
            .putInt("api_port", apiPort).putString("start_screen", startScreen).putBoolean("show_camera", showCamera)
            .putBoolean("show_boxes", showBoxes)
            .apply()
    }

    /** These settings overridden by launch extras; returns the result and the keys that changed. */
    fun withExtras(e: Bundle?): Pair<AppSettings, List<String>> {
        if (e == null || e.isEmpty) return this to emptyList()
        val s = copy(
            host = e.getString("host") ?: host,
            port = if (e.containsKey("port")) e.getInt("port", port) else port,
            source = e.getString("source") ?: source,
            mode = e.getString("mode") ?: mode,
            model = e.getString("model") ?: model,
            fps = if (e.containsKey("fps")) e.getInt("fps", fps) else fps,
            votes = if (e.containsKey("votes")) e.getInt("votes", votes) else votes,
            debug = if (e.containsKey("debug")) e.getBoolean("debug", debug) else debug,
            minConf = if (e.containsKey("minconf")) e.getFloat("minconf", minConf) else minConf,
            popupClasses = e.getString("popup_classes")?.let(::parseClasses) ?: popupClasses,
            crops = e.getString("crops")?.let(::parseClasses) ?: crops,
            apiPort = if (e.containsKey("api_port")) e.getInt("api_port", apiPort) else apiPort,
            startScreen = e.getString("start_screen") ?: startScreen,
            showCamera = if (e.containsKey("show_camera")) e.getBoolean("show_camera", showCamera) else showCamera,
            showBoxes = if (e.containsKey("show_boxes")) e.getBoolean("show_boxes", showBoxes) else showBoxes,
        ).validated()
        return s to changedKeys(this, s)
    }

    companion object {
        val MODES = listOf("inspect", "stream")
        val MODELS = listOf("default", "plant")
        val DEFAULT_CROPS = setOf("corn", "tomato", "potato")
        val START_SCREENS = listOf("welcome", "inspect")

        /** Popup threshold per model (8.5). plant 0.40: the correct answers in 8.4 went down to 0.44. */
        val MIN_CONFIDENCE = mapOf("plant" to 0.40f, "default" to 0.50f)

        fun load(prefs: SharedPreferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                host = prefs.getString("host", d.host) ?: d.host,
                port = prefs.getInt("port", d.port),
                source = prefs.getString("source", d.source) ?: d.source,
                mode = prefs.getString("mode", d.mode) ?: d.mode,
                model = prefs.getString("model", d.model) ?: d.model,
                fps = prefs.getInt("fps", d.fps),
                votes = prefs.getInt("votes", d.votes),
                debug = prefs.getBoolean("debug", d.debug),
                minConf = prefs.getFloat("minconf", d.minConf),
                popupClasses = parseClasses(prefs.getString("popup_classes", "") ?: ""),
                crops = prefs.getString("crops", null)?.let(::parseClasses) ?: d.crops,
                apiPort = prefs.getInt("api_port", d.apiPort),
                startScreen = prefs.getString("start_screen", d.startScreen) ?: d.startScreen,
                showCamera = prefs.getBoolean("show_camera", d.showCamera),
                showBoxes = prefs.getBoolean("show_boxes", d.showBoxes),
            ).validated()
        }

        /** "Person, cow ,," -> {person, cow} */
        fun parseClasses(text: String): Set<String> =
            text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        fun changedKeys(a: AppSettings, b: AppSettings): List<String> = buildList {
            if (a.host != b.host) add("host"); if (a.port != b.port) add("port")
            if (a.source != b.source) add("source"); if (a.mode != b.mode) add("mode")
            if (a.model != b.model) add("model"); if (a.fps != b.fps) add("fps")
            if (a.votes != b.votes) add("votes"); if (a.debug != b.debug) add("debug")
            if (a.minConf != b.minConf) add("minconf"); if (a.popupClasses != b.popupClasses) add("popup_classes")
            if (a.crops != b.crops) add("crops")
            if (a.apiPort != b.apiPort) add("api_port"); if (a.startScreen != b.startScreen) add("start_screen")
            if (a.showCamera != b.showCamera) add("show_camera")
            if (a.showBoxes != b.showBoxes) add("show_boxes")
        }
    }
}
