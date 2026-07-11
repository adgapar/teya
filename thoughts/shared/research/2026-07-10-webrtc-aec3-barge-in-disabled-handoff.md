---
date: 2026-07-10T00:00:00Z
topic: "WebRTC AEC3 barge-in: Plan B implemented, live-tested, disabled pending real AEC tuning"
status: handoff
---

# Session Handoff: WebRTC AEC3 Barge-In — Implemented, Tested, Disabled

## Where things stand

**Plan A (native AEC3 module) and Plan B (VoicePipeline/HarnessService integration) are both fully
implemented and committed to `main`.** The feature works end-to-end in code, was live-tested on
device, and is currently **disabled via a kill-switch** because real-world echo suppression proved
insufficient. Teya's barge-in behavior today is unchanged from before either plan: gap-gated only
(listens between sentences, not mid-sentence).

Commits, in order (all on `main`):
- `f0bda45` … `7f6d8f9` — Plan A: vendor AEC3, build, JNI glue, on-device validation (~72dB
  suppression on synthetic tones). Plan doc: `thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md`.
- `aa5c8d1`, `18a53cd`, `dfdcad4`, `7ec4012` — Plan B phases 1–4: resampler, render wiring, capture
  wiring, gate/gap removal. Plan doc:
  `thoughts/shared/plans/2026-07-09-webrtc-aec3-voicepipeline-integration.md` (status: `paused`).
- `8aac930` — Live-debugging fallout: two real platform-AEC wiring bugs found and fixed (kept),
  diagnostic logging added, kill-switch flipped off, code comments cleaned of phase/plan narration,
  findings written up in `docs/roadmap.md`.

**Read `docs/roadmap.md`'s "Native WebRTC AEC3 module" entry (under ✅ Done) first** — it's the
durable, up-to-date summary of what's built, what's fixed, and why it's off. This handoff doc adds
session-specific context the roadmap entry doesn't need.

## What was found, in order

1. **Platform `AcousticEchoCanceler` conflict** — `WakeWordEngine`'s shared mic had Android's own
   AEC still enabled during playback. Harmless before Plan B (barge-in never listened during
   playback at all), but once continuous listening started, it crushed the captured signal to
   near-silence before `NativeAec3`/Silero ever saw it (raw pre-gain amplitude logged a hard
   `0/32767` for seconds at a time). Fixed: `VoicePipeline.startAecSession()`/`endAecSession()`
   toggle it via `WakeWordEngine.setPlatformAecEnabled()`.
2. **Mic-restart reset** — `WakeWordEngine.start()` re-creates the platform AEC (enabled=true,
   hardcoded) on every restart, and restarts happen repeatedly *within* one conversation (after
   every `listenForCommand` cycle). Fix #1 alone got silently undone within seconds. Fixed:
   `platformAecDesiredEnabled` persists the desired state across restarts.
3. **Real AEC3 suppression still insufficient** — with both bugs fixed, real signal reached Silero,
   but confidence still fired on Teya's own voice (up to 99%). Diagnostic logging (raw vs.
   AEC3-cleaned peak amplitude, same chunk) showed suppression ranging from negligible to *negative*
   (cleaned louder than raw in one window) — not a wiring problem, AEC3 genuinely isn't cancelling
   reliably here.
4. **Buffer-size experiment** — hypothesis: `analyzeRender` (fed at network-chunk-arrival time) was
   running up to ~0.5s ahead of actual speaker output (the TTS `AudioTrack` buffer was sized ~0.5s
   for streaming smoothness), likely exceeding AEC3's delay-correlation window. Shrank the buffer to
   Android's true minimum. Helped in one test window (36% reduction vs. ~0%), not consistently.

At that point, per the plan's own "stop and reassess" guidance, the user chose to flip the
kill-switch off rather than keep patching live.

## What's next (if picked back up)

Real fix is believed to be **render/capture time-alignment**, not another parameter tweak:
- Check whether `NativeAec3`'s JNI wrapper or `EchoCanceller3Config` exposes any explicit delay
  estimate/compensation input — current wrapper (`voice/aec/NativeAec3.kt`) exposes none.
- Consider restructuring *when* `analyzeRender` is called to track actual playback position more
  tightly than "about to write to AudioTrack" — Android's `AudioTrack` has no direct "this sample is
  playing now" callback, only buffer-position polling (`getPlaybackHeadPosition`), so this isn't a
  trivial change.
- `EchoCanceller3Config` defaults have never been retuned for this specific speaker/mic pair —
  explicitly out of scope for both plans so far.
- Plan A's ~72dB synthetic number was almost certainly measured with render/capture frames fed in
  artificial lockstep (a controlled test harness), which doesn't exercise real timing drift at all —
  worth re-reading Plan A's Phase 4 test code before assuming that number means anything about real
  achievable suppression here.

To re-enable and test: flip `VoicePipeline.AEC3_BARGE_IN_ENABLED` back to `true`, rebuild, install.
The diagnostic logging (`Barge-in: peak VAD confidence...` / `speech detected (...rawPeak=...,
cleanedPeak(pre-gain)=...)"`) is still in place and will immediately show whether a fix actually
improved suppression — don't rely on subjective "felt better," check the numbers.

## Working notes for whoever picks this up

- Wireless adb on this device drops frequently mid-session — if `adb devices` shows nothing,
  the fastest recovery is usually re-toggling **Wireless debugging** off/on on the phone and asking
  for the freshly-shown IP:port (it rotates), or a fresh pairing via "Pair device with pairing code"
  if a plain reconnect keeps getting refused.
- **`./gradlew connectedAndroidTest --offline` uninstalls the app afterward** as standard Gradle
  behavior (cleans up the test-under-app it installed) — if you run it after `installDebug` in the
  same verification pass, the app will be gone again immediately after. Reinstall with plain
  `installDebug` afterward if you need the app present for manual testing.
- This device's actual page size is 4KB — the "isn't 16 KB compatible" install warning
  (`libteya_aec3.so`, `libonnxruntime.so`, `libtensorflowlite_jni.so`) is a Play Store compliance
  nag, harmless here since Teya is sideloaded. Logged as a backlog item in `docs/roadmap.md`, not
  urgent.
- Repo convention: solo project, commit direct to `main`, no branches/PRs, but still split into
  revertible, well-described commits.
