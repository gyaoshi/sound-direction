package com.gyaoshi.soundcompass

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.min

/**
 * 双通道音频采集。
 *
 * - 优先向系统申请「未经处理」的立体声采集，这样两个麦克风的原始时差不会被降噪算法抹掉；
 * - 依次尝试 UNPROCESSED → CAMCORDER → MIC → DEFAULT，并核查实际通道数确实为 2；
 * - 维护长度为 FFT_SIZE 的滑动历史，每读满 HOP 帧就回调一次分析。
 */
class AudioEngine(private val listener: Listener) {

    interface Listener {
        /** 在音频线程回调；a / b 是内部复用缓冲区，请勿持有引用。 */
        fun onBlock(a: FloatArray, b: FloatArray)

        fun onAudioReady(status: Status)

        fun onAudioError(message: String)
    }

    data class Status(
        val sampleRate: Int,
        val channels: Int,
        val sourceName: String,
        val stereo: Boolean
    )

    companion object {
        const val FFT_SIZE = 2048
        const val HOP = FFT_SIZE / 2

        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO

        private val SOURCE_CANDIDATES = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT"
        )

        private val RATE_CANDIDATES = intArrayOf(48000, 44100)
    }

    var sampleRate: Int = 48000
        private set
    var channels: Int = 0
        private set
    var sourceName: String = "-"
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val histA = FloatArray(FFT_SIZE)
    private val histB = FloatArray(FFT_SIZE)
    private var filled = 0

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return

        val picked = openRecord()
        if (picked == null) {
            listener.onAudioError("无法打开录音设备：本机可能不支持双通道采集，或不支持 48 kHz / 44.1 kHz。")
            return
        }

        val rec = picked.first
        sampleRate = picked.second
        channels = rec.channelCount
        sourceName = picked.third

        record = rec
        filled = 0

        listener.onAudioReady(
            Status(sampleRate, channels, sourceName, channels >= 2)
        )

        running = true
        rec.startRecording()

        thread = Thread({ loop(rec) }, "sound-compass-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        try {
            t?.join(600)
        } catch (_: InterruptedException) {
        }
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        record = null
        channels = 0
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(HOP * 2)
        try {
            while (running) {
                val read = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (!running) break
                    continue
                }
                val frames = read / 2
                if (frames <= 0) continue

                val f = min(frames, FFT_SIZE)
                System.arraycopy(histA, f, histA, 0, FFT_SIZE - f)
                System.arraycopy(histB, f, histB, 0, FFT_SIZE - f)
                val base = FFT_SIZE - f
                for (i in 0 until f) {
                    histA[base + i] = buf[i * 2] / 32768f
                    histB[base + i] = buf[i * 2 + 1] / 32768f
                }
                filled = min(FFT_SIZE, filled + f)
                if (filled >= FFT_SIZE) {
                    listener.onBlock(histA, histB)
                }
            }
        } catch (t: Throwable) {
            if (running) {
                listener.onAudioError("采集线程异常：${t.message}")
            }
        }
    }

    /** @return Triple(AudioRecord, 采样率, 音源名) */
    @SuppressLint("MissingPermission")
    private fun openRecord(): Triple<AudioRecord, Int, String>? {
        for (rate in RATE_CANDIDATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_MASK, ENCODING)
            if (minBuf <= 0) continue

            for ((source, name) in SOURCE_CANDIDATES) {
                val rec = try {
                    AudioRecord(
                        source,
                        rate,
                        CHANNEL_MASK,
                        ENCODING,
                        max(minBuf, HOP * 2 * 4)
                    )
                } catch (_: Exception) {
                    null
                } ?: continue

                val ok = rec.state == AudioRecord.STATE_INITIALIZED && rec.channelCount >= 2
                if (ok) {
                    return Triple(rec, rate, name)
                }
                try {
                    rec.release()
                } catch (_: Exception) {
                }
            }
        }

        // 退而求其次：单声道也能看到电平，只是无法测向
        for (rate in RATE_CANDIDATES) {
            val monoMask = AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(rate, monoMask, ENCODING)
            if (minBuf <= 0) continue
            for ((source, name) in SOURCE_CANDIDATES) {
                val rec = try {
                    AudioRecord(source, rate, monoMask, ENCODING, max(minBuf, HOP * 2 * 4))
                } catch (_: Exception) {
                    null
                } ?: continue
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return Triple(rec, rate, "$name(单声道)")
                }
                try {
                    rec.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }
}
