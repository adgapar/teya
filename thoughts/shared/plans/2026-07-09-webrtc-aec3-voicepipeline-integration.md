---
date: 2026-07-09T00:00:00Z
topic: "WebRTC AEC3 VoicePipeline/HarnessService integration (Plan B: wire the validated native module in)"
tags: [voice, barge-in, aec3, webrtc, voicepipeline, harnessservice]
status: paused
last_updated: 2026-07-10T00:00:00Z
last_updated_by: phase-running (Phase 4)
---

**Status note (2026-07-10)**: Phases 1–4 implemented and committed. Live testing found AEC3's real
echo suppression insufficient (inconsistent, occasionally negative) — feature is shipped but
disabled via `VoicePipeline.AEC3_BARGE_IN_ENABLED = false`. Two real wiring bugs found along the
way are fixed and kept regardless. See `docs/roadmap.md`'s "Native WebRTC AEC3 module" entry for
the durable, up-to-date summary — this file remains as the detailed historical record (design
decisions, phase-by-phase changes, full diagnostic evidence).

# WebRTC AEC3 VoicePipeline/HarnessService Integration (Plan B)

## Overview

Wire the now-validated `NativeAec3` module (Plan A — vendored, built, and proven on-device to
suppress a known echo by ~72dB while leaving an independent signal ~0.01dB touched) into the real
voice pipeline: feed live TTS audio into `analyzeRender`, feed live mic audio into
`processCapture`, hand the *cleaned* signal to `SileroVad` instead of the raw gained signal, and
remove the `currentTrack`/`currentMediaPlayer` self-echo gate + `BARGE_IN_GAP_MS` sentence-gap
pause that currently make barge-in only listen between sentences. End state: Teya can be
interrupted **mid-sentence**, while she's actually talking — the capability the platform AEC and
the gap-gated workaround couldn't provide.

- **Motivation**: `thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md` (Plan A) — built and
  validated the native module specifically to remove the current safe-but-limited "only listens in
  gaps between sentences" barge-in behavior. Plan A's own Appendix scoped this integration as its
  explicit follow-up; this is that follow-up.
