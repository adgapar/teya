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
            
            val options = Interpreter.Options()
            options.setNumThreads(2)
            
            interpreter = Interpreter(modelBuffer, options)
            Log.d("WakeWordEngine", "Model loaded successfully: $modelPath")
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Error loading model: $modelPath", e)
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

        try {
            audioRecord?.startRecording()
            Log.d("WakeWordEngine", "Microphone recording started")
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Failed to start recording", e)
            isRunning = false
            return
        }
        
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
        // Simple volume logging to verify mic is active
        val maxAmplitude = audio.maxOrNull() ?: 0
        if (maxAmplitude > 1500) {
            Log.v("WakeWordEngine", "Mic Audio Level: $maxAmplitude")
        }

        // TODO: Implement Mel-Spectrogram feature extraction here
    }

    fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Error stopping audio", e)
        }
        audioRecord = null
    }
}
