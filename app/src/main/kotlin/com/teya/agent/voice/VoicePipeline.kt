package com.teya.agent.voice

import android.content.Context
import android.util.Log

class VoicePipeline(private val context: Context) {
    // Stubs for STT, TTS, and Wake Word
    
    fun startListening(onWakeWord: () -> Unit) {
        Log.d("VoicePipeline", "Wake word detection started")
        // Implementation with Porcupine/etc. goes here
    }
    
    fun speechToText(audio: Any): String {
        return "Call Dad" // Stubbed for now
    }
    
    fun textToSpeech(text: String) {
        Log.d("VoicePipeline", "Speaking: $text")
        // Implementation with ElevenLabs/etc. goes here
    }
}
