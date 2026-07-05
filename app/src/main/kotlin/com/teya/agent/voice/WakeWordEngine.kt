package com.teya.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class WakeWordEngine(
    private val context: Context,
    private val modelPath: String = "hey_jarvis.tflite",
    private val onDetected: () -> Unit
) {
    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    
    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetFileDescriptor = context.assets.openFd(modelPath)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.length
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d("WakeWordEngine", "Model loaded: $modelPath")
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Error loading model", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true
        
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
        
        Thread {
            val audioBuffer = ShortArray(160) // 10ms of audio at 16kHz
            while (isRunning) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    processAudio(audioBuffer)
                }
            }
        }.start()
    }

    private fun processAudio(audio: ShortArray) {
        // Implementation of Mel-Spectrogram features for microWakeWord
        // For testing, we are logging the amplitude to prove the mic works
        val maxAmplitude = audio.maxOrNull() ?: 0
        if (maxAmplitude > 1000) {
            Log.v("WakeWordEngine", "Audio level: $maxAmplitude")
        }

        // Placeholder for TFLite inference
        // In a real implementation, we'd feed the spectrogram here
    }

    fun stop() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
