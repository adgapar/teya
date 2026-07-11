---
date: 2026-07-11T00:00:00Z
topic: "WebView/Chromium AEC integration for continuous barge-in (replacing NativeAec3)"
tags: [voice, barge-in, aec, webview, chromium, webrtc, voicepipeline, harnessservice]
status: ready-to-implement
---

# WebView/Chromium AEC Integration for Barge-In

## Why this plan exists

Two prior efforts tried to give Teya **continuous mid-sentence barge-in** (interrupt her while
she's actively talking, not just in the ~350ms gaps between sentences):

1. **Native `NativeAec3`** (vendored WebRTC AEC3 as a JNI module — see
   `thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md` and
   `2026-07-09-webrtc-aec3-voicepipeline-integration.md`). Built, wired in, real bugs found and
   fixed (kept), but never achieved real suppression on this device — traced to two root causes:
   render/capture timing drift (partially fixed) and a signal-energy convergence gate AEC3's own
   filter-confidence check never crosses on this device's quiet self-echo (**unfixed, load-bearing
   blocker**). Full trail: `docs/experiments.md`'s "make interruption work well" section.
2. **LiveKit / Pipecat evaluated as replacements** — both decompiled at the bytecode/source level
   (not just docs). Both require a real second WebRTC endpoint (a "bot" publishing TTS as a genuine
   remote track) to get AEC a render reference at all — no synthetic-PCM-injection API exists in
   `JavaAudioDeviceModule`. That's not incidental overhead, it's structural to how their local AEC
   is wired. Rejected: worse infra cost than our own approach, same underlying wall.

**This plan is the third attempt, and the first with real positive evidence behind it.** A local,
no-network WebView spike (`experiments/AecWebExperimentActivity` — currently in the repo as a
throwaway test harness, not production code) measured Chromium's own
`getUserMedia({echoCancellation:true})` suppressing a self-generated tone by **~36-41dB across two
independent confirmation runs**, vs. `NativeAec3`'s measured **~0dB** on this same physical device.
Android's WebView *is* Chromium — updated centrally by Google via Play Store, independent of OEM —
so this reuses a full-duplex, cross-device-tested audio pipeline instead of one we tune ourselves.
Full experiment log with exact numbers: `docs/experiments.md`.

**What this plan is NOT**: a guaranteed win. The confirmed result is a synthetic tone in an
isolated, one-shot WebView activity with no conversation happening. Real TTS speech has different
spectral characteristics than a pure tone, and — critically — this plan requires a **persistent**
WebView integrated into the live voice loop, which is a materially different (and harder)
engineering problem than the one-shot spike that produced the good numbers. Treat every phase below
as something to validate, not just implement.

## Read first

