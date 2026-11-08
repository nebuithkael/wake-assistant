package com.dsh.wake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                prepareAndStart()
            } else {
                Toast.makeText(this, "没有麦克风权限就没法听", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        val deepseekKey = findViewById<EditText>(R.id.deepseekKey)
        val wakeWord = findViewById<EditText>(R.id.wakeWord)

        deepseekKey.setText(prefs.deepseekKey)
        wakeWord.setText(prefs.wakeWord)

        status.text = if (WakeState.running) getString(R.string.running) else getString(R.string.idle)
        toggle.text = if (WakeState.running) getString(R.string.stop) else getString(R.string.start)

        toggle.setOnClickListener {
            if (WakeState.running) {
                stopService(Intent(this, WakeService::class.java))
                WakeState.running = false
                status.text = getString(R.string.idle)
                toggle.text = getString(R.string.start)
            } else {
                prefs.deepseekKey = deepseekKey.text.toString().trim()
                prefs.wakeWord = wakeWord.text.toString().trim().ifEmpty { "小深" }
                if (!prefs.ready()) {
                    Toast.makeText(this, "先填 DeepSeek API Key", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                askAudioPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WakeState.running) {
            status.text = getString(R.string.running)
            toggle.text = getString(R.string.stop)
        }
    }

    private fun askAudioPermission() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) prepareAndStart() else askPermissions.launch(missing.toTypedArray())
    }

    private fun prepareAndStart() {
        if (File(filesDir, "vosk").listFiles()?.any { it.isDirectory } == true) {
            startListening()
            return
        }
        status.text = "下载识别模型…"
        Thread {
            try {
                ensureVoskModel { p -> runOnUiThread { status.text = "下载识别模型 $p%" } }
                runOnUiThread { startListening() }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "准备失败：${e.message}"
                    Toast.makeText(this, "准备失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startListening() {
        requestBatteryExemption()
        ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
        WakeState.running = true
        status.text = getString(R.string.running)
        toggle.text = getString(R.string.stop)
        Toast.makeText(this, "叫一声「${prefs.wakeWord}」试试", Toast.LENGTH_SHORT).show()
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureVoskModel(onProgress: (Int) -> Unit) {
        val root = File(filesDir, "vosk")
        if (root.listFiles()?.any { it.isDirectory } == true) return
        val zip = File(cacheDir, "vosk-cn.zip")
        Downloader.download(
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            zip,
            onProgress,
        )
        Downloader.unzip(zip, root)
        zip.delete()
    }
}

/** 让 Activity 知道服务状态（进程内）。 */
object WakeState {
    @Volatile
    var running: Boolean = false
}
