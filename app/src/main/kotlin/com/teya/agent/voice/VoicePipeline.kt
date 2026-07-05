package com.teya.agent.voice

import android.content.Context
import android.util.Log

class VoicePipeline(private val context: Context) {
    
    private val wakeWordEngine = WakeWordEngine(context) {
        onDetected()
    }
    
    private var wakeWordCallback: (() -> Unit)? = null

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
        return "Call Dad" // Stubbed for now
    }
    
    fun textToSpeech(text: String) {
        Log.d("VoicePipeline", "Speaking: $text")
        // Implementation with Mistral TTS goes here
    }

    fun stop() {
        wakeWordEngine.stop()
    }
}