- `docs/experiments.md` — the full experiment trail (LiveKit/Pipecat rejection reasoning, AEC3 root
  causes, the WebView spike's exact numbers and the false-negative-then-confirmation story).
- `docs/roadmap.md` — "Make interruption work well" entry (goal-level status).
- `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt` — everything below references this
  file's current shape (as of 2026-07-11): `startAecSession`/`endAecSession` (session lifecycle),
  `streamToSpeaker` (render path, `feedPlayedRenderFrames`'s playback-position pacing), on the
  capture side `forwardArmedChunk`/`cleanCaptureChunk` (gap-gated barge-in + the `bargeInAudioBuffer`
  pre-interrupt buffer added 2026-07-11 — see `consumeBargeInAudio`), `setBargeInArmed` (per-turn
  `SileroVad` lifecycle), `listenForCommand`/`recordWithVad` (`prefixAudio` param).
- `app/src/main/kotlin/com/teya/agent/harness/HarnessService.kt` — `onBargeIn`, `runConversation`,
  `respond` (the per-sentence speaker loop + `BARGE_IN_GAP_MS` gap).
- `app/src/main/kotlin/com/teya/agent/experiments/AecWebExperimentActivity.kt` +
  `app/src/main/assets/aec_experiment.html` — the spike this plan builds on. Read these fully before
  starting; Phase 1 below reuses their WebView setup (permission grant, `MODIFY_AUDIO_SETTINGS`) but
  the activity itself is throwaway (one-shot, no persistence, no UI).

## The one architectural risk that could invalidate this whole plan — investigate FIRST

**Does a WebView keep running `getUserMedia`/Web Audio when its hosting Activity isn't in the
foreground, or when the screen is off?** Teya is an always-on ambient assistant — `HarnessService`
is a foreground *service* that must keep the wake-word loop and (per this plan) the AEC pipeline
alive regardless of whether any Activity is visible (see `[[foreground-and-app-handoff]]`/
`ARCHITECTURE.md`: "Android won't let a backgrounded Teya resurface" is already a known constraint
this app works around). WebView instances are conventionally tied to an Activity's window; running
one invisibly from a Service, or keeping it alive across the Activity going to the background, is
not a standard, well-documented pattern and may not work reliably across Android versions —
`getUserMedia` specifically may be throttled or suspended when the page isn't visible (this is
common browser-side power-saving behavior, and WebView inherits a lot of it).

**Do this before any other phase**: build the smallest possible test — a persistent (not one-shot)
WebView running `getUserMedia` continuously, then background the app (press home, lock the screen,
wait 30s+) and confirm via logcat/JS console whether audio capture is still delivering real samples
(not just that the page hasn't crashed). If this fails, the whole plan needs a different shape
(e.g., a foreground-service-owned invisible window via `WindowManager.addView` with a
`TYPE_APPLICATION_OVERLAY`-style layout params trick, or accepting the constraint that AEC-covered
barge-in only works while the app is actually on-screen).

## Architecture overview

Current render/capture split (`VoicePipeline.kt`):
- **Render**: `streamToSpeaker` writes Voxtral TTS PCM to a native `AudioTrack`; `analyzeRender` is
  fed in step, paced to `AudioTrack.playbackHeadPosition`.
- **Capture**: `WakeWordEngine`'s single shared `AudioRecord` (Android can't run two concurrently)
  delivers raw chunks; `forwardArmedChunk` runs them through `NativeAec3.processCapture`, then
  `SileroVad`, gated by `currentTrack != null` (the gap-gating self-echo defense).

Target shape with this plan: replace **both halves** of this with a persistent WebView page that
owns TTS playback (via Web Audio) and mic capture (`getUserMedia({echoCancellation:true})`) in the
same page context — the thing that makes Chromium's AEC work at all (real mic + real speaker in one
tab, no synthetic-render-injection problem). Kotlin keeps everything else unchanged: Mistral
network calls (STT/LLM/TTS), wake-word detection (`WakeWordEngine`'s own `AudioRecord` — separate
concern, not competing for the mic once TTS+barge-in capture both live in the WebView instead),
`SileroVad` (still runs in Kotlin, just fed WebView-cleaned audio instead of `NativeAec3`-cleaned
audio), and the whole `HarnessService` conversation loop.

```
Mistral TTS PCM ──▶ [JS bridge: push PCM] ──▶ WebView page: Web Audio playback
                                                      │ (same-page correlation)
                                                      ▼
                                          getUserMedia(echoCancellation:true)
                                                      │
                                          [JS bridge: cleaned PCM back] 
                                                      ▼
                                    Kotlin: SileroVad (existing, unchanged) ──▶ onBargeIn()
```

## Phases

### Phase 0 — De-risk background execution (see risk section above) — ✅ DONE 2026-07-11

Standalone spike, not integrated with anything. Success criterion: `getUserMedia` delivers real
(non-zero, changing) samples for at least 60 continuous seconds with the screen off / app
backgrounded. If this fails, stop and reassess the whole plan's shape before continuing.

