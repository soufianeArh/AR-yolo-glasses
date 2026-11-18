package com.soufiane.ar.glasses

import android.content.SharedPreferences
import android.os.Bundle

data class AppSettings(
    val host: String = "10.0.2.2",
    val port: Int = 50051,
    val source: String = "test-image",
    val mode: String = "inspect",
    val model: String = "default",
    val fps: Int = 8,
    val votes: Int = 2,
    val debug: Boolean = false,
    val minConf: Float = -1f,
    val popupClasses: Set<String> = emptySet(),
    val crops: Set<String> = DEFAULT_CROPS,
    val apiPort: Int = 8090,
    val startScreen: String = "welcome",
    val showCamera: Boolean = true,
    val showBoxes: Boolean = true,
    val language: String = "en",
) {
    fun minConfFor(model: String): Float = if (minConf >= 0f) minConf else MIN_CONFIDENCE[model] ?: 0.5f

    // anything out of range falls back to the default
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
            language = language.takeIf { it in Lang.CODES } ?: d.language,
        )
    }

    fun describe() = "host=$host port=$port source=$source mode=$mode model=$model fps=$fps votes=$votes " +
        "debug=$debug minconf=${if (minConf < 0f) "per-model" else minConf} " +
        "popup_classes=${if (popupClasses.isEmpty()) "all" else popupClasses.sorted().joinToString(",")} " +
        "crops=${if (crops.isEmpty()) "any" else crops.sorted().joinToString(",")} api_port=$apiPort start_screen=$startScreen show_camera=$showCamera show_boxes=$showBoxes language=$language"

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("host", host).putInt("port", port).putString("source", source)
            .putString("mode", mode).putString("model", model).putInt("fps", fps).putInt("votes", votes)
            .putBoolean("debug", debug).putFloat("minconf", minConf)
            .putString("popup_classes", popupClasses.sorted().joinToString(","))
            .putString("crops", crops.sorted().joinToString(","))
            .putInt("api_port", apiPort).putString("start_screen", startScreen).putBoolean("show_camera", showCamera)
            .putBoolean("show_boxes", showBoxes)
            .putString("language", language)
            .apply()
    }

    // launch extras are saved too so one adb command configures the glasses
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
            language = e.getString("language") ?: language,
        ).validated()
        return s to changedKeys(this, s)
    }

    companion object {
        val MODES = listOf("inspect", "stream")
        val MODELS = listOf("default", "plant")
        val DEFAULT_CROPS = setOf("corn", "tomato", "potato")
        val START_SCREENS = listOf("welcome", "inspect")

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
                language = prefs.getString("language", d.language) ?: d.language,
            ).validated()
        }

        // spaces and empty items are ignored and everything is lowercased
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
            if (a.language != b.language) add("language")
        }
    }
}
