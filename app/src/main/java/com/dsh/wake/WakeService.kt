package com.dsh.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * 常驻监听：本机离线识别，听到唤醒词就进入听写，然后问 DeepSeek 并朗读。
 * 唤醒词和转写共用一个 Vosk 中文模型，不需要任何账号或联网授权。
 */
class WakeService : Service() {

    private val tag = "WakeService"
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioEngine
    private val running = AtomicBoolean(false)

    private var voskModel: Model? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var worker: Thread? = null
    private var tone: ToneGenerator? = null

    private val aliases = ArrayList<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = AudioEngine()
        createChannel()
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (_: Exception) {
            null
        }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.CHINA
                tts?.setSpeechRate(1.05f)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY
        running.set(true)
        startForegroundCompat(notifyText("正在启动…"))
        worker = Thread({ loop() }, "wake-loop").also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        try {
            audio.stop()
        } catch (_: Exception) {
        }
        worker?.join(1500)
        release()
        super.onDestroy()
    }

    private fun release() {
        try {
            voskModel?.close()
        } catch (_: Exception) {
        }
        voskModel = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        try {
            tone?.release()
        } catch (_: Exception) {
        }
        tone = null
    }

    // ---------------- 主循环 ----------------

    private fun loop() {
        try {
            val dir = File(filesDir, "vosk").listFiles()?.firstOrNull { it.isDirectory }
                ?: throw IllegalStateException("识别模型还没准备好")
            LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
            voskModel = Model(dir.absolutePath)
            audio.start()

            val word = prefs.wakeWord.trim().ifEmpty { "小深" }
            aliases.clear()
            aliases.add(word)
            aliases.add(word.replace("小", "晓"))
            aliases.add(word.replace("深", "生"))
            aliases.add(word.replace("深", "沈"))
            setMode("正在聆听「$word」")

            val frame = ShortArray(FRAME)
            var recognizer = Recognizer(voskModel, 16000.0f)
            var framesSinceReset = 0

            while (running.get()) {
                val n = audio.readFully(frame)
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }
                recognizer.acceptWaveForm(toBytes(frame, n), n * 2)
                val heard = textOf(recognizer.partialResult)

                if (containsWake(heard)) {
                    onWake()
                    var command = capture(recognizer)
                    try {
                        recognizer.close()
                    } catch (_: Exception) {
                    }
                    recognizer = Recognizer(voskModel, 16000.0f)
                    framesSinceReset = 0
                    if (command.isEmpty()) {
                        // 只叫了名字没说话：再等一句，这时不带唤醒词
                        command = capture(recognizer)
                        try {
                            recognizer.close()
                        } catch (_: Exception) {
                        }
                        recognizer = Recognizer(voskModel, 16000.0f)
                    }
                    if (command.isNotEmpty()) answer(command)
                    setMode("正在聆听「$word」")
                    continue
                }

                if (++framesSinceReset > 600) {
                    try {
                        recognizer.reset()
                    } catch (_: Exception) {
                    }
                    framesSinceReset = 0
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "loop failed", e)
            setMode("出错：${e.message ?: "未知错误"}")
            running.set(false)
            stopSelf()
        }
    }

    /** 收一句命令：等到说完（静音 1 秒）或超时，返回去掉唤醒词的文字。 */
    private fun capture(recognizer: Recognizer): String {
        val frame = ShortArray(FRAME)
        var speech = false
        var silenceMs = 0
        var totalMs = 0
        var last = ""
        while (running.get()) {
            val n = audio.readFully(frame)
            if (n <= 0) {
                Thread.sleep(10)
                continue
            }
            val frameMs = n * 1000 / 16000
            totalMs += frameMs
            recognizer.acceptWaveForm(toBytes(frame, n), n * 2)

            val partial = textOf(recognizer.partialResult)
            if (partial.length > last.length) {
                last = partial
                speech = true
                silenceMs = 0
            } else if (speech && rms(frame, n) < 700) {
                silenceMs += frameMs
            }

            if (speech && silenceMs > 1000) break
            if (!speech && totalMs > 4000) break
            if (totalMs > 15000) break
        }
        val finalText = textOf(recognizer.finalResult)
        return stripWake(if (finalText.isNotEmpty()) finalText else last)
    }

    private fun onWake() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        setMode("在听，请说")
    }

    private fun answer(question: String) {
        setMode("思考中：$question")
        val reply = try {
            LlmClient.ask(prefs.deepseekKey, question)
        } catch (e: Exception) {
            setMode("问答失败：${e.message ?: "网络错误"}")
            return
        }
        if (!running.get()) return
        setMode("回答：$reply")
        speak(reply)
    }

    private fun speak(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            setMode("回答：$text（朗读不可用）")
            return
        }
        engine.stop()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // ---------------- 小工具 ----------------

    private fun containsWake(text: String): Boolean {
        if (text.isEmpty()) return false
        return aliases.any { it.isNotEmpty() && text.contains(it) }
    }

    private fun stripWake(text: String): String {
        var out = text
        aliases.sortedByDescending { it.length }.forEach { out = out.replace(it, "") }
        out = out.replace(Regex("^[，,。.、\\s]+"), "")
        return out.trim()
    }

    private fun textOf(json: String): String = try {
        val o = JSONObject(json)
        o.optString("text", "").ifEmpty { o.optString("partial", "") }
    } catch (_: Exception) {
        ""
    }

    private fun toBytes(frame: ShortArray, n: Int): ByteArray {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = frame[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = frame[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / maxOf(1, n))
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun notifyText(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小深唤醒")
            .setContentText(text.take(80))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun setMode(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, notifyText(text))
        } catch (e: Exception) {
            Log.w(tag, "notify", e)
        }
    }

    companion object {
        private const val CHANNEL = "wake"
        private const val NOTIF_ID = 42
        private const val FRAME = 1600
        const val ACTION_STOP = "com.dsh.wake.STOP"
    }
}
