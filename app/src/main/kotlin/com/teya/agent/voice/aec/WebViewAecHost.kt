package com.teya.agent.voice.aec

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 of the WebView/Chromium AEC plan
 * (thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md): a persistent, session-scoped
 * WebView host, analogous to [NativeAec3]'s lifecycle (construct once, tear down once), hosted via
 * `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` rather than an Activity — confirmed working
 * with zero Activity ever in the foreground by the Phase 0 spike
 * (`experiments/AecServiceHostedExperimentService`, see docs/experiments.md's 2026-07-11 entries).
 *
 * No real render/capture audio flows through here yet (that's Phase 2/3) — this only proves the
 * bidirectional bridge: [measureRoundTripLatency] pushes into the page (Kotlin->JS, via
 * `evaluateJavascript`) and awaits the page pulling back (JS->Kotlin, via the [AecBridge] interface
 * `assets/aec_bridge.html` calls), so later phases know the viable chunk size/cadence for real
 * audio plumbing.
 *
 * All WebView calls must happen on the main thread (this is Android's own requirement, not just a
 * style choice) — [start]/[stop]/[measureRoundTripLatency] all hop to [Dispatchers.Main]
 * internally, so callers (VoicePipeline's coroutine-based session lifecycle) don't need to care.
 */
class WebViewAecHost(private val context: Context) {

    companion object {
        private const val TAG = "WebViewAecHost"
        private const val PING_TIMEOUT_MS = 2000L
        private const val PAGE_LOAD_TIMEOUT_MS = 3000L
    }

    private var webView: WebView? = null
    private var windowManager: WindowManager? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var nextPingId = 0L
    // loadUrl() is fire-and-forget — it returns long before the page (and its onPing function)
    // actually exists, so an evaluateJavascript call right after start() returns can silently hit a
    // blank page and never call back (exactly what happened in first real testing: the bridge
    // round-trip timed out every time because the ping raced page load and always lost). Resolved
    // by WebViewClient.onPageFinished below; start() awaits it before returning.
    private var pageLoaded = CompletableDeferred<Unit>()

    /** True if this device has granted "draw over other apps" — required to host the overlay. */
    fun canHost(): Boolean = Settings.canDrawOverlays(context)

    /** Adds the overlay WebView and loads the bridge page. Call once per session; idempotent. */
    suspend fun start() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        if (!canHost()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot host overlay WebView this session")
            return@withContext
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        pageLoaded = CompletableDeferred()
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(AecBridge(), "AecBridge")
            webChromeClient = object : WebChromeClient() {
                // Local bridge page only (file:///android_asset/...), never a real site — safe to
                // grant whatever it asks for (mic capture, needed from Phase 3 on) without a
                // confirmation prompt.
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    request.grant(request.resources)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded.complete(Unit)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(wv, params)
            webView = wv
            wv.loadUrl("file:///android_asset/aec_bridge.html")
            Log.d(TAG, "Overlay WebView added, awaiting page load")
            val loaded = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await(); true }
            Log.d(TAG, if (loaded == true) "Bridge page loaded" else "Bridge page did not finish loading within timeout")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay WebView", e)
        }
    }

    /** Removes and destroys the overlay WebView. Safe to call even if [start] was never called. */
    suspend fun stop() = withContext(Dispatchers.Main) {
        try {
            webView?.let { windowManager?.removeView(it) }
            webView?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down overlay WebView", e)
        }
        webView = null
        windowManager = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /**
     * Pushes a ping into the page and awaits it pulling back via [AecBridge.onPong], returning the
     * round-trip latency in ms — or `null` if the host isn't running or the page didn't respond
     * within [PING_TIMEOUT_MS]. This is Phase 1's whole point: measuring this determines the viable
     * chunk size/cadence for Phase 2/3's real audio plumbing over the same channel.
     */
    suspend fun measureRoundTripLatency(): Long? {
        val wv = webView ?: return null
        val id = (nextPingId++).toString()
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        val sentAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            wv.evaluateJavascript("onPing('$id')", null)
        }
        val completed = withTimeoutOrNull(PING_TIMEOUT_MS) { deferred.await(); true }
        pending.remove(id)
        return if (completed == true) System.currentTimeMillis() - sentAt else null
    }

    private inner class AecBridge {
        @JavascriptInterface
        fun onPong(id: String) {
            pending.remove(id)?.complete(Unit)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "[js] $message")
        }
    }
}