- **Related**:
  - `thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md` (Plan A — vendoring, build,
    on-device validation, including the double-talk-from-frame-zero finding this plan must design
    around)
  - `thoughts/shared/research/2026-07-08-barge-in-vad-options.md` (original barge-in research —
    platform AEC over-subtraction failure mode)
  - `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
  - `app/src/main/kotlin/com/teya/agent/harness/HarnessService.kt`
  - `app/src/main/kotlin/com/teya/agent/voice/WakeWordEngine.kt`
  - `app/src/main/kotlin/com/teya/agent/voice/aec/NativeAec3.kt`
  - `app/src/main/kotlin/com/teya/agent/voice/vad/SileroVad.kt` (lifecycle pattern for the
    per-turn `bargeInFired`/VAD-trust gate; `NativeAec3` itself is **session**-scoped instead — see
    Current State Analysis' corrected framing below, resolved during this plan's own review)

## Current State Analysis

**TTS playback has two paths with very different PCM access** (`VoicePipeline.kt`):
- `streamToSpeaker` (`VoicePipeline.kt:404-470`): the **primary, streaming** path. `TTS_SAMPLE_RATE
  = 24000` (`VoicePipeline.kt:39`), PCM16 mono. Runs on `Dispatchers.IO`. Audio arrives via
  `client.streamSpeechPcm(text) { floats -> ... }` (`VoicePipeline.kt:433`) as a **chunked
  callback** — each invocation is one network chunk, converted to `ShortArray` and written to the
  `AudioTrack` (`VoicePipeline.kt:435-446`). This callback is the exact, already-decoded-PCM tap
  point for `analyzeRender`. `currentTrack` is set at `VoicePipeline.kt:429`, cleared in the
  `finally` block at `VoicePipeline.kt:468` (after a drain-loop that polls
  `audioTrack.playbackHeadPosition`, `VoicePipeline.kt:454-460`).
- `playMp3` (`VoicePipeline.kt:473-523`): the **fallback** path. Fetches a whole mp3 clip
  (`client.synthesizeSpeech`), then plays it via `MediaPlayer` + `ByteArrayMediaDataSource`
  (`VoicePipeline.kt:486`). **`MediaPlayer` decodes and plays opaquely — there is no PCM access
  point anywhere in this method.** This is a hard constraint, not a gap to close: AEC3's
  `analyzeRender` cannot be fed for this path without replacing `MediaPlayer` entirely (out of
  scope — see "What We're NOT Doing").

**The self-echo gate and gap pause this plan removes/replaces**:
- `forwardArmedChunk` (`VoicePipeline.kt:186-229`), called from `WakeWordEngine`'s dedicated
  capture `Thread` (`WakeWordEngine.kt:237-260`, not a coroutine) via the `onArmedAudioChunk`
  callback (`WakeWordEngine.kt:253`, wired at `VoicePipeline.kt:46`). Line 2 of the function body:
  `if (currentTrack != null || currentMediaPlayer != null) return` (`VoicePipeline.kt:188`) — this
  is the entire self-echo defense today, and it's what makes barge-in gap-gated: while either sink
  is playing, `forwardArmedChunk` does nothing at all.
- `HarnessService.respond()`'s sentence loop (`HarnessService.kt:377-421`): plays each sentence
  (`voicePipeline.textToSpeech(sentence)`, `HarnessService.kt:409`), then `delay(BARGE_IN_GAP_MS)`
  (900ms, `HarnessService.kt:62,419`) before pulling the next sentence off the channel. The comment
  at `HarnessService.kt:414-417` states explicitly this gap exists *because* back-to-back playback
  would leave no listening window — i.e. the gap and the gate above are two halves of the same
  workaround.
- `onBargeIn()` (`HarnessService.kt:286-291`) is detector-agnostic — it only reads
  `conversationActive` and cancels `activeTurnJob` / calls `voicePipeline.interrupt()`. **No changes
  needed here**, confirmed again this session (matches Plan A's research).

**Frame-size mismatch across three layers**: `WakeWordEngine`'s shared `AudioRecord` is 16kHz mono
PCM16, delivered in `CHUNK = 1280`-sample (80ms) chunks (`WakeWordEngine.kt:62-63,212-218`).
`forwardArmedChunk` already reassembles these into `SileroVad`'s 512-sample frames via a running
leftover buffer (`vadFrameBuffer`, `VoicePipeline.kt:61,198-216`, guarded by `synchronized(vadLock)`
— `vadLock` is `VoicePipeline.kt:68`). `NativeAec3` needs a *third* frame size: exactly
`NativeAec3.FRAME_SIZE = 160` samples (10ms @ 16kHz). This plan adds a second reassembly stage
(1280 → 160 for AEC3) whose *output* (AEC3-cleaned 160-sample frames) feeds the *existing* 512-frame
reassembly for Silero — i.e. Silero's input changes from "raw gained chunk" to "AEC3-cleaned
chunk," but Silero's own reassembly logic doesn't need to change shape.

**Sample-rate mismatch for render**: `streamToSpeaker` produces 24kHz PCM; `analyzeRender` requires
16kHz (matching the 16kHz capture-side `AudioRecord` and `NativeAec3.FRAME_SIZE=160`'s 10ms-@-16kHz
assumption). **No resampling utility exists anywhere in the app's Kotlin code** — a repo-wide search
for `resample`/`Resample` under `app/src/main/**/*.kt` returns zero matches; the only
resampling-related code in the repo is inside the vendored/shimmed WebRTC C++ sources
(`app/src/main/cpp/third_party/webrtc/modules/audio_processing/audio_buffer.{h,cc}`,
`app/src/main/cpp/webrtc_shim/modules/audio_processing/audio_buffer.h`), which isn't wired up as a
general-purpose Kotlin-callable utility. This must be written from scratch (Phase 1).

**`SileroVad`'s lifecycle pattern** (`VoicePipeline.setBargeInArmed`, `VoicePipeline.kt:137-167`):
construct fresh inside `synchronized(vadLock)` only when arming (`VoicePipeline.kt:140,152`); on
disarm, `wakeWordEngine.bargeInArmed = false` first, then inside `synchronized(vadLock)`:
`close(); = null` (`VoicePipeline.kt:161,163-164`). `vadLock` exists specifically to stop a
disarm's `close()` racing a concurrent native call on the capture thread (`VoicePipeline.kt:63-67`).
`NativeAec3` already has its own internal lock + `CLOSED_HANDLE` sentinel guarding exactly this
race at the native-call level (Plan A, Phase 3b).

**Corrected during this plan's own review — arm/disarm is per-turn, not per-conversation**: tracing
`HarnessService.runConversation()` (`HarnessService.kt:300-336`) shows `setBargeInArmed(true)`/
`setBargeInArmed(false)` brackets exactly **one `respond()` call** — disarm happens at the top of
every loop iteration before `listenForCommand` (`HarnessService.kt:309`), and re-arm happens fresh
before every subsequent response (`HarnessService.kt:325`). A multi-turn conversation with N
follow-ups therefore constructs N separate `SileroVad` instances today, one per turn, not one for
the whole session.

**The double-talk-from-frame-zero finding (Plan A, Phase 4) is the central design constraint here,
and it interacts badly with per-turn arming**: AEC3 measured ~0dB echo suppression when fed a
double-talk signal from the very first frame, vs. ~72dB once genuinely converged (under 1 second of
echo-only signal in Plan A's synthetic test). Barge-in detection must not be *trusted* until AEC3
has seen a real, uncontaminated render-only window. If `NativeAec3`'s lifecycle mirrored
`SileroVad`'s exactly (one instance per armed window = one per turn, per the correction above),
*every single turn* in a conversation — not just the one right after an interruption — would pay a
fresh ~1-2s "can't trust barge-in yet" window at its start. **Decision (see Implementation
Approach): `NativeAec3`'s lifecycle is decoupled from `SileroVad`'s and instead spans the whole
conversation session** (`runConversation()`'s lifetime), so convergence pays its cost once per
session, not once per turn. `SileroVad` keeps its existing per-turn construct/close — only the
`bargeInFired`-trust gate (Phase 4) reads from `NativeAec3`'s session-wide convergence state, not a
per-turn one.

**Permissions**: re-confirmed this session — `app/src/main/AndroidManifest.xml:4-30` still has no
`MODIFY_AUDIO_SETTINGS`. Not needed for this plan (no `AudioManager` mode/routing changes here).

## Desired End State

- A new `Resampler` utility (exact location TBD in Phase 1) converts 24kHz PCM16 mono to 16kHz
  PCM16 mono, correctly enough to preserve voice-band frequency content (verified via a
  Goertzel-style tone test, the same technique Plan A's Phase 4 used).
- `VoicePipeline` owns one `NativeAec3` instance **per conversation session** (`aec3: NativeAec3?`,
  constructed once near the start of `HarnessService.runConversation()` and closed once when that
  session ends) — deliberately decoupled from `SileroVad`'s per-turn arm/disarm lifecycle, so
  convergence happens once per session, not once per turn.
- `streamToSpeaker`'s per-chunk callback resamples each arriving 24kHz chunk to 16kHz and feeds it
  to `analyzeRender` in `NativeAec3.FRAME_SIZE`-sized pieces, for every sentence played in the
  session — this is what lets AEC3 build and maintain one converged echo estimate across the whole
  conversation, including across turns.
- `forwardArmedChunk`'s reassembled 160-sample chunks are passed through `processCapture` before
  being handed to `SileroVad`'s 512-sample reassembly — Silero sees AEC3-cleaned audio, not raw
  gained mic audio.
- The `currentTrack != null` half of `forwardArmedChunk`'s gate is removed whenever the *current*
  playback moment is on the AEC3-covered `streamToSpeaker` path — re-evaluated live, since the
  streaming-vs-`playMp3` choice is made independently per sentence (confirmed during review; see
  Current State Analysis), not fixed for a whole turn. Live listening (including mid-sentence) is
  additionally gated on **a render-only convergence lead-in that has elapsed since the session's
  very first render frame** (not reset per turn). Before that lead-in elapses, capture continues to
  feed `processCapture` (so AEC3 keeps converging) but the result is not passed to Silero / not
  allowed to fire `bargeInFired`.
- `HarnessService`'s `BARGE_IN_GAP_MS` inter-sentence `delay` is removed for whichever sentences
  actually play via `streamToSpeaker` (re-evaluated per sentence, not per turn), while sentences
  that fall back to `playMp3` keep the current gap-gated behavior unchanged (see "What We're NOT
  Doing").
- A simple kill-switch (single boolean/feature constant) can disable AEC3-based continuous
  barge-in and revert to the old gap-gated behavior without a design change, for use if real-device
  behavior (Phase 5) turns out worse than the workaround it replaces.
- Verify via: a real on-device conversation where interrupting Teya **while she is mid-sentence**
  (not in a gap) reliably triggers `onBargeIn()`, and where Teya's own uninterrupted speech does
  **not** false-trigger barge-in once past the lead-in window.

## What We're NOT Doing

- **Not adding PCM access to the `playMp3`/`MediaPlayer` fallback path.** `MediaPlayer` decodes
  and plays opaquely; there is no tap point without replacing it (a much larger, separate effort).
  The fallback path keeps its current gap-gated (`currentTrack`/`currentMediaPlayer`-null +
  `BARGE_IN_GAP_MS`) behavior exactly as it is today — this plan only upgrades the primary
  `streamToSpeaker` path. `playMp3`'s own `currentMediaPlayer` check inside `forwardArmedChunk`'s
  gate is therefore **not removed**, only the `currentTrack` half is (see Phase 4).
- **Not touching `AudioManager` mode/routing, and not adding `MODIFY_AUDIO_SETTINGS`.** Confirmed
  still unnecessary for this plan.
- **Not re-tuning `EchoCanceller3Config`** beyond its shipped defaults — same scope boundary as
  Plan A. If real-device convergence/suppression numbers in Phase 5 turn out to need tuning, that's
  a follow-up, not this plan.
- **Not building a general-purpose resampler.** A narrow, fixed-ratio (24000→16000, i.e. 3:2) PCM16
  decimator is all this needs — no arbitrary sample-rate support.
- **Not changing `onBargeIn()`'s cancellation logic** — confirmed detector-agnostic and unchanged
  in Plan A's research; re-confirmed this session.
- **Not per-turn or per-sentence re-convergence.** One `NativeAec3` instance persists for the
  entire conversation session (deliberately *not* mirroring `SileroVad`'s per-turn
  construct/close — see Current State Analysis' corrected framing) — convergence happens once,
  near the start of the session's first sentence, not once per turn or per sentence.
- **Not building a general state machine for the lead-in gate.** It's a single elapsed-time/
  frame-count check against one session-scoped timestamp, not new arm/disarm states.
- **No native/NDK/CMake changes.** `NativeAec3`'s public API (`analyzeRender`/`processCapture`/
  `close`) is already complete from Plan A; this plan is Kotlin-only wiring plus the new Kotlin
  resampler.

## Implementation Approach

- **`NativeAec3`'s lifecycle spans the whole conversation session, not each armed window/turn** —
  the single most important design decision in this plan, forced by a Critical finding during
  review: `setBargeInArmed(true)`/`(false)` brackets exactly one `respond()` call
  (`HarnessService.kt:305,309,325,332`), so mirroring `SileroVad`'s lifecycle exactly would pay the
  ~1-2s convergence-trust cost on *every* turn, not just after a barge-in. Concretely:
  `HarnessService.runConversation()` calls a new pair of `VoicePipeline` methods (construct/close
  `aec3`) once at session start/end, independent of the per-turn `setBargeInArmed` calls that still
  govern `SileroVad` as they do today. `SileroVad`'s per-turn lifecycle is unchanged.
- Split render-side wiring (Phase 2) from capture-side wiring (Phase 3) before touching the actual
  gate/gap removal (Phase 4) — mirrors Plan A's own risk-isolation philosophy (Phase 3a/3b split):
  a bug in render wiring shouldn't be tangled up with a bug in capture wiring when both are new.
- The resampler (Phase 1) is pure Kotlin math with no Android/device dependency at all — it gets a
  plain JVM unit test (`app/src/test/`, no device needed), the cheapest possible phase to validate,
  done first. Method: linear interpolation at the exact 2/3 resampling positions (simplest correct
  approach for a fixed 3:2 ratio; a FIR decimator would add complexity this narrow, farend-reference
  use case doesn't need — see Phase 1).
- The convergence-lead-in mechanism (Phase 4) is a simple elapsed-time/frame-count gate against the
  session's first-render-frame timestamp — not a new state machine, and not reset per turn. Feed
  `processCapture` unconditionally once armed (so AEC3 always keeps converging), but gate whether
  the *result* is allowed to reach `SileroVad`/fire barge-in on "has enough render-only lead-in
  elapsed since the session's first render frame."
- The streaming-vs-`playMp3` gate (Phase 4) is re-evaluated per playback moment, not cached per
  turn — confirmed during review that the choice is made independently per sentence
  (`VoicePipeline.textToSpeech`, `VoicePipeline.kt:373-385`).
- A single boolean kill-switch gates the whole AEC3-active behavior change (Phase 4), so real-device
  results in Phase 5 that turn out worse than the old gap-gated workaround can be reverted without a
  design change — see Failure Mode / Rollback below.
- Real end-to-end correctness (does mid-sentence interruption actually work, does Teya's own speech
  stay silent) is a real-device, human-judgment concern — Phase 5 is deliberately Manual
  Verification-heavy, unlike Plan A's phases which could prove correctness with fully synthetic
  instrumented tests. This is an honest scope difference, not a shortcut: there's no practical way
  to synthesize "a human talks over the phone's real speaker into its real mic" in an instrumented
  test. Phase 5 does still log real suppression/false-trigger diagnostics during manual sessions
  (mirroring Plan A Phase 4's "log real numbers, don't just guess" discipline) rather than tuning by
  feel alone.

## Failure Mode / Rollback

This plan changes live, daily-use voice UX with no PR/branch safety net (this project commits
direct to `main`, per its own convention) — unlike Plan A, whose validation was fully synthetic and
reversible with zero user-facing impact, a bad outcome here (excessive false-triggered barge-ins,
or barge-in failing to fire when it should) is something the household would actually notice.

- **Kill-switch**: a single boolean/feature constant (exact name/location TBD in Phase 4) gates the
  entire AEC3-active behavior change — when off, `forwardArmedChunk` and `HarnessService.respond()`
  behave exactly as they did before this plan (gate + gap intact), regardless of whether `aec3` is
  constructed and fed. This makes "it's worse than before" a one-line revert, not a design
  discussion, if Phase 5's real-device testing goes badly.
- **If Phase 4's manual tests reveal frequent false-triggers or missed barge-ins that Phase 5's
  tuning can't fix** (i.e. the lead-in duration isn't the actual problem), that's a **stop and
  reassess** trigger: flip the kill-switch off, and treat further AEC3-based continuous barge-in
  work as a follow-up plan informed by what specifically went wrong (e.g. `EchoCanceller3Config`
  tuning, a longer/adaptive lead-in, or accepting the gap-gated workaround as the durable answer for
  this device's specific acoustic path) — not something to force through phase-by-phase.
- **No silent fallback is baked into the phases themselves** — same principle as Plan A: reverting
  is the user's call if real-device behavior disappoints, not something to resolve unilaterally by
  quietly loosening what "success" means.

## Quick Verification Reference

- Compile-check: `./gradlew assembleDebug --offline`
- Install: `./gradlew installDebug --offline`
- Resampler unit test (no device): `./gradlew testDebugUnitTest --offline`
- Instrumented tests (device required, wireless adb per `CLAUDE.md`):
  `./gradlew connectedAndroidTest --offline`
- Manual: tap the orb, have Teya speak a multi-sentence response, talk over her mid-sentence;
  confirm interruption; confirm no false-trigger during her own uninterrupted speech.

---

## Phase 1: 24kHz→16kHz PCM resampler

### Overview

A standalone Kotlin utility that downsamples 24kHz PCM16 mono to 16kHz PCM16 mono (3:2 ratio),
with no Android dependency — the missing piece needed before `streamToSpeaker`'s output can be fed
to `analyzeRender`.

### Changes Required:

#### 1. Resampler utility
**File**: `app/src/main/kotlin/com/teya/agent/voice/aec/Resampler.kt` (new)
**Changes**: A small stateful class converting a stream of `ShortArray` chunks from 24000Hz to
16000Hz PCM16 mono, via **linear interpolation at the exact 2/3 resampling positions** (decided
during review, over a FIR decimator, as the simplest correct method for a fixed 3:2 ratio — AEC3
needs a reasonably faithful *farend reference* for its adaptive filter to track, not
broadcast-quality audio, so interpolation error at this ratio is not a real concern). Must carry
fractional-position state across calls (a running phase accumulator), since `streamToSpeaker`
delivers audio in independent callback chunks whose lengths don't evenly divide by the 3:2 ratio —
without carried state, every chunk boundary would introduce a small click or duplicated/dropped
sample.

### Success Criteria:

#### Automated Verification:
- [x] `./gradlew testDebugUnitTest --offline` passes

#### Automated QA:
- [x] Unit test: generate a known-frequency tone (e.g. 500Hz) at 24kHz, resample to 16kHz, and
      assert the output's dominant frequency (via a Goertzel-magnitude probe, matching Plan A
      Phase 4's technique) is still ~500Hz within a small tolerance — proves the resampler preserves
      frequency content, not just "produces the right number of samples"
- [x] Unit test: feed the resampler in several independently-sized chunks (simulating
      `streamToSpeaker`'s real chunk-by-chunk delivery) vs. one single call with all the same
      samples, and assert the two produce equivalent output — proves cross-chunk state carries
      over correctly

#### Manual Verification:
- [ ] None — this is pure, fully-automatable DSP math (confirmed: no manual verification items
      apply to this phase; left unchecked per convention, not overlooked)

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 2: Feed real TTS audio into `analyzeRender`

### Overview

`VoicePipeline` owns one `NativeAec3` instance **for the whole conversation session** (not per
armed window — see the Critical review finding this resolves) and feeds it real, resampled TTS
audio from `streamToSpeaker`'s callback — render-side wiring only, deliberately isolated from any
capture-side change.

### Changes Required:

#### 1. Session-scoped `NativeAec3` instance lifecycle
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`,
`app/src/main/kotlin/com/teya/agent/harness/HarnessService.kt`
**Changes**: Add `@Volatile private var aec3: NativeAec3? = null` plus a dedicated `aecLock = Any()`
in `VoicePipeline` (a separate lock from `vadLock`, since `aec3`'s lifecycle is intentionally
decoupled from `sileroVad`'s — sharing one lock across two independently-scoped resources would be
more confusing than two small locks). Add `VoicePipeline` methods e.g. `startAecSession()` /
`endAecSession()` (construct/close `aec3` under `aecLock`, mirroring `SileroVad`'s
construct-inside-lock / close-inside-lock pattern at the code level even though the lifecycle scope
differs). `HarnessService.runConversation()` (`HarnessService.kt:300-336`) calls `startAecSession()`
once near its start and `endAecSession()` once in its existing `finally` block
(`HarnessService.kt:331-335`) — independent of the per-turn `setBargeInArmed` calls, which continue
to govern only `sileroVad` as they do today. Add `@Volatile private var firstRenderFrameAtMs: Long =
0L` (or a frame counter) in `VoicePipeline`, set once (only if still `0L`) the first time
`analyzeRender` is actually called in a session — this field is written from the render/TTS thread
and read from the capture thread in Phase 4's lead-in gate, so `@Volatile` (not a plain `var`) is
required for correctness, not just style.