**Result: passed, decisively.** `experiments/AecBackgroundExperimentActivity` +
`assets/aec_background_experiment.html` (a persistent, non-one-shot WebView running
`getUserMedia({echoCancellation:true})` alongside a continuous quiet Web Audio tone) ran
uninterrupted through two independent backgrounding scenarios on the real device:
- Screen dozed off (unintentionally, while the activity was still topmost) — **~12 continuous
  minutes**, `audioCtx.state` never left `running`, `samplesThisTick` never hit 0 across 800+
  per-second log ticks.
- Explicit `KEYCODE_HOME` backgrounding (launcher resumed, `mFocusedApp` confirmed `null` for our
  activity) — **~50s**, same result: no gaps, no suspension.

Flat `peak=rms=0.00000` readings during quiet/backgrounded stretches were verified to be real
silence, not a dead pipeline — asking the user to make noise near the device mid-run produced a
clean non-zero spike (`peak=0.24238`) exactly when expected. Full log excerpt in
`docs/experiments.md`'s 2026-07-11 Phase 0 row.

**Follow-up caveat, closed the same day:** the above only proved the Activity-hosted case. The
harder production shape — a WebView kept alive with **no Activity ever in the foreground at all** —
was tested immediately after via `experiments/AecServiceHostedExperimentService`: a 1x1 invisible
WebView added with `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` from a plain `Service` (needs
`SYSTEM_ALERT_WINDOW`, an app-ops permission grantable non-interactively via
`adb shell appops set com.teya.agent SYSTEM_ALERT_WINDOW allow`, or a one-time Settings toggle in
production). Result: same as above — `audioCtx.state` stayed `running`, capture never gapped,
across ~103+ seconds spanning both home-backgrounded and screen-locked/dozing windows, with
`mFocusedApp` confirmed to be the device launcher throughout (not this app at all). **Both halves of
Phase 0 are now cleared.** Phase 1 below should host its WebView via this `WindowManager` overlay
mechanism, not an Activity — see `AecServiceHostedExperimentService` for the pattern (add/remove
view, overlay `LayoutParams`, permission check/redirect). Full log excerpts: `docs/experiments.md`.

### Phase 1 — Persistent WebView host + bidirectional bridge scaffolding — ✅ DONE 2026-07-11

Build a long-lived (session-scoped, not one-shot) WebView host analogous to `NativeAec3`'s
lifecycle: construct once in `startAecSession()`, tear down in `endAecSession()`. Host it via
`WindowManager.addView(TYPE_APPLICATION_OVERLAY)` (confirmed working from a Service with zero
Activity involved — see Phase 0 above and `AecServiceHostedExperimentService`), not an Activity —
`VoicePipeline`'s `context` is the Service's context, and this needs to work regardless of whether
any Activity is ever on screen. Requires `SYSTEM_ALERT_WINDOW`; check
`Settings.canDrawOverlays(context)` at startup and degrade gracefully (fall back to `NativeAec3` or
gap-gated, same kill-switch pattern as `AEC3_BARGE_IN_ENABLED`) if not granted, rather than crashing
session start. No real audio yet — just prove:
- A JS interface method Kotlin can call to push data into the page (mirrors
  `AecWebExperimentActivity`'s `webChromeClient.onPermissionRequest` grant pattern, but needs a
  **push**, not just a **pull** — `evaluateJavascript` from Kotlin, or a bridge object with a method
  the JS side polls/awaits).
- A JS interface Kotlin exposes that the page can call to send data back (mirrors the existing
  `JsLogBridge` pattern in `AecWebExperimentActivity.kt`, but carrying real payloads, not log
  strings).
- Round-trip latency measurement for both directions — this determines the viable chunk size for
  phases 2-3 below.

**Result: done.** `voice/aec/WebViewAecHost.kt` implements exactly this — session-scoped
construct/teardown in `VoicePipeline.startAecSession()`/`endAecSession()`, gated by a new
`WEBVIEW_AEC_HOST_ENABLED` kill-switch (default `false`, same pattern as `AEC3_BARGE_IN_ENABLED` —
built alongside `NativeAec3`, not replacing it yet). Push is `evaluateJavascript` calling a JS-side
`onPing(id)`; pull is `assets/aec_bridge.html` calling back into an `AecBridge` JS interface's
`onPong(id)`. `Settings.canDrawOverlays` is checked in `start()`; missing permission or a failed
overlay add degrades to "no host" rather than crashing.

