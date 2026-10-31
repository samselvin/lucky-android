package com.sam.lucky

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.rgb(2, 6, 11)
    private val cyan = Color.rgb(0, 229, 255)
    private val dim = Color.rgb(93, 138, 153)
    private val text = Color.rgb(214, 247, 255)

    private lateinit var toggle: Button
    private lateinit var logView: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        val prefs = getSharedPreferences("lucky", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            this.text = "LUCKY"
            textSize = 34f
            setTextColor(cyan)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.35f
            gravity = Gravity.CENTER
            setShadowLayer(18f, 0f, 0f, cyan)
        })
        root.addView(label("Say \"Hey Lucky\", wait for the beep, then speak.\nTry: \"Set an alarm at 5 AM\" or \"Timer for 10 minutes\".", text, 15f).apply {
            gravity = Gravity.CENTER
        })

        root.addView(label("1 · GEMINI KEY"))
        val key = EditText(this).apply {
            hint = "Paste your Gemini key (AQ.…)"
            setText(prefs.getString("gemini_key", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(key)
        root.addView(button("Save key") {
            prefs.edit().putString("gemini_key", key.text.toString().trim()).apply()
            toast("Key saved")
            refresh()
        })

        root.addView(label("2 · PERMISSIONS (one time)"))
        root.addView(button("Allow microphone & notifications") { askPermissions() })
        root.addView(button("Allow \"Display over other apps\" (for alarms)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("Let Lucky run in the background") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        status = label("", dim, 14f)
        root.addView(status)

        root.addView(label("3 · START"))
        toggle = button("") { toggleService() }
        root.addView(toggle)
        root.addView(button("🎤  Talk now (no wake word)") { startLucky(LuckyService.ACTION_TALK) })

        root.addView(label("ACTIVITY"))
        logView = label("", text, 15f)
        root.addView(logView)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        LuckyService.onLog = { refresh() }
        refresh()
    }

    override fun onPause() {
        LuckyService.onLog = null
        super.onPause()
    }

    private fun label(t: String, color: Int = dim, size: Float = 13f) = TextView(this).apply {
        this.text = t
        textSize = size
        setTextColor(color)
        setPadding(0, 36, 0, 10)
        if (color == dim && size == 13f) {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
        }
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        this.text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun startLucky(action: String?) {
        if (!micGranted()) {
            toast("Please allow the microphone first")
            askPermissions()
            return
        }
        val prefs = getSharedPreferences("lucky", Context.MODE_PRIVATE)
        if (prefs.getString("gemini_key", "").isNullOrBlank()) toast("Tip: add your Gemini key so Lucky can answer questions")
        val i = Intent(this, LuckyService::class.java)
        if (action != null) i.action = action
        startForegroundService(i)
        toggle.postDelayed({ refresh() }, 600)
    }

    private fun toggleService() {
        if (LuckyService.running) {
            stopService(Intent(this, LuckyService::class.java))
            toggle.postDelayed({ refresh() }, 600)
        } else {
            startLucky(null)
        }
    }

    private fun refresh() {
        toggle.text = if (LuckyService.running) "■  Stop listening" else "▶  Start \"Hey Lucky\""
        val pm = getSystemService(PowerManager::class.java)
        val checks = listOf(
            (if (micGranted()) "✓" else "✗") + " Microphone",
            (if (Settings.canDrawOverlays(this)) "✓" else "✗") + " Display over other apps",
            (if (pm.isIgnoringBatteryOptimizations(packageName)) "✓" else "✗") + " Background running",
            (if (getSharedPreferences("lucky", Context.MODE_PRIVATE).getString("gemini_key", "").isNullOrBlank()) "✗" else "✓") + " Gemini key"
        )
        status.text = checks.joinToString("\n")
        logView.text = LuckyService.logText().ifEmpty { "Nothing yet." }
    }
}