#### 2. Feed `analyzeRender` from `streamToSpeaker`
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
**Changes**: In `streamToSpeaker`'s per-chunk callback (`VoicePipeline.kt:433` area), after
converting the arriving float chunk to `ShortArray` (24kHz), resample it to 16kHz via Phase 1's
`Resampler`, buffer/split into `NativeAec3.FRAME_SIZE`-sized (160-sample) pieces, and call
`aec3?.analyzeRender(frame)` for each — do this regardless of whether an `AudioTrack` write
succeeds, so render analysis and playback aren't coupled to each other's failure modes, and
regardless of `setBargeInArmed` state (render feeding is session-scoped, not per-turn-armed). No
capture-side change in this phase — `forwardArmedChunk`'s existing gate stays exactly as-is.

### Success Criteria:

#### Automated Verification:
- [x] `./gradlew assembleDebug --offline`
- [x] `./gradlew installDebug --offline`
- [x] No regression: existing app behavior (gap-gated barge-in) still works exactly as before —
      this phase must be behaviorally invisible except for the new render feed running in the
      background

#### Automated QA:
- [x] Add a `@VisibleForTesting` counter or debug-log marker for "render frames fed to AEC3 this
      armed window" (exact mechanism TBD at implementation time — check whether `VoicePipeline`'s
      `MistralClient` dependency is injectable/fakeable enough for a real instrumented test to drive
      a fake TTS utterance end-to-end; if not, this becomes a manual logcat check instead — decide
      honestly based on what's actually feasible, don't force an automated test that isn't real).
      Checked: `VoicePipeline.mistralClient` is typed as the concrete `MistralClient` class (not
      the `BrainClient` interface `MistralClient` implements), and the TTS methods this phase reads
      (`streamSpeechPcm`) aren't part of that interface anyway — there is no existing fake/mock
      seam, and building one would be a DI change beyond this phase's scope. Implemented the
      debug-log fallback instead: `VoicePipeline.feedRenderToAec3` logs
      `"AEC3: render frames fed to analyzeRender this session = $renderFrameCount"` every 100
      frames (~1s of 16kHz render audio), for manual logcat verification.

