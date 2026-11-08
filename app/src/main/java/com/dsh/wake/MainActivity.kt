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
import android.widget.CheckBox
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
    private lateinit var keywordLabel: TextView

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val dest = File(filesDir, "porcupine/keyword.ppn").apply { parentFile?.mkdirs() }
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "读不到这个文件" }
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                prefs.keywordPath = dest.absolutePath
                keywordLabel.text = "已选择：${uri.lastPathSegment ?: "keyword.ppn"}"
                Toast.makeText(this, "唤醒词已就位", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }

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
        keywordLabel = findViewById(R.id.keywordLabel)

        val accessKey = findViewById<EditText>(R.id.accessKey)
        val deepseekKey = findViewById<EditText>(R.id.deepseekKey)
        val chinese = findViewById<CheckBox>(R.id.chineseSwitch)
        val pick = findViewById<Button>(R.id.pickKeyword)

        accessKey.setText(prefs.accessKey)
        deepseekKey.setText(prefs.deepseekKey)
        chinese.isChecked = prefs.chinese
        keywordLabel.text = if (prefs.keywordPath.isEmpty()) {
            getString(R.string.hint_keyword)
        } else {
            "已选择：${File(prefs.keywordPath).name}"
        }
        status.text = if (WakeState.running) getString(R.string.running) else getString(R.string.idle)
        toggle.text = if (WakeState.running) getString(R.string.stop) else getString(R.string.start)

        pick.setOnClickListener { pickFile.launch(arrayOf("*/*")) }

        toggle.setOnClickListener {
            if (WakeState.running) {
                stopService(Intent(this, WakeService::class.java))
                WakeState.running = false
                status.text = getString(R.string.idle)
                toggle.text = getString(R.string.start)
            } else {
                prefs.accessKey = accessKey.text.toString().trim()
                prefs.deepseekKey = deepseekKey.text.toString().trim()
                prefs.chinese = chinese.isChecked
                if (!prefs.ready()) {
                    Toast.makeText(this, "先把两个 Key 和唤醒词文件填好", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                askAudioPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WakeState.running && !isServiceRunning()) {
            WakeState.running = false
            status.text = getString(R.string.idle)
            toggle.text = getString(R.string.start)
        } else if (WakeState.running) {
            status.text = getString(R.string.running)
            toggle.text = getString(R.string.stop)
        }
    }

    private fun isServiceRunning(): Boolean {
        // 简单判断：通知还在就认为在跑（服务本身由系统托管）。
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return manager.activeNotifications.any { it.id == 42 }
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
        status.text = "准备模型…"
        Thread {
            try {
                if (prefs.chinese) ensureChineseParams()
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
        Toast.makeText(this, "说「小深」试试", Toast.LENGTH_SHORT).show()
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

    private fun ensureChineseParams() {
        val dest = File(filesDir, "porcupine/porcupine_params_zh.pv")
        if (dest.exists() && dest.length() > 0) return
        runOnUiThread { status.text = "下载中文唤醒模型…" }
        Downloader.download(
            "https://raw.githubusercontent.com/Picovoice/porcupine/master/lib/common/porcupine_params_zh.pv",
            dest,
        )
        if (!dest.exists() || dest.length() == 0L) {
            throw IllegalStateException("中文唤醒模型下载失败")
        }
    }

    private fun ensureVoskModel(onProgress: (Int) -> Unit) {
        val root = File(filesDir, "vosk")
        val existing = root.listFiles()?.firstOrNull { it.isDirectory }
        if (existing != null) return
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
