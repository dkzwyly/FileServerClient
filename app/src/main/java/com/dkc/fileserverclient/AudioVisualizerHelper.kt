package com.dkc.fileserverclient

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.sqrt

class AudioVisualizerHelper(
    private val exoPlayer: ExoPlayer,
    private val onSpectrum: (FloatArray) -> Unit
) {
    private var visualizer: Visualizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isStarted = false

    fun start() {
        if (isStarted) return
        try {
            val audioSessionId = exoPlayer.audioSessionId
            if (audioSessionId == 0) {
                Log.w("AudioVisualizer", "AudioSessionId is 0, will retry in 200ms")
                mainHandler.postDelayed({ start() }, 200)
                return
            }

            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray,
                            samplingRate: Int
                        ) {}

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray,
                            samplingRate: Int
                        ) {
                            processFftData(fft)
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true
                )
                enabled = true
                isStarted = true
                Log.d("AudioVisualizer", "Visualizer started, sessionId=$audioSessionId")
            }
        } catch (e: Exception) {
            Log.e("AudioVisualizer", "Failed to start Visualizer: ${e.message}")
        }
    }

    private fun processFftData(fft: ByteArray) {
        val n = fft.size / 2
        val magnitudes = FloatArray(n)
        for (i in 0 until n) {
            val real = fft[i].toFloat()
            val imag = fft[i + n].toFloat()
            magnitudes[i] = sqrt(real * real + imag * imag)
        }

        val targetBands = 32
        val step = magnitudes.size / targetBands
        if (step == 0) return

        val spectrum = FloatArray(targetBands)
        for (i in 0 until targetBands) {
            var sum = 0f
            for (j in 0 until step) {
                val idx = i * step + j
                if (idx < magnitudes.size) {
                    sum += magnitudes[idx]
                }
            }
            val avg = sum / step
            var value = (avg / 50f).coerceIn(0f, 1f)

            // 低频补偿：前8个频段（对应低频区域）放大2.5倍
            if (i < 8) {
                value = (value * 2.5f).coerceIn(0f, 1f)
            }
            spectrum[i] = value
        }

        // 反转，让低频显示在右侧
        spectrum.reverse()
        mainHandler.post { onSpectrum(spectrum) }
    }

    fun stop() {
        visualizer?.let {
            it.enabled = false
            it.release()
        }
        visualizer = null
        isStarted = false
    }
}