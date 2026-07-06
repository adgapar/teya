package com.teya.agent.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.teya.agent.brain.MistralClient
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class VoicePipeline(private val context: Context) {
    
    private val wakeWordEngine = WakeWordEngine(context) {
        onDetected()
    }
    
    private var wakeWordCallback: (() -> Unit)? = null
    private var mistralClient: MistralClient? = null

    fun setMistralClient(client: MistralClient) {
        this.mistralClient = client
    }

    fun startListening(onWakeWord: () -> Unit) {
        Log.d("VoicePipeline", "Wake word detection started")
        this.wakeWordCallback = onWakeWord
        wakeWordEngine.start()
    }
    
    private fun onDetected() {
        Log.d("VoicePipeline", "Wake word detected!")
        wakeWordCallback?.invoke()
    }
    
    fun speechToText(audio: Any): String {
        return "Call Dad" 
    }
    
    suspend fun textToSpeech(text: String) {
        if (text.isBlank()) return
        
        val client = mistralClient ?: run {
            Log.e("VoicePipeline", "MistralClient not set, cannot speak")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "tts_output.mp3")
                client.synthesizeSpeech(text) { channel ->
                    val inputStream = channel.toInputStream()
                    FileOutputStream(tempFile).use { output ->
                        inputStream.copyTo(output)
                    }
                }

                // Play and wait for completion
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val mediaPlayer = MediaPlayer()
                        mediaPlayer.setDataSource(tempFile.absolutePath)
                        
                        mediaPlayer.setOnCompletionListener {
                            Log.d("VoicePipeline", "Playback finished")
                            it.release()
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        
                        mediaPlayer.setOnErrorListener { mp, what, extra ->
                            Log.e("VoicePipeline", "MediaPlayer Error: $what, $extra")
                            mp.release()
                            if (continuation.isActive) continuation.resume(Unit)
                            true
                        }

                        mediaPlayer.prepare()
                        mediaPlayer.start()
                        
                        continuation.invokeOnCancellation {
                            try {
                                mediaPlayer.stop()
                                mediaPlayer.release()
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VoicePipeline", "Error playing TTS", e)
            }
        }
    }

    fun stop() {
        wakeWordEngine.stop()
    }
}
