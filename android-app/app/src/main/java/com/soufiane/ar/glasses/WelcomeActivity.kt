package com.soufiane.ar.glasses

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Step 8.9: the entry screen. What the app does, whether the server is reachable, and three ways
 * in: inspect with the camera, demo videos, settings. Every button works with the arrow keys +
 * Enter too (the glasses' touchpad/buttons arrive as keys).
 *
 * Setting "start screen = inspect" (for the glasses): the app goes straight into inspection;
 * Back from there returns here.
 */
class WelcomeActivity : Activity() {

    private lateinit var serverLine: TextView
    private lateinit var demoLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = AppSettings.load(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE))
        val fromLauncher = intent?.action == Intent.ACTION_MAIN && savedInstanceState == null
        if (fromLauncher && s.startScreen == "inspect") {
            Log.i(MainActivity.TAG, "WELCOME skipped (start screen = inspect)")
            startActivity(Intent(this, MainActivity::class.java))
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(64, 48, 64, 48)
        }
        col.addView(text("AR Plant Inspector", 40f, Color.WHITE))
        col.addView(text("Look at a corn, tomato or potato leaf and hold still:\n" +
            "the glasses tell you if it is diseased.", 18f, Color.rgb(170, 170, 170)).apply { setPadding(0, 12, 0, 28) })
        serverLine = text("server: checking...", 16f, Color.rgb(170, 170, 170))
        demoLine = text("", 16f, Color.rgb(170, 170, 170)).apply { setPadding(0, 4, 0, 32) }
        col.addView(serverLine)
        col.addView(demoLine)

        val inspect = button("Start inspection (camera)", Color.rgb(120, 255, 120)) {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("source", "camera").putExtra("mode", "inspect"))
        }
        col.addView(inspect)
        col.addView(button("Demo videos", Color.rgb(255, 200, 90)) {
            startActivity(Intent(this, DemoListActivity::class.java))
        })
        col.addView(button("Settings", Color.rgb(170, 170, 170)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        setContentView(col)
        inspect.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val s = AppSettings.load(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE))
        serverLine.text = "server ${s.host}:${s.port}: checking..."
        thread(name = "welcome-check") {
            val scheduler = try {
                Socket().use { it.connect(InetSocketAddress(s.host, s.port), 1500) }; true
            } catch (_: Exception) {
                false
            }
            val listing = DemoRepository(getExternalFilesDir(null)!!, s.host, s.apiPort).list()
            Log.i(MainActivity.TAG, "WELCOME scheduler_reachable=$scheduler demos=${listing.demos.size} " +
                "demo_server=${listing.serverError ?: "ok"}")
            runOnUiThread {
                serverLine.text = "server ${s.host}:${s.port}: " + if (scheduler) "reachable ✓" else "NOT reachable ✗ (check Settings)"
                serverLine.setTextColor(if (scheduler) Color.rgb(120, 255, 120) else Color.rgb(255, 120, 120))
                demoLine.text = "${listing.demos.size} demo videos" +
                    if (listing.serverError != null) " (demo server not reachable: only videos already on this device)" else ""
            }
        }
    }

    private fun text(t: String, sp: Float, color: Int) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(color)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(Color.rgb(20, 20, 20)); setStroke(3, color); cornerRadius = 24f
        }
        // Focus (arrow keys) must be visible: brighter fill when focused.
        setOnFocusChangeListener { v, has ->
            (v.background as GradientDrawable).setColor(if (has) Color.rgb(60, 60, 60) else Color.rgb(20, 20, 20))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(900, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) }
    }
}
