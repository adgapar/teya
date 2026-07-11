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
 * Phase 0 spike (see thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md) — tests the
 * one architectural risk that could invalidate the whole WebView-AEC plan: does a WebView's
 * `getUserMedia` keep delivering real samples when this Activity is backgrounded or the screen is
 * off? Deliberately does NOT override onPause/onResume to touch the WebView — the point is to
 * observe Android's default behavior when the app is backgrounded the normal way (home button,
 * screen lock), not to force the WebView to keep running.
 *
 * Loads `assets/aec_background_experiment.html`, which runs indefinitely (unlike
 * `AecWebExperimentActivity`'s one-shot tone test) logging capture/playback stats every second via
 * [JsLogBridge]. Test procedure: launch, confirm logs are flowing
 * (`adb logcat -s AecBackgroundExperiment`), then background the app / lock the screen for 60s+ and
 * check whether the per-second logs kept advancing the whole time.
 *
 * Launch manually: `adb shell am start -n com.teya.agent/.experiments.AecBackgroundExperimentActivity`
 */
class AecBackgroundExperimentActivity : Activity() {

    companion object {
        private const val TAG = "AecBackgroundExperiment"
        private const val MIC_PERMISSION_REQUEST = 1002
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
            webView.loadUrl("file:///android_asset/aec_background_experiment.html")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) {
            webView.loadUrl("file:///android_asset/aec_background_experiment.html")
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
