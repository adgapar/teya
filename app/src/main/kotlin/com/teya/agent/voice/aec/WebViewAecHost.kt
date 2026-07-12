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
 * A persistent, session-scoped WebView host that gives Teya real Chromium-grade acoustic echo
 * cancellation for barge-in, hosted via `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` rather
 * than an Activity — this keeps it running with zero Activity ever in the foreground, matching
 * `HarnessService`'s own always-on foreground-service lifecycle.
 *
 * Owns both halves of the render/capture split: [pushRenderChunk]/[awaitRenderPlaybackDone]/
 * [stopPlayback] stream TTS PCM into the page's Web Audio scheduler for gapless playback, while
 * [startCapture]/[stopCapture] run `getUserMedia({echoCancellation:true})` and deliver each cleaned
 * chunk back via the constructor's `onCaptureChunk` callback. Because both playback and capture
 * happen inside the same page, Chromium's own echo cancellation has a real reference signal to
 * cancel against — no explicit render-frame feeding is needed the way a from-scratch AEC would
 * require. [measureRoundTripLatency] proves the underlying bidirectional bridge (`evaluateJavascript`
 * push, the [AecBridge] interface for the pull) independent of any real audio.
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
        // Render-path polling cadence/timeout — this poll round-trips through evaluateJavascript
        // instead of reading a local field (measured ~9ms one-way overhead for that round trip).
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

    /**
     * True if the overlay WebView is actually up and running this session. Callers use this to
     * decide whether to use this host at all or fall back to the no-AEC path — [start] never
     * throws on failure (missing permission, WindowManager error), it just leaves this `false`.
     */
    fun isActive(): Boolean = webView != null

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
                // grant whatever it asks for (mic capture) without a confirmation prompt.
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
     * within [PING_TIMEOUT_MS].
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
     * Pushes one streamed TTS PCM chunk (float32, range [-1, 1], as Mistral's own
     * `streamSpeechPcm` already yields) into the page's Web Audio scheduler
     * (`assets/aec_bridge.html`'s `pushRenderChunk`) as base64 — `evaluateJavascript` takes a JS
     * expression string, so this is the simplest encoding that survives string interpolation
     * without escaping concerns. No conversion needed on this side: unlike the AudioTrack
     * fallback path, Web Audio wants float32 directly, not int16.
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
     * Sets the linear gain applied to every render chunk from here on (`assets/aec_bridge.html`'s
     * `setRenderGain`) — the WebView path's equivalent of [VoicePipeline.attachLoudnessEnhancer] on
     * the AudioTrack fallback. Plain Web Audio GainNode, not a limiter, so callers should convert
     * from a dB value modest enough not to clip (see ConfigManager.ttsVolumeBoostDb).
     */
    suspend fun setRenderGain(linearGain: Double) = withContext(Dispatchers.Main) {
        webView?.evaluateJavascript("setRenderGain($linearGain)", null)
    }

    /**
     * Starts `getUserMedia({echoCancellation:true})` in the page (`assets/aec_bridge.html`'s
     * `startCapture`) at [sampleRate] — pass 16000 to match `VoicePipeline`'s
     * `SileroVad`/`WakeWordEngine` sample rate directly, no resampling needed on either side. Each
     * captured chunk arrives via [onCaptureChunk] (the constructor callback), decoded from base64
     * float32 to int16 in [AecBridge.onCaptureChunk] below — matches the same int16 PCM units
     * `WakeWordEngine`'s raw `AudioRecord` chunks already use.
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
         * One chunk of `getUserMedia`-cleaned mic audio, base64-encoded float32 (matches
         * [pushRenderChunk]'s encoding, just the reverse direction). Invoked on whatever thread the
         * WebView's JS engine calls this interface from — not the main thread, and not
         * `WakeWordEngine`'s capture thread either, so [onCaptureChunk]'s receiver
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