#### Manual Verification:
- [x] Have a real conversation; confirm via logcat that render frames are being fed to `NativeAec3`
      while Teya speaks (the counter/marker from Automated QA should be visibly incrementing).
      Confirmed 2026-07-10: logcat showed "AEC3: render frames fed to analyzeRender this session =
      2800" then "= 5200", incrementing across turns within one session as designed. User separately
      noted mid-sentence interruption still doesn't work and the 900ms gap is still present — both
      expected at this point: Phase 2 is render-wiring only, gate/gap removal is Phase 4.

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 3: Feed cleaned capture audio into `SileroVad`

### Overview

`forwardArmedChunk`'s reassembled mic audio is run through `processCapture` before reaching
`SileroVad` — capture-side wiring, still without changing *when* barge-in is allowed to fire (the
`currentTrack`/`currentMediaPlayer` gate stays for now), so this phase is testable via the existing
gap-gated flow.

### Changes Required:

#### 1. Second frame-reassembly stage (1280 → 160) feeding AEC3
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
**Changes**: In `forwardArmedChunk`, before the existing gain + 512-sample Silero reassembly, split
the incoming 1280-sample chunk into `NativeAec3.FRAME_SIZE`-sized (160-sample) pieces (a leftover
buffer, same pattern as the existing `vadFrameBuffer`), call `aec3?.processCapture(frame)` on each,
and concatenate the *cleaned* 160-sample outputs back into a buffer that feeds the existing gain +
512-sample Silero reassembly path in place of the raw chunk. If `aec3` is `null` (no active
conversation session, or somehow not constructed), fall back to the raw chunk unchanged — this
phase must not regress behavior when AEC3 isn't active for any reason.

