package com.teya.agent.experiments

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Phase 0/1 prerequisite spike (see thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md):
 * the earlier `AecBackgroundExperimentActivity` result (getUserMedia survives screen-off/backgrounding)
 * only proved the Activity-hosted case. Real integration needs a WebView owned by `HarnessService` —
 * a plain Service, with NO Activity ever in the foreground. This tests whether that's even possible:
 * adds a 1x1 invisible WebView via `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` from a Service,
 * with no Activity ever started, and runs the same continuous getUserMedia capture test.
 *
 * Requires the user to grant "draw over other apps" once (Settings.canDrawOverlays) — this spike
 * redirects to that settings screen itself if not yet granted; check logcat after granting and
 * restarting the service.
 *
 * Launch manually: `adb shell am start-service -n com.teya.agent/.experiments.AecServiceHostedExperimentService`
 */
class AecServiceHostedExperimentService : Service() {

    companion object {
        private const val TAG = "AecServiceHostedExperiment"
    }

    private var webView: WebView? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Missing 'draw over other apps' permission — redirecting to settings. Grant it, then restart this service.")
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(settingsIntent)
            stopSelf()
            return START_NOT_STICKY
        }

        if (webView != null) {
            Log.d(TAG, "Already running.")
            return START_STICKY
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(JsLogBridge(), "Android")
            webChromeClient = object : WebChromeClient() {
                // Local experiment page only (file:///android_asset/...), never a real site —
                // safe to grant whatever it asks for (mic capture) without a confirmation prompt.
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }
        }
        webView = wv

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1, 1, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(wv, params)
            Log.d(TAG, "Overlay WebView added, no Activity involved. Loading experiment page.")
            wv.loadUrl("file:///android_asset/aec_background_experiment.html")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay WebView", e)
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            webView?.let { windowManager?.removeView(it) }
            webView?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down overlay WebView", e)
        }
        webView = null
        super.onDestroy()
    }

    private class JsLogBridge {
        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, message)
        }
    }
}
