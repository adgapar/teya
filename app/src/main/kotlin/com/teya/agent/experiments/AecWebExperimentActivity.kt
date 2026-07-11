package com.teya.agent.experiments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Standalone spike (see docs/experiments.md) — not part of the app's normal flow. Tests whether
 * Chromium's own `getUserMedia({echoCancellation:true})`, running inside Android's own WebView,
 * suppresses a self-generated tone picked up by this device's mic better than our native
 * `NativeAec3` path did on real audio (see the 2026-07-10/11 experiment log entries).
 *
 * Loads `assets/aec_experiment.html`, which plays a tone and captures the mic once with
 * echoCancellation on and once off, logging peak/RMS for both via [JsLogBridge] so the two numbers
 * can be compared directly (`adb logcat -s AecWebExperiment`).
 *
 * Launch manually: `adb shell am start -n com.teya.agent/.experiments.AecWebExperimentActivity`
 */
class AecWebExperimentActivity : Activity() {

    companion object {
        private const val TAG = "AecWebExperiment"
        private const val MIC_PERMISSION_REQUEST = 1001
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(JsLogBridge(), "Android")
            webChromeClient = object : WebChromeClient() {
                // Local experiment page only (file:///android_asset/...), never a real site —
                // safe to grant whatever it asks for (mic capture) without a confirmation prompt.
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { request.grant(request.resources) }
                }
            }
        }
        setContentView(webView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
        } else {
            webView.loadUrl("file:///android_asset/aec_experiment.html")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) {
            webView.loadUrl("file:///android_asset/aec_experiment.html")
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private class JsLogBridge {
        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, message)
        }
    }
}