### Success Criteria:

#### Automated Verification:
- [x] `./gradlew assembleDebug --offline`
- [x] `./gradlew installDebug --offline`
- [x] `./gradlew connectedAndroidTest --offline` — no regressions in Plan A's existing
      `NativeAec3SmokeTest`/`NativeAec3EchoCancellationTest`

#### Automated QA:
- [x] None new beyond what Plan A's own tests already cover for `NativeAec3` in isolation — this
      phase's correctness is really "does the real capture path call the already-validated API
      correctly," which is best checked by the manual verification below plus code review, not a
      new synthetic test (the synthetic echo-cancellation correctness itself was already proven in
      Plan A)

#### Manual Verification:
- [ ] Real conversation, gap-gated barge-in still works exactly as before (behavior should be
      unchanged or subtly improved, not broken) — confirms the new capture wiring didn't regress
      the existing (still gap-gated at this point) barge-in path

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 4: Remove the gate/gap — enable continuous mid-sentence barge-in

### Overview

The actual behavior change: remove the `currentTrack`-null gate and the `BARGE_IN_GAP_MS` pause for
sentences that play via `streamToSpeaker`, gated by a session-scoped render-only convergence
lead-in so barge-in detection isn't trusted before AEC3 has actually converged (per Plan A's
double-talk-from-frame-zero finding). The `playMp3` fallback path's gap-gated behavior is
explicitly preserved, re-evaluated per sentence rather than assumed per turn. A kill-switch makes
the whole change revertible without a design change.

