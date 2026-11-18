package com.soufiane.ar.glasses

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * Step 8.6: the settings screen. Opened from the main screen with a long-press (glasses touchpad)
 * or the menu key; Save writes [AppSettings] and returns - the main screen restarts with them.
 * Built in code (no XML): a short list of fields.
 */
class SettingsActivity : Activity() {

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var source: Spinner
    private lateinit var mode: Spinner
    private lateinit var model: Spinner
    private lateinit var fps: EditText
    private lateinit var votes: EditText
    private lateinit var minConf: EditText
    private lateinit var classes: EditText
    private lateinit var crops: EditText
    private lateinit var apiPort: EditText
    private lateinit var startScreen: Spinner
    private lateinit var debug: CheckBox
    private lateinit var showCamera: CheckBox
    private lateinit var showBoxes: CheckBox
    private lateinit var language: Spinner
    private lateinit var sources: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val s = AppSettings.load(prefs)
        Lang.current = s.language

        val videos = getExternalFilesDir(null)?.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.map { "video:${it.name}" }?.sorted() ?: emptyList()
        sources = (listOf("camera", "test-image") + videos + listOf(s.source)).distinct()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        form.addView(TextView(this).apply {
            text = Lang.t("settings_title")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, 0, 24)
        })
        language = spinner(form, Lang.t("f_language"), Lang.NAMES, Lang.NAMES[Lang.CODES.indexOf(s.language).coerceAtLeast(0)])
        host = edit(form, Lang.t("f_host"), s.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        port = edit(form, Lang.t("f_port"), s.port.toString(), InputType.TYPE_CLASS_NUMBER)
        apiPort = edit(form, Lang.t("f_api_port"), s.apiPort.toString(), InputType.TYPE_CLASS_NUMBER)
        startScreen = spinner(form, Lang.t("f_start_screen"),
            AppSettings.START_SCREENS, s.startScreen)
        source = spinner(form, Lang.t("f_source"), sources, s.source)
        mode = spinner(form, Lang.t("f_mode"), AppSettings.MODES, s.mode)
        model = spinner(form, Lang.t("f_model"), AppSettings.MODELS, s.model)
        fps = edit(form, Lang.t("f_fps"), s.fps.toString(), InputType.TYPE_CLASS_NUMBER)
        votes = edit(form, Lang.t("f_votes"), s.votes.toString(), InputType.TYPE_CLASS_NUMBER)
        minConf = edit(form, Lang.t("f_minconf"),
            if (s.minConf < 0f) "" else s.minConf.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        classes = edit(form, Lang.t("f_classes"),
            s.popupClasses.sorted().joinToString(", "), InputType.TYPE_CLASS_TEXT)
        crops = edit(form, Lang.t("f_crops"),
            s.crops.sorted().joinToString(", "), InputType.TYPE_CLASS_TEXT)
        showCamera = CheckBox(this).apply {
            text = Lang.t("cb_show_camera")
            isChecked = s.showCamera
        }
        form.addView(showCamera)
        showBoxes = CheckBox(this).apply {
            text = Lang.t("cb_show_boxes")
            isChecked = s.showBoxes
        }
        form.addView(showBoxes)
        debug = CheckBox(this).apply {
            text = Lang.t("cb_debug")
            isChecked = s.debug
            contentDescription = "debug"
        }
        form.addView(debug)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = Lang.t("btn_save")
            contentDescription = "save"
            setOnClickListener { save(prefs, s) }
        })
        buttons.addView(Button(this).apply {
            text = Lang.t("btn_cancel")
            setOnClickListener { finish() }
        })
        form.addView(buttons)

        setContentView(ScrollView(this).apply { addView(form) })
    }

    private fun save(prefs: android.content.SharedPreferences, old: AppSettings) {
        val s = AppSettings(
            host = host.text.toString(),
            port = port.text.toString().toIntOrNull() ?: -1,
            source = sources[source.selectedItemPosition],
            mode = AppSettings.MODES[mode.selectedItemPosition],
            model = AppSettings.MODELS[model.selectedItemPosition],
            fps = fps.text.toString().toIntOrNull() ?: old.fps,
            votes = votes.text.toString().toIntOrNull() ?: old.votes,
            debug = debug.isChecked,
            showCamera = showCamera.isChecked,
            showBoxes = showBoxes.isChecked,
            language = Lang.CODES[language.selectedItemPosition],
            minConf = minConf.text.toString().trim().toFloatOrNull() ?: -1f,
            popupClasses = AppSettings.parseClasses(classes.text.toString()),
            crops = AppSettings.parseClasses(crops.text.toString()),
            apiPort = apiPort.text.toString().toIntOrNull() ?: -1,
            startScreen = AppSettings.START_SCREENS[startScreen.selectedItemPosition],
        ).validated()
        s.save(prefs)
        Log.i(MainActivity.TAG, "SETTINGS_SAVED from=screen changed=${AppSettings.changedKeys(old, s)} ${s.describe()}")
        finish()
    }

    private fun label(form: LinearLayout, text: String) {
        form.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 16, 0, 0)
        })
    }

    private fun edit(form: LinearLayout, title: String, value: String, type: Int): EditText {
        label(form, title)
        return EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            contentDescription = title
            form.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun spinner(form: LinearLayout, title: String, items: List<String>, selected: String): Spinner {
        label(form, title)
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(items.indexOf(selected).coerceAtLeast(0))
            contentDescription = title
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {}
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            form.addView(this)
        }
    }
}
