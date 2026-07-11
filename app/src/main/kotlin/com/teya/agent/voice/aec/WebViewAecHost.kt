package com.teya.agent.voice.aec

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Phase 1 of the WebView/Chromium AEC plan
 * (thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md): a persistent, session-scoped
 * WebView host, analogous to [NativeAec3]'s lifecycle (construct once, tear down once), hosted via
 * `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` rather than an Activity — confirmed working
 * with zero Activity ever in the foreground by the Phase 0 spike
 * (`experiments/AecServiceHostedExperimentService`, see docs/experiments.md's 2026-07-11 entries).
 *
 * Phase 1 proved the bidirectional bridge: [measureRoundTripLatency] pushes into the page
 * (Kotlin->JS, via `evaluateJavascript`) and awaits the page pulling back (JS->Kotlin, via the
 * [AecBridge] interface `assets/aec_bridge.html` calls) — confirmed 9ms round trip live (see
 * docs/experiments.md), plenty of headroom for real audio. Phase 2 built the render path on top of
 * that same channel: [pushRenderChunk] streams real TTS PCM into the page's Web Audio scheduler,
 * [awaitRenderPlaybackDone] is the JS-side equivalent of `AudioTrack.playbackHeadPosition`'s
 * drain-loop poll, and [stopPlayback] is this path's `interrupt()` equivalent. Phase 3 adds the
 * capture path: [startCapture] starts `getUserMedia({echoCancellation:true})` in the page — the
 * ~36-41dB suppression confirmed by the original tone spike — and [onCaptureChunk] (via the
 * constructor callback) delivers each cleaned chunk back to Kotlin's `SileroVad` pipeline.
 *
 * All WebView calls must happen on the main thread (this is Android's own requirement, not just a
 * style choice) — every public suspend function here hops to [Dispatchers.Main] internally, so
 * callers (VoicePipeline's coroutine-based session lifecycle) don't need to care.
 */
class WebViewAecHost(
    private val context: Context,
    private val onCaptureChunk: ((ShortArray) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "WebViewAecHost"
        private const val PING_TIMEOUT_MS = 2000L
        private const val PAGE_LOAD_TIMEOUT_MS = 3000L
        // Phase 2 render-path polling cadence/timeout — mirrors streamToSpeaker's own
        // AudioTrack.playbackHeadPosition drain-loop poll (VoicePipeline.AEC3_RENDER_POLL_MS is
        // 10ms; 20ms here since this poll round-trips through evaluateJavascript instead of reading
        // a local field, and Phase 1 measured ~9ms one-way overhead for that round trip).
        private const val RENDER_POLL_MS = 20L
        private const val RENDER_DONE_THRESHOLD_MS = 5.0
        private const val RENDER_MAX_WAIT_MS = 15000L
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

    /**
     * Phase 2 render path: pushes one streamed TTS PCM chunk (float32, range [-1, 1], as Mistral's
     * own `streamSpeechPcm` already yields) into the page's Web Audio scheduler
     * (`assets/aec_bridge.html`'s `pushRenderChunk`) as base64 — `evaluateJavascript` takes a JS
     * expression string, so this is the simplest encoding that survives string interpolation
     * without escaping concerns. No conversion needed on this side: unlike the AudioTrack path,
     * Web Audio wants float32 directly, not int16.
     */
    suspend fun pushRenderChunk(floats: FloatArray, sampleRate: Int) = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext
        val bytes = ByteArray(floats.size * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floats)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        wv.evaluateJavascript("pushRenderChunk('$b64', $sampleRate)", null)
    }

    /**
     * Polls the page's scheduled-playback clock (`getPlaybackRemainingMs`) until everything pushed
     * via [pushRenderChunk] has actually played — the JS-side equivalent of streamToSpeaker's
     * `AudioTrack.playbackHeadPosition` drain-loop. Returns `false` on timeout (mirrors that
     * drain-loop's own no-progress bail) rather than hanging forever.
     */
    suspend fun awaitRenderPlaybackDone(): Boolean {
        if (webView == null) return false
        val deadline = System.currentTimeMillis() + RENDER_MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = evalJsNumber("getPlaybackRemainingMs()")
            if (remainingMs == null || remainingMs <= RENDER_DONE_THRESHOLD_MS) return true
            delay(RENDER_POLL_MS)
        }
        return false
    }

    /** Immediately stops all scheduled/playing render audio — the WebView path's [interrupt] equivalent. */
    suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        webView?.evaluateJavascript("stopAllPlayback()", null)
    }

    /**
     * Phase 3 capture path: starts `getUserMedia({echoCancellation:true})` in the page
     * (`assets/aec_bridge.html`'s `startCapture`) at [sampleRate] — pass 16000 to match
     * `VoicePipeline`'s `SileroVad`/`WakeWordEngine` sample rate directly, no resampling needed on
     * either side. Each captured chunk arrives via [onCaptureChunk] (the constructor callback),
     * decoded from base64 float32 to int16 in [AecBridge.onCaptureChunk] below — matches the same
     * int16 PCM units `WakeWordEngine`'s raw `AudioRecord` chunks already use.
     */
    suspend fun startCapture(sampleRate: Int) = withContext(Dispatchers.Main) {
        webView?.evaluateJavascript("startCapture($sampleRate)", null)
    }

    /** Stops capture and releases the mic track. Safe to call even if [startCapture] was never called. */
    suspend fun stopCapture() = withContext(Dispatchers.Main) {
        webView?.evaluateJavascript("stopCapture()", null)
    }

    /** Runs [expr] on the main thread and parses its JSON result as a number, or `null` on failure. */
    private suspend fun evalJsNumber(expr: String): Double? {
        val wv = webView ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(expr) { result ->
                    if (cont.isActive) cont.resume(result?.toDoubleOrNull())
                }
            }
        }
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

        /**
         * Phase 3 capture path: one chunk of `getUserMedia`-cleaned mic audio, base64-encoded
         * float32 (matches [pushRenderChunk]'s encoding, just the reverse direction). Invoked on
         * whatever thread the WebView's JS engine calls this interface from — not the main thread,
         * and not [WakeWordEngine]'s capture thread either, so [onCaptureChunk]'s receiver
         * (`VoicePipeline`) must not assume a specific caller thread here.
         */
        @JavascriptInterface
        fun onCaptureChunk(base64: String) {
            val onChunk = onCaptureChunk ?: return
            try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                val shorts = ShortArray(floats.size) { i ->
                    val v = floats[i] * 32767f
                    when {
                        v >= 32767f -> Short.MAX_VALUE
                        v <= -32768f -> Short.MIN_VALUE
                        else -> v.toInt().toShort()
                    }
                }
                onChunk(shorts)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode capture chunk", e)
            }
        }
    }
}