### Changes Required:

#### 1. Convergence lead-in gate (session-scoped)
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
**Changes**: Using the session-scoped `firstRenderFrameAtMs` from Phase 2 (not reset per turn),
compute whether the lead-in has elapsed since that one session-wide timestamp (duration TBD at
implementation time — derive from Plan A's measured ~1s synthetic-signal convergence plus a real
safety margin, re-checked against real device audio in Phase 5 rather than assuming the synthetic
number transfers exactly). Before the lead-in has elapsed: still call `processCapture` (so AEC3
keeps converging) but do not pass the result to `SileroVad` / do not allow `bargeInFired` to be
set. After it elapses (which, session-scoped, happens once near the very start of the
conversation, not at the start of every turn): pass through normally for the rest of the session.

#### 2. Remove the self-echo gate for the AEC3-active path — re-evaluated per sentence
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
**Changes**: `forwardArmedChunk`'s `if (currentTrack != null || currentMediaPlayer != null) return`
(`VoicePipeline.kt:188`) becomes conditional: skip the early return when `currentTrack != null`
*and* `aec3` is non-null (streaming path currently playing, AEC3 cleaning the signal) — but keep
returning early when `currentMediaPlayer != null` (mp3 fallback, no AEC3 coverage, must stay
gap-gated per "What We're NOT Doing"). Because `streamToSpeaker` vs. `playMp3` is chosen
independently per sentence (`VoicePipeline.textToSpeech`, `VoicePipeline.kt:373-385` — confirmed
during review, not a per-turn choice), this condition naturally re-evaluates correctly on every
call to `forwardArmedChunk` since it just reads the current values of `currentTrack`/
`currentMediaPlayer`, which already flip per-sentence as playback switches paths — no new
per-sentence state needed here, just confirming the existing gate check is inherently
moment-correct once `currentTrack` alone (not `currentMediaPlayer`) is exempted.

#### 3. Remove the inter-sentence gap for sentences that streamed
**File**: `app/src/main/kotlin/com/teya/agent/voice/HarnessService.kt`
**Changes**: The `delay(BARGE_IN_GAP_MS)` in `respond()`'s sentence loop (`HarnessService.kt:419`)
is no longer needed after a sentence that played via `streamToSpeaker` (barge-in listens
continuously during and after it). Since `textToSpeech` (`VoicePipeline.kt:373-385`) already
returns having tried streaming first and falls back to `playMp3` only on failure, gate the delay on
whichever path *that specific sentence* actually took — e.g. have `textToSpeech` (or a variant)
report back which path played, and only apply `BARGE_IN_GAP_MS` when it was `playMp3`.

