---
date: 2026-07-11T00:00:00Z
topic: "WebView/Chromium AEC shipped as default barge-in; NativeAec3 retired; session handoff"
tags: [voice, barge-in, aec, webview, chromium, handoff]
---

# Handoff: WebView AEC shipped, NativeAec3 retired

## What happened this session

Executed the full phased plan in `thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md`
(Phases 0-6), then — on the user's explicit direction, given how decisively the WebView path
outperformed `NativeAec3` in live testing — retired `NativeAec3` entirely and made the WebView AEC
path the sole, unconditional default. Continuous mid-sentence barge-in (interrupting Teya while
she's actively speaking, not just in the gaps between sentences) now **ships as the real, default
behavior**, confirmed working live across many real conversation turns.

14 commits on `main`, working tree clean, `./gradlew assembleDebug --offline` and
`installDebug --offline` both succeed. Full experiment trail: `docs/experiments.md`'s "make
interruption (barge-in) work well" section (read newest-first).

## Current architecture (post-cleanup)

- `voice/aec/WebViewAecHost.kt` — session-scoped WebView, hosted via
  `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` (not an Activity — runs with `HarnessService`
  regardless of what's on screen). Owns both render (`pushRenderChunk`/`awaitRenderPlaybackDone`/
  `stopPlayback`, Web Audio playback) and capture (`startCapture`/`stopCapture`, Chromium's own
  `getUserMedia({echoCancellation:true})`). `isActive()` tells callers whether it's really up.
- `assets/aec_bridge.html` — the WebView-side JS: Web Audio scheduler for render, `getUserMedia`
  capture at 512-sample (32ms) chunks (2048/128ms was tried first and found too coarse to reliably
  land within a gap window — see docs/experiments.md's Phase 3 entry).
- `voice/VoicePipeline.kt` — `startAecSession()`/`endAecSession()` construct/tear down the host
  unconditionally (no flags anymore). `forwardWebViewCapturedChunk` is the primary barge-in capture
  path when the host is active; `forwardArmedChunk` is now a plain gap-gated fallback used only when
  it isn't (missing `SYSTEM_ALERT_WINDOW`, or the host failed to start for any reason).
  `streamToSpeakerViaWebView`/`streamToSpeakerViaAudioTrack` are the equivalent render-side pair.
  `isContinuousBargeInActive()` is the public accessor `HarnessService` uses to decide whether its
  inter-sentence gap is still needed.
- `NativeAec3`, `Resampler`, the entire vendored WebRTC AEC3 native module
  (`app/src/main/cpp/` — `jni_aec3.cpp`, `webrtc_shim/`, `third_party/webrtc/`), the NDK/CMake build
  config, and the dev-only spike files (`experiments/` Activities/Service, `aec_experiment.html` and
  siblings) are all **deleted**. No native code left in the app at all.

## The one real bug found and fixed this session (read this before touching AEC code again)

Deleting `NativeAec3`'s construction block silently also deleted a
`wakeWordEngine.setPlatformAecEnabled(false)` call that had been running as an incidental side
effect on every single test all session. Its real purpose has nothing to do with AEC3 — it disables
this device's own unreliable platform `AcousticEchoCanceler` (on `WakeWordEngine`'s own concurrent
`AudioRecord` session), which was found to interfere with Chromium's separate `getUserMedia` echo
cancellation (most likely hardware/DSP-level interaction on this device's chipset — Samsung A34,
MediaTek). The first live test after the cleanup produced a genuine self-interrupt false positive
(Teya's own voice triggering barge-in) that hadn't shown up in any earlier test. Restored explicitly
in `VoicePipeline.startAecSession()`/`endAecSession()`, independent of `NativeAec3`; re-tested live
and confirmed working correctly again (user-confirmed).

**Lesson for future cleanups**: deleting a code block can silently drop side effects unrelated to
that block's stated purpose. When retiring code, grep for what else touches the same shared state
(here: `WakeWordEngine`'s effects) before assuming a deletion is behaviorally inert.

## Constraints now baked into the architecture

- **`minSdk` 26 is now a hard floor, not headroom** — `WebViewAecHost` hosts its overlay via
  `TYPE_APPLICATION_OVERLAY`, introduced in API 26, no fallback path coded for older devices.
- **`SYSTEM_ALERT_WINDOW`** needs a manual one-time grant via Settings (not a runtime dialog like
  `RECORD_AUDIO`) — until granted, `WebViewAecHost.isActive()` reports `false` and everything falls
  back automatically to gap-gated, no-AEC behavior. No crash, but silently degraded — worth a real
  user-facing check (e.g. a startup diagnostic or admin-screen status indicator) if this becomes a
  recurring support issue.
- **Validated on one device only** (Samsung A34, MediaTek chipset). The platform-AEC interaction bug
  above is plausibly chipset-specific — a different phone model could behave differently and need
  its own tuning pass. Documented in `CLAUDE.md`'s Gotchas and `README.md` (buyer-facing: Android
  8.0+ requirement, one-time permission step).

## What's next (not started, no code written for these)

1. **Phase 5 from the original plan — broader real-world validation.** Everything so far is strong
   live evidence (multiple real conversation turns, a 26-second uninterrupted story with zero false
   positives, several genuine mid-sentence interrupts all firing correctly) but still from one
   session, one room, one person. More exposure over time (different rooms/noise levels/longer
   sessions, other family members' voices) would build more confidence before calling this fully
   proven rather than "very promising."
2. **New backlog item (just added, `docs/roadmap.md`'s Backlog section): admin-configurable
   barge-in/wake-word tuning.** Currently hardcoded knobs that are real candidates for exposing
   through `SettingsActivity`, backed by `ConfigManager` (same `EncryptedSharedPreferences` store as
   the Mistral API key), with today's values as defaults:
   - `VoicePipeline`: Silero VAD `threshold` (0.7), `speechDurationMs` (50), `silenceDurationMs`
     (300), `BARGE_IN_GAIN` (6.0)
   - `HarnessService`: `BARGE_IN_GAP_MS` (350ms — only matters on the fallback path now)
   - `WakeWordEngine`: `THRESHOLD` (0.2), `INPUT_GAIN` (6.0), `PATIENCE` (1)

   Rationale: today's tuning required rebuild+install cycles for every value change (this session
   did several). An admin screen would let a future tuning pass — Phase 5 above, or retuning wake
   word for a different device — happen without a build.
3. Two older, still-open items noted in `docs/experiments.md`'s "Currently testing / next up" from
   earlier in the week, unrelated to this session's work: a UI-state bug (stays in "speaking" after
   an interrupt sometimes) and a swallowed-STT-network-error bug in `listenForCommand`. Neither was
   touched this session; still open.

## If you're picking this up fresh

Read `docs/experiments.md`'s barge-in section top-to-bottom (it's newest-first, so start there for
current status, then read down for the full trail if you want the history). The plan doc
(`thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md`) has every phase's exact result
inline — useful if you need to understand *why* a particular piece of the current code looks the way
it does. `git log --oneline` from this session's first commit
(`1650666 Fix barge-in interrupt race...`) through the last
(`6e5beae Add admin-configurable tuning to backlog...`) is 14 commits, each scoped to one
phase/finding — good granularity if you need to `git show` any specific step.
