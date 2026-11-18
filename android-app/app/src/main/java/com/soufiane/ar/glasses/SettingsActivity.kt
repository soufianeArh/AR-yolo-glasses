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
    private lateinit var debug: CheckBox
    private lateinit var sources: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val s = AppSettings.load(prefs)

        val videos = getExternalFilesDir(null)?.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.map { "video:${it.name}" }?.sorted() ?: emptyList()
        sources = (listOf("camera", "test-image") + videos + listOf(s.source)).distinct()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        form.addView(TextView(this).apply {
            text = "AR Plant Inspector - settings"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, 0, 24)
        })
        host = edit(form, "Server (Java scheduler) address", s.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        port = edit(form, "Server port", s.port.toString(), InputType.TYPE_CLASS_NUMBER)
        source = spinner(form, "Camera source (videos: adb push into the app's files folder)", sources, s.source)
        mode = spinner(form, "Mode: inspect = plant look-and-hold, stream = every frame", AppSettings.MODES, s.mode)
        model = spinner(form, "Stream mode model (inspect always uses plant)", AppSettings.MODELS, s.model)
        fps = edit(form, "Frames per second (1-30)", s.fps.toString(), InputType.TYPE_CLASS_NUMBER)
        votes = edit(form, "Inspect: frames voted per leaf (1-5)", s.votes.toString(), InputType.TYPE_CLASS_NUMBER)
        minConf = edit(form, "Popup minimum confidence 0-1 (empty = per model: plant 0.40, COCO 0.50)",
            if (s.minConf < 0f) "" else s.minConf.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        classes = edit(form, "Stream mode: classes allowed to pop up, comma separated (empty = all)",
            s.popupClasses.sorted().joinToString(", "), InputType.TYPE_CLASS_TEXT)
        crops = edit(form, "Inspect: crops to inspect, comma separated (empty = any plant)",
            s.crops.sorted().joinToString(", "), InputType.TYPE_CLASS_TEXT)
        debug = CheckBox(this).apply {
            text = "Debug view (camera frame, boxes, stats - off on the glasses)"
            isChecked = s.debug
            contentDescription = "debug"
        }
        form.addView(debug)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "Save"
            contentDescription = "save"
            setOnClickListener { save(prefs, s) }
        })
        buttons.addView(Button(this).apply {
            text = "Cancel"
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
            minConf = minConf.text.toString().trim().toFloatOrNull() ?: -1f,
            popupClasses = AppSettings.parseClasses(classes.text.toString()),
            crops = AppSettings.parseClasses(crops.text.toString()),
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