#### 4. Kill-switch
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt` (or a shared constants file)
**Changes**: One boolean/feature constant (e.g. `AEC3_BARGE_IN_ENABLED`) that, when `false`, makes
items 1-3 above no-ops — `forwardArmedChunk` and `respond()`'s gap keep their pre-Plan-B behavior
exactly, regardless of whether `aec3` is constructed and being fed. See Failure Mode / Rollback.

### Success Criteria:

#### Automated Verification:
- [x] `./gradlew assembleDebug --offline`
- [x] `./gradlew installDebug --offline`
- [x] `./gradlew connectedAndroidTest --offline` — no regressions

#### Automated QA:
- [x] None new — this phase's correctness is inherently a real-device, real-audio, real-human-voice
      question; forcing a synthetic automated "proof" here would be exactly the kind of degenerate
      check the plan should avoid claiming

#### Manual Verification:
- [ ] Have a real conversation; interrupt Teya **mid-sentence** (not in a gap) and confirm
      `onBargeIn()` fires and the turn is interrupted
- [ ] Let Teya speak an uninterrupted multi-sentence response; confirm no false-trigger barge-in
      once past the lead-in window
- [ ] Specifically test interrupting *very early* in the **first sentence of the whole session**
      (within the lead-in window) — confirm this does **not** false-trigger (or does, if the
      lead-in is too short — this is exactly what Phase 5 tunes)
- [ ] Have a multi-turn conversation (interrupt once, then continue) and confirm the **second and
      later turns do not re-pay the lead-in delay** — this is the concrete, testable proof that the
      session-scoped lifecycle decision actually works, not just that it compiles
- [ ] Flip the kill-switch off and confirm behavior reverts exactly to the pre-Plan-B gap-gated
      flow

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 5: Real-device tuning and validation

### Overview

The lead-in duration and any other constants chosen provisionally in Phase 4 get validated (and
adjusted if needed) against this specific device's real acoustic path, real speaker/mic hardware,
and a real human voice — the thing Plan A's fully-synthetic tests couldn't cover and didn't try to.

### Changes Required:

#### 1. Debug logging for real-device suppression/false-trigger diagnostics
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
**Changes**: Add debug-level logcat logging (mirroring Plan A Phase 4's "log real numbers" habit,
not this plan's earlier draft which relied on "a round of real conversations" plus subjective
judgment alone) — e.g. log the VAD confidence/energy level Silero sees on cleaned vs. what it would
have seen on raw capture for a sampling of frames, and log every `bargeInFired` event with a
timestamp relative to session start, so tuning decisions in item 2 below are informed by what
actually happened during manual testing, not just how it felt.

#### 2. Tune the convergence lead-in against real device audio
**File**: `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
**Changes**: If Phase 4's manual tests (now informed by item 1's logging) reveal the lead-in is too
short (false-triggers early) or unnecessarily long (feels sluggish to interrupt right after the
session's first sentence starts), adjust the constant based on what's actually observed on this
device — not the synthetic Plan A number, which was measured against clean synthetic tones, not
this device's real speaker→mic acoustic path noise floor and real voice characteristics.

### Success Criteria:

#### Automated Verification:
- [x] N/A so far — conditional on the tuning constant changing; Phase 5 not yet reached (currently
      paused after Phase 2 for manual verification). Re-open this checkbox if Phase 5 changes a
      constant and needs a rebuild check.

#### Automated QA:
- [ ] None — real acoustic tuning is not something to fake an automated proof for

#### Manual Verification:
- [ ] A round of real conversations covering: mid-sentence interruption (multiple sentences into a
      turn), very-early interruption (first sentence, near the lead-in boundary), and an
      uninterrupted long response (checking for false positives) — confirm the tuned behavior feels
      right and matches what Plan A's Motivation section set out to fix
- [ ] Confirm the `playMp3` fallback path (if it's ever actually exercised in practice) still
      behaves exactly as it did before this plan — gap-gated, unaffected

**Implementation Note**: This is the last phase. After manual confirmation, this plan (and Plan A
before it) together deliver the originally-scoped capability: AEC3-based, mid-sentence-capable
barge-in, replacing the gap-gated workaround end to end.

---

## Appendix

- **Follow-up plans**: none identified yet. If Phase 5's real-device tuning reveals AEC3's default
  `EchoCanceller3Config` needs adjustment for this specific speaker/mic hardware, that would be a
  natural follow-up plan (explicitly out of scope here, per "What We're NOT Doing").
- **Real-device finding, discovered live-testing Phase 4 (2026-07-10), not yet confirmed fixed**:
  mid-sentence interruption did not work on the first Phase 4 build. Root cause: the platform
  `AcousticEchoCanceler` on `WakeWordEngine`'s shared `AudioRecord` (`WakeWordEngine.kt`,
  `enableAudioEffects`) was still enabled during playback. It was harmless before Phase 4 (barge-in
  never listened during playback at all), but Phase 4 turns on continuous listening *during*
  playback, where this device's platform AEC — already documented in this file and in
  `2026-07-08-barge-in-vad-options.md` as unreliable/over-suppressive — was found to crush the
  captured signal to near-silence before `NativeAec3`/Silero ever saw it (logcat showed raw
  pre-gain amplitude reading a hard `0/32767` across every 2-second diagnostic window during
  playback, which a genuinely live mic essentially never does).
  - **Fix attempt 1** (uncommitted, superseded by fix 2): `VoicePipeline.startAecSession()` /
    `endAecSession()` call a new `WakeWordEngine.setPlatformAecEnabled(Boolean)` to disable the
    platform AEC for the session and re-enable it after. Retested: still didn't work.
  - **Fix attempt 2** (uncommitted, current state as of last edit): found `WakeWordEngine.start()`
    calls `enableAudioEffects()` fresh on **every mic restart**, which happens repeatedly *within*
    one conversation (once after every `listenForCommand()` cycle) — each restart was
    unconditionally re-`setEnabled(true)`-ing the platform AEC, silently undoing fix 1's disable
    well before the user got a chance to interrupt mid-response. Added
    `WakeWordEngine.platformAecDesiredEnabled` (persisted, `@Volatile`) so `enableAudioEffects`
    applies the last-requested state instead of hardcoding `true`, and `setPlatformAecEnabled` both
    updates that persisted flag and applies it live.
  - **Status**: fix 1 + fix 2 confirmed working (2026-07-10 retest, logcat evidence): `AEC available,
    enabled=false` persists correctly across mic restarts within the session, and raw pre-gain
    amplitude is no longer stuck at 0 (`18026/32767` observed) — the platform-AEC wiring bug is
    resolved. Both Kotlin files (`VoicePipeline.kt`, `WakeWordEngine.kt`) still **uncommitted**.
  - **New, deeper finding (same retest)**: with the platform AEC genuinely off and real signal
    reaching Silero, `NativeAec3` itself is not sufficiently cancelling Teya's own TTS echo in this
    real acoustic environment. Logcat: `Barge-in: speech detected (confidence=0.9909005)` /
    `(confidence=0.89004755)` firing on Teya's own voice, ~7s and ~1.8s respectively into two
    separate turns (well past the 2s lead-in, so not a lead-in-timing issue) — user's own
    follow-up utterance ("Okay, so you keep interrupting yourself, no?") confirms these were
    self-triggers, not real interruption attempts. Plan A's ~72dB suppression number was measured
    against clean synthetic tones (Plan A Phase 4); real broadband TTS speech through this device's
    actual speaker→mic acoustic path is a harder case that wasn't validated end-to-end before now.
    Candidate causes, untested as of this note: (a) `BARGE_IN_GAIN` (6x software gain in
    `VoicePipeline.kt`, applied *after* `cleanCaptureChunk`) amplifying a residual echo that AEC3
    left behind — the loud TTS source signal may leave a larger absolute residual than the quiet
    real speech this gain was originally tuned for, even at reasonable dB suppression; (b) AEC3's
    internal delay estimation not locking onto this device's actual render→capture round-trip
    latency (a classic real-hardware AEC challenge synthetic tests don't exercise); (c)
    `EchoCanceller3Config` defaults genuinely needing retuning for this speaker/mic pair (explicitly
    out of scope for this plan, flagged as a likely follow-up in the Appendix above). This is
    exactly the "stop and reassess" trigger described in Failure Mode / Rollback — decision on how
    to proceed is the user's, not something to force through by continuing to patch blindly.
  - **Root cause confirmed (2026-07-10, diagnostic logging added to `forwardArmedChunk`/
    `VoicePipeline.kt`, comparing raw vs. AEC3-cleaned peak amplitude on the same chunk)**: AEC3
    provides negligible-to-zero real suppression in the live pipeline. Evidence from the retest:
    `confidence=0.91788214, rawPeak=12056, cleanedPeak(pre-gain)=12098` (cleaned **higher** than
    raw — zero suppression), `confidence=0.96789384, rawPeak=4776, cleanedPeak(pre-gain)=3786`
    (~21%/~2dB reduction, nowhere near Plan A's measured ~72dB), and one 2-second window where
    `peak RAW = 1910` and `peak CLEANED = 1910` were bit-for-bit identical. This rules out
    candidate cause (a) (gain amplifying a *residual* echo — there is essentially no suppression
    to amplify) and points squarely at (b): **render/capture time-alignment**. AEC3's adaptive
    filter only cancels echo it can correlate against a time-aligned render reference; Plan A's
    synthetic on-device test (Phase 4) almost certainly fed render+capture frames in lockstep
    (an idealized fixed delay), which is not representative of this live pipeline, where
    `analyzeRender` is called from `streamToSpeaker`'s per-chunk callback right before
    `audioTrack.write()` (`VoicePipeline.kt`) — a proxy for "about to play," not "physically
    emitting from the speaker right now." Any drift between that call and the real acoustic delay
    (`AudioTrack` internal buffering, streaming/network chunk irregularities, hardware DAC latency)
    can easily exceed what AEC3's delay estimator can lock onto, collapsing suppression to ~0dB
    regardless of how converged the adaptive filter otherwise is. This is a materially harder,
    more open-ended problem than any of the wiring bugs fixed so far — likely requiring either (i)
    an explicit delay estimate/compensation passed to AEC3 (check whether the JNI wrapper or
    `EchoCanceller3Config` exposes a settable nominal delay), or (ii) restructuring where/when
    `analyzeRender` is called to track actual output timing much more tightly (nontrivial: Android's
    `AudioTrack` doesn't expose a direct "this sample is playing now" callback, only buffer-position
    polling). **Not attempted or fixed as of this note** — this is squarely a "stop and reassess"
    point per Failure Mode/Rollback, not something to keep patching inline.
- **Derail notes**:
  - **Resolved during review**: `playMp3` fallback selection is per-sentence, not per-response.
    `textToSpeech()` (`VoicePipeline.kt:373-385`) calls `streamToSpeaker` fresh for every sentence
    and falls back to `playMp3` independently on failure (`VoicePipeline.kt:381`), with no
    turn-level memoized state — so within one armed window, sentence 1 could stream (AEC3-covered)
    while sentence 2 falls back to mp3 (gap-gated) and sentence 3 streams again. See Review Errata
    (Important #2) for the implication this has for Phase 4's gate logic.
  - Whether `VoicePipeline`'s `MistralClient` dependency has an existing fake/mock seam suitable for
    a real automated instrumented test of the render-wiring path (Phase 2's Automated QA) wasn't
    confirmed this session either — worth checking early in Phase 2 rather than assuming either way.
- **References**:
  - Plan A: `thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md`
  - Research: `thoughts/shared/research/2026-07-08-barge-in-vad-options.md`
  - This session's `VoicePipeline.kt`/`HarnessService.kt`/`WakeWordEngine.kt` research (file:line
    citations inlined throughout Current State Analysis above)

## Review Errata

_Reviewed: 2026-07-09 (adversarial pass via `desplega:reviewing`, with codebase-analyzer
verification)._

### Resolved
- [x] Derail note on `playMp3` fallback granularity (per-sentence vs per-response) — confirmed via
      codebase-analyzer and folded into the Derail notes above and Phase 4 item 2/3.
- [x] **Critical — per-turn arm/disarm would force a fresh convergence lead-in on every response
      turn.** Resolved by decoupling `NativeAec3`'s lifecycle from `SileroVad`'s: `NativeAec3` now
      spans the whole conversation session (constructed/closed once by `HarnessService.
      runConversation()`, independent of the per-turn `setBargeInArmed` calls), so convergence pays
      its cost once per session, not once per turn. Reflected in Current State Analysis, Desired
      End State, What We're NOT Doing, Implementation Approach, and Phase 2/4's Changes Required;
      Phase 4 gained a manual verification item specifically proving second/later turns don't
      re-pay the lead-in.
- [x] **Important — streaming-vs-`playMp3` choice is per-sentence, not per-turn.** Phase 4 items 2
      and 3 rewritten to re-evaluate the gate/gap per playback moment rather than assuming a stable
      per-turn mode.
- [x] **Important — no Failure Mode / Rollback section.** Added one (mirroring Plan A's), plus a
      dedicated kill-switch item in Phase 4 and a manual verification step proving it actually
      reverts behavior.
- [x] **Important — cross-thread lead-in timestamp needed explicit thread-safety treatment.**
      Phase 2 item 1 now specifies `@Volatile private var firstRenderFrameAtMs: Long` explicitly,
      with the render-thread-write/capture-thread-read rationale spelled out.
- [x] **Important — Phase 1's resampling method left as an either/or.** Committed to linear
      interpolation at the exact 2/3 resampling positions; documented why over a FIR decimator in
      both Phase 1 and Implementation Approach.
- [x] **Important — Phase 5 had no structured measurement plan.** Added a new Phase 5 item 1
      (debug logging of VAD confidence/energy and `bargeInFired` events) so tuning in item 2 is
      informed by real logged numbers, matching Plan A Phase 4's discipline.
