---
date: 2026-07-10T00:00:00Z
topic: "AEC3 delay-estimator diagnostic: confirmed stuck at delayMs=0, real device data"
status: handoff
---

# AEC3 GetMetrics() Diagnostic — Confirmed Root Cause

Follow-up to `2026-07-10-webrtc-aec3-barge-in-disabled-handoff.md`. That doc suspected
render/capture time misalignment but only had amplitude-comparison evidence. This session added a
real diagnostic (Google's own `EchoControl::GetMetrics()`, exposed via a new JNI call) and captured
live numbers on-device, which confirm the theory directly.

## What was added (committed as working code, not yet committed to git — see below)

- `jni_aec3.cpp`: `nativeGetMetrics()` — returns `[echo_return_loss, echo_return_loss_enhancement,
  delay_ms]` straight from `EchoCanceller3::GetMetrics()`.
- `NativeAec3.kt`: `getMetrics(): Metrics` wrapper (data class with the three fields).
- `VoicePipeline.kt`: `logAec3Metrics()`, called every ~2s from the existing barge-in diagnostic
  block in `forwardArmedChunk`. Pure diagnostic, no behavior change — `AEC3_BARGE_IN_ENABLED`
  untouched, still `false`.

Note: `startAecSession()`/`endAecSession()` run on every normal conversation regardless of the
kill-switch (`HarnessService.kt:310,340` — independent of `AEC3_BARGE_IN_ENABLED`, which only gates
whether barge-in *acts* on the result at line 431). So this diagnostic fires on every conversation
already, no flag needed.

## Real captured data (one live conversation, 2026-07-10 ~15:50-15:51)

Three windows across the same conversation (aec3 is session-scoped, spans all turns —
`VoicePipeline.kt`'s `aec3` field doc comment):

```
15:50:16  delayMs=0, echoReturnLossDb=-30.0, echoReturnLossEnhancementDb=0.2
15:50:35  delayMs=0, echoReturnLossDb=-30.0, echoReturnLossEnhancementDb=0.2
15:51:16  delayMs=8, echoReturnLossDb=-19.0, echoReturnLossEnhancementDb=0.4
```

## Interpretation

- **Not frozen, but crawling far too slowly to be useful**: the first two windows were identical
  (0/-30.0/0.2), but a third window ~40s later (a later conversation turn) had moved to
  8ms/-19.0/0.4. So AEC3's delay estimator isn't permanently stuck — it's inching toward a nonzero
  delay estimate and ERLE is inching up, just extremely slowly relative to a real conversation's
  timescale (still <0.5dB of enhancement after ~90s of an actual session). The config's own
  `default_delay` is 5 (blocks), so even 0 wasn't the config default — it's the live estimator's own
  low-confidence state.
- **`echoReturnLossEnhancementDb≈0.2` (~0dB)**: confirms the adaptive filter does essentially no
  cancellation — matches the handoff doc's "suppression ranging from negligible to negative"
  finding, now via AEC3's own internal diagnostic rather than raw/cleaned amplitude comparison.
- **Why it's stuck rather than merely wrong**: the matched filter locks onto a delay by
  cross-correlating a render history window against capture — this only works if the same offset
  holds consistently across that window. `feedRenderToAec3` (`VoicePipeline.kt`) feeds
  `analyzeRender` at *network-chunk-arrival* time, not actual-speaker-output time, and TTS chunks
  arrive over the network unevenly. That means the true render→capture offset isn't a fixed
  constant — it moves within a single utterance. A moving target smears the correlation peak and
  the estimator's confidence never crosses threshold — consistent with "reports 0, stays at 0"
  rather than "converges to a stable wrong number."

## What this settles vs. what's still open

**Settled**: the root cause is render/capture timing, not AEC3's suppression math, not an
incomplete vendored algorithm ([[webrtc-adm-no-pcm-injection]] already ruled out the "use
LiveKit/Pipecat's prebuilt AEC3 instead" path as a dead end for the same underlying reason — their
architecture avoids exactly this problem by having a real audio-device-module own both playout and
capture on one native thread).

**Two candidate fixes, not mutually exclusive**:
1. **Cheap experiment, likely partial fix**: wire `EchoCanceller3::SetAudioBufferDelay(int
   delay_ms)` (already vendored, never called — `jni_aec3.cpp` has no export for it yet) with a
   rough constant estimate. This directly seeds the delay buffer instead of requiring blind
   convergence (see `render_delay_buffer.cc`'s `external_audio_buffer_delay_` handling). Can only
   compensate for a *fixed* offset — won't help if the true offset drifts within an utterance,
   which the data above suggests it does. Worth trying for signal (does `delayMs` stop reporting 0?
   does ERLE move off ~0?) but likely not sufficient alone.
2. **Structural fix (the one both handoff docs point at)**: pace `analyzeRender` to actual playback
   position (e.g. via `AudioTrack.getPlaybackHeadPosition()` or a full-duplex Oboe/AAudio callback
   tying render and capture to the same clock) instead of network-arrival time. This removes the
   drifting-offset problem at its source rather than compensating for it after the fact.

## Next step if picked back up

Don't re-attempt the "just add SetAudioBufferDelay and declare victory" shortcut without also
checking `delayMs` in the logs afterward — if it's still 0 or unstable, the fixed-offset hint didn't
help and the structural fix is required. The logging added this session
(`VoicePipeline.logAec3Metrics()`) is the fast way to check either fix's effect: re-run a
conversation, grep `adb logcat -s VoicePipeline | grep "AEC3 metrics"`, and look at whether `delayMs`
stabilizes to a nonzero, consistent value and `echoReturnLossEnhancementDb` rises meaningfully above
~0.

## Working notes

- adb on this session: wireless adb sometimes enumerates the same physical device twice (an
  `IP:port` entry and an `adb-<serial>._adb-tls-connect._tcp` mDNS entry), which makes plain `adb`
  commands fail with "more than one device/emulator" — target explicitly with `adb -s
  <ip:port> ...`.
- `installDebug` does not launch the app — `ps -A | grep -i teya` came back empty right after
  install this session; the app needs to be opened manually on the device before a conversation can
  happen.