Flipped the flag to `true` and drove a real live conversation on-device (not just an isolated
spike) to validate it. First run surfaced a genuine bug: `loadUrl()` is fire-and-forget, so the very
first `evaluateJavascript` ping raced the page load and always lost (timed out with no response,
every time). Fixed by gating readiness on `WebViewClient.onPageFinished`. Re-ran: **9ms round-trip
latency**, measured inside a real session, with zero disruption to the rest of the turn (STT/LLM/TTS
all proceeded normally). Flag reverted to `false` afterward — no shipped behavior change. Full log
excerpts: `docs/experiments.md`.

9ms leaves generous headroom for Phase 2/3's real audio chunk cadence (at 16kHz, even a 10ms frame
is far above this latency floor) — no reason to widen chunk size for bridge-latency reasons alone.

### Phase 2 — Render path: TTS audio into the WebView — ✅ DONE 2026-07-11

Replace (or run alongside, behind a flag) `streamToSpeaker`'s `AudioTrack` writes with pushing the
same PCM chunks (currently 24kHz float32 from `client.streamSpeechPcm`) into the WebView page via
the Phase 1 bridge, played through Web Audio (`AudioBufferSourceNode`, scheduled to avoid gaps/
glitches between chunks — this is the JS-side equivalent of `streamToSpeaker`'s streaming
`AudioTrack.write` loop, and needs its own care around buffering/underrun, same class of problem).
Needs a JS-side equivalent of `playbackHeadPosition` polling (`streamToSpeaker`'s drain-loop) so
Kotlin can know when a sentence has actually finished playing — e.g. schedule each
`AudioBufferSourceNode` and use its `onended` callback, bridged back.

**Test in isolation**: does TTS still sound correct (no glitches, no added latency perceptible vs.
today) with render going through this path, before touching capture at all.

**Result: done.** `streamToSpeaker` is now a small dispatcher choosing between the original
(renamed) `streamToSpeakerViaAudioTrack` and a new `streamToSpeakerViaWebView`, gated by a
`WEBVIEW_RENDER_ENABLED` kill-switch (default `false`, requires `WEBVIEW_AEC_HOST_ENABLED` too).
The WebView path pushes each PCM chunk via `WebViewAecHost.pushRenderChunk` — no int16 conversion
needed (Web Audio wants float32 directly) and no explicit AEC3-style render-feed bookkeeping
(Chromium's own `getUserMedia` doesn't need one). `assets/aec_bridge.html`'s scheduler uses a
`nextStartTime` clock (clamped to `audioCtx.currentTime`) instead of `onended` bridging — simpler,
and matches the plan's "poll the drain position" framing more directly:
`getPlaybackRemainingMs()` is polled from `WebViewAecHost.awaitRenderPlaybackDone()` the same way
`streamToSpeakerViaAudioTrack` polls `playbackHeadPosition`.

One real gap found and closed before testing, not after: this path has no `AudioTrack`/
`MediaPlayer` for `forwardArmedChunk`'s self-echo gate or `interrupt()` to key off, so a new
`webViewRenderActive` flag fills that role — gates unconditionally (no capture-side echo
cancellation exists yet, that's Phase 3) and `interrupt()` now also calls
`WebViewAecHost.stopPlayback()` (which calls the page's `stopAllPlayback()`) to actually cut the
audio, not just flip a flag with no effect.

Live-tested with both flags on: a short "Yes?" prompt (user confirmed hearing it clearly) and a
real follow-up ("a short fact") producing a 9.76s / 234,240-sample multi-sentence response, both
played entirely through Web Audio. User confirmed **no glitches, pops, or added latency** vs. the
`AudioTrack` path. Both flags reverted to `false` afterward. Full log: `docs/experiments.md`.

### Phase 3 — Capture path: cleaned audio back to Kotlin

`getUserMedia({echoCancellation:true})` (proven to suppress ~36-41dB in the spike) captures mic
audio in the same page; bridge the cleaned PCM back to Kotlin at whatever chunk size Phase 1's
latency measurement supports. Feed it into the **existing** `SileroVad` pipeline exactly where
`NativeAec3`-cleaned audio used to go (`forwardArmedChunk`/`cleanCaptureChunk` in `VoicePipeline.kt`)
— reuse `bargeInAudioBuffer`/`consumeBargeInAudio` as-is, since that logic is about *what happens
after* cleaned audio exists, independent of which AEC produced it.

**Open question to resolve here, not assume**: does `getUserMedia` on this device conflict with
`WakeWordEngine`'s own concurrent `AudioRecord` (the documented "Android can't reliably open a
second concurrent `AudioRecord`" constraint that already shapes this codebase — see
`WakeWordEngine.kt`'s own doc comment)? If WebView's mic session and `WakeWordEngine`'s mic session
can't coexist, wake-word detection may need to pause while barge-in-armed (may already effectively
be true today, but confirm explicitly rather than assume).

### Phase 4 — Remove the gap, enable continuous listening

Once Phase 3 is validated with a live conversation (not just synthetic audio), remove the
`currentTrack != null && !aecActive` self-echo gate in `forwardArmedChunk` for the WebView-covered
path and `BARGE_IN_GAP_MS`'s forced delay in `HarnessService.respond()` — mirroring exactly what
`AEC3_BARGE_IN_ENABLED` was originally meant to do, just with a working AEC underneath this time.
Keep the gap-gated path as an automatic fallback (kill-switch, same pattern as
`AEC3_BARGE_IN_ENABLED`) in case the WebView pipeline fails to initialize for any reason — this
must never regress today's working gap-gated behavior.

### Phase 5 — Real-world tuning and validation

Live-test extensively: does real TTS speech (not a synthetic tone) get suppressed as well as the
spike's tone did? Does interrupt latency (time from starting to talk to Teya actually stopping)
feel at least as good as today's gap-gated + `textToSpeech` race-condition-fixed behavior
(2026-07-11)? Does the pre-interrupt audio buffer (`bargeInAudioBuffer`) still work correctly
against WebView-cleaned audio? Re-run `docs/experiments.md`-style measurement (peak/RMS comparison,
or reuse `EchoCanceller3`-style metrics conceptually) against real conversational audio, not just a
tone.

### Phase 6 — Cleanup (only after Phase 5 is solid in practice, not before)

Decide whether to retire `NativeAec3` entirely (JNI module, CMake build, vendored AEC3 source under
`app/src/main/cpp/third_party/webrtc/`) or keep it as a documented fallback. Don't do this
prematurely — recall this session's own earlier mistake of nearly abandoning `NativeAec3` before
the WebView approach was even confirmed working once.

## What we're explicitly NOT doing (yet)

- Not attempting to move STT/LLM/TTS network calls into the WebView's JS context — Kotlin keeps
  owning all Mistral API calls; only render/capture audio plumbing moves.
- Not porting Silero VAD to run inside the WebView (e.g. via ONNX Runtime Web) — keep it in Kotlin,
  fed by WebView-cleaned audio, to avoid maintaining the same model in two runtimes.
- Not implementing the "verify pre-interrupt transcript actually mentions Teya before committing"
  false-positive mitigation discussed alongside this plan — a separate, smaller piece of work,
  intentionally decoupled from this AEC migration.

## Testing / success criteria

- Phase 0's background-execution check passes (or the plan is revised if it doesn't).
- A live conversation with continuous listening enabled (Phase 4) survives at least 10 minutes of
  normal use without regressing gap-gated behavior when the WebView path is unavailable.
- Real interrupt latency (subjective + logged) is not worse than today's gap-gated result.
- `docs/experiments.md` gets a new entry recording whatever this plan actually finds — good or bad
  — following this session's established discipline of logging real results, not just intentions.
