package com.dsh.wake

import ai.picovoice.porcupine.Porcupine
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
import kotlin.math.sqrt

/**
 * 常驻监听：唤醒词 → 录一句 → 本机转写 → DeepSeek → 朗读 → 回到监听。
 * 全程离线转写，只有问答那一步联网。
 */
class WakeService : Service() {

    private val tag = "WakeService"
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioEngine
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private var porcupine: Porcupine? = null
    private var voskModel: Model? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var worker: Thread? = null
    private var tone: ToneGenerator? = null

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
        worker?.join(1500)
        release()
        super.onDestroy()
    }

    private fun release() {
        try {
            audio.stop()
        } catch (_: Exception) {
        }
        try {
            porcupine?.delete()
        } catch (_: Exception) {
        }
        porcupine = null
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
            initEngines()
            audio.start()
            val frame = ShortArray(porcupine!!.frameLength)
            setMode("正在聆听")
            while (running.get()) {
                val n = audio.readFully(frame)
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }
                val hit = try {
                    porcupine!!.process(frame) >= 0
                } catch (e: Exception) {
                    Log.w(tag, "porcupine", e)
                    false
                }
                if (hit) {
                    onWake()
                    listenAndAnswer(frame)
                    setMode("正在聆听")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "loop failed", e)
            setMode("出错：${e.message ?: "未知错误"}")
            running.set(false)
            stopSelf()
        }
    }

    private fun initEngines() {
        val modelPath = File(filesDir, "porcupine/porcupine_params_zh.pv")
        val builder = Porcupine.Builder().setAccessKey(prefs.accessKey)
        if (prefs.chinese && modelPath.exists()) builder.setModelPath(modelPath.absolutePath)
        builder.setKeywordPaths(arrayOf(prefs.keywordPath))
        porcupine = builder.build(this)

        val voskDir = File(filesDir, "vosk").listFiles()?.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("识别模型还没准备好")
        LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
        voskModel = Model(voskDir.absolutePath)
    }

    private fun onWake() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        setMode("正在听你说…")
    }

    private fun listenAndAnswer(frame: ShortArray) {
        val model = voskModel ?: return
        val recognizer = Recognizer(model, 16000.0f)
        val bytes = ByteArray(frame.size * 2)
        var speechSeen = false
        var silenceMs = 0
        var totalMs = 0
        try {
            while (running.get()) {
                val n = audio.readFully(frame)
                if (n <= 0) {
                    Thread.sleep(10)
                    continue
                }
                val frameMs = n * 1000 / 16000
                totalMs += frameMs

                if (rms(frame, n) > 600) {
                    speechSeen = true
                    silenceMs = 0
                } else if (speechSeen) {
                    silenceMs += frameMs
                }

                for (i in 0 until n) {
                    val v = frame[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                recognizer.acceptWaveForm(bytes, n * 2)

                if (speechSeen && silenceMs > 1000) break
                if (!speechSeen && totalMs > 5000) break
                if (totalMs > 15000) break
            }
        } finally {
            val json = try {
                recognizer.finalResult
            } catch (_: Exception) {
                "{}"
            }
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
            val question = try {
                JSONObject(json).optString("text", "").trim()
            } catch (_: Exception) {
                ""
            }
            if (question.isEmpty()) {
                setMode("没听清，继续聆听")
                return
            }
            answer(question)
        }
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
        const val ACTION_STOP = "com.dsh.wake.STOP"
    }
}
