package com.dsh.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** 16 kHz 单声道 16 位录音，供唤醒词和识别共用一条流。 */
class AudioEngine {
    private var record: AudioRecord? = null

    val sampleRate: Int get() = SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (record != null) return true
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return false
        val bufferSize = maxOf(min * 2, SAMPLE_RATE)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        record = r
        return true
    }

    fun read(buffer: ShortArray): Int {
        val r = record ?: return 0
        return r.read(buffer, 0, buffer.size)
    }

    fun stop() {
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
