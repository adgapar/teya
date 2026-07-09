---
date: 2026-07-09T00:00:00Z
topic: "WebRTC AEC3 native port implementation plan (Plan A: vendor + build + validate)"
tags: [voice, barge-in, aec3, webrtc, ndk, android-native]
status: in-progress
last_updated: 2026-07-09T00:00:00Z
last_updated_by: phase-running (Phase 2)
---

# WebRTC AEC3 Native Module — Vendor, Build, Validate (Plan A)

## Overview

Vendor Google WebRTC's AEC3 (Acoustic Echo Canceller v3) into Teya as a small, purpose-built native
module — built from AEC3's own primary source, not a third-party wrapper — and prove on-device that
it actually cancels a known echo signal. This is **Plan A of two**: it stands up the native
module and proves it works in isolation. **Plan B** (not written yet — see Appendix) wires the
validated module into `VoicePipeline`/`HarnessService`, replacing the current gap-gated barge-in
workaround.

- **Motivation**: `thoughts/shared/research/2026-07-08-barge-in-vad-options.md` — this device's
  platform `AcousticEchoCanceler` over-subtracts and zeroes real speech during TTS playback,
  independently confirmed with two different detectors (Mistral Realtime STT, then Silero VAD).
  Barge-in currently ships in a safe-but-limited state (only listens in gaps between TTS sentences).
  A real AEC3 implementation removes that limitation by cancelling the *known* echo (Teya's own TTS
  audio) at the signal level instead of avoiding it by timing.
- **Related**:
  - `thoughts/shared/research/2026-07-08-barge-in-vad-options.md` (barge-in history + AEC3 decision)
  - `app/src/main/kotlin/com/teya/agent/voice/VoicePipeline.kt`
  - `app/src/main/kotlin/com/teya/agent/voice/WakeWordEngine.kt`
  - `app/src/main/kotlin/com/teya/agent/voice/vad/SileroVad.kt` (the JNI-adjacent pattern this
    plan's Kotlin wrapper should mirror — same "vendored ML/DSP core behind a small Kotlin class"
    shape, ONNX Runtime instead of a custom JNI build, but same lifecycle idea)

## Current State Analysis

**Zero native/NDK build step exists today.** Confirmed via repo-wide search: no `CMakeLists.txt`,
no `Android.mk`, no `ndkVersion`/`externalNativeBuild` in `app/build.gradle.kts` or
`gradle/libs.versions.toml`, no `ndk.dir` in `local.properties`, and no NDK package installed under
`~/Library/Android/sdk/ndk`. The only native code in the app today ships as prebuilt AARs:
`com.google.ai.edge.litert:litert` (TFLite, wake word) and `com.microsoft.onnxruntime:onnxruntime-android`
(Silero VAD). `app/build.gradle.kts`: `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`;
`gradle/libs.versions.toml`: AGP `9.2.1`, Kotlin `2.0.0`.

**Audio pipeline shape** (`VoicePipeline.kt`, `WakeWordEngine.kt`, `SileroVad.kt`,
`HarnessService.kt` — full detail from this session's research pass):
- Mic capture: a single shared `AudioRecord` lives in `WakeWordEngine` (16kHz mono PCM16,
  `VOICE_RECOGNITION` source, 1280-sample/80ms chunks) — Android can't reliably open a second
  concurrent `AudioRecord` on this device, so `VoicePipeline` taps the same stream via
  `onArmedAudioChunk` rather than opening its own.
- `VoicePipeline.forwardArmedChunk` currently: gates entirely on `currentTrack != null ||
  currentMediaPlayer != null` (skips VAD while Teya's own audio plays — the existing self-echo
  defense), applies a 6× software gain (no hardware AGC on this device), reassembles 1280-sample
  chunks into Silero's 512-sample frames, and calls `SileroVad.isSpeech()` synchronously on the
  capture thread.
- TTS playback: two paths in the same file — `streamToSpeaker` (`AudioTrack`, `MODE_STREAM`,
  `USAGE_MEDIA`, PCM16 mono **24kHz**) and `playMp3` (`MediaPlayer` mp3 fallback). Both set
  `currentTrack`/`currentMediaPlayer` for the self-echo gate's duration.
- `SileroVad.isSpeech()` contract: exactly 512 PCM16 samples per call, strictly sequential (stateful
  RNN, 64-sample rolling context prepended each call) — one instance per armed window.
- `HarnessService.respond()` inserts a `BARGE_IN_GAP_MS = 900L` pause after each spoken sentence —
  this is the exact window the gap-gated workaround listens in; `onBargeIn()` cancels
  `activeTurnJob` and calls `voicePipeline.interrupt()`. This cancellation plumbing is
  detector-agnostic and needs **no changes** for AEC3 (confirmed in the research doc and re-confirmed
  this session).
- `AndroidManifest.xml` has **no `MODIFY_AUDIO_SETTINGS`** permission — a prior attempt to force
  `AudioManager` call-mode routing for AEC purposes silently no-op'd because of this (see
  `VoicePipeline.kt` comment block above `streamToSpeaker`). Not needed for this plan (no
  `AudioManager` mode changes here), but worth remembering if Plan B touches routing.

**AEC3 sourcing research** (this session — see full findings for citations):
- AEC3 lives at `webrtc.googlesource.com/src/modules/audio_processing/aec3/` on the current WebRTC
  `main` branch. It is extractable directly as a scoped `.tar.gz` via googlesource's `+archive`
  endpoint (confirmed working) — no depot_tools, no full multi-GB Chromium checkout.
- `webrtc-audio-processing` (the PulseAudio/PipeWire extraction) is Meson-based, Linux-only, vendors
  the *whole* APM (not an AEC3-only slice), and has no Android/NDK prior art — it is **not** a
  shortcut for this use case.
- AEC3 itself is ~94 source/header files (per `BUILD.gn`), with a real but narrow dependency spine:
  a handful of `rtc_base` utility files, `api/audio` (`AudioBuffer`, `EchoControl`), `system_wrappers`
  metrics glue, one Abseil header at the direct-target level (transitively more via `rtc_base`/`api`),
  and the Ooura FFT (`common_audio/third_party/ooura`) — permissively licensed but under its own
  distinct attribution-request license text, not BSD/Apache.
- No prior art exists for an **AEC3-only** Android/NDK port (the few Android WebRTC-audio repos found
  all build the full APM via `ndk-build`). This is genuinely unclaimed territory — budget real time
  for first-pass NDK+Abseil toolchain integration.
- Public API (`EchoCanceller3`, confirmed from live header): `AnalyzeRender(AudioBuffer*)` (farend/TTS
  reference), `AnalyzeCapture(AudioBuffer*)` + `ProcessCapture(AudioBuffer*, bool)` (nearend/mic,
  cleaned in-place). Render and capture are designed to be called from **separate threads** — this
  matches our two-Kotlin-call-site shape (TTS playback thread feeds render, mic capture thread feeds
  capture) — but capture-side calls must be serialized relative to each other. At 16kHz mono, AEC3
  operates on plain 10ms/160-sample full-band frames — no multi-band splitting (that only kicks in
  above 16kHz).
- Licensing: AEC3/WebRTC BSD-3-Clause, Abseil Apache-2.0, Ooura FFT its own permissive
  attribution-request license (must be listed separately in third-party notices, matching the
  existing `THIRD_PARTY_MODELS.md` pattern used for the wake-word and Silero models).

## Desired End State

A native `libteya_aec3.so`, built for **arm64-v8a only** via NDK/CMake, containing AEC3 vendored
from a **pinned WebRTC commit**, wrapped in a small Kotlin class (working name `NativeAec3`,
mirroring `SileroVad`'s shape: construct → feed frames → `close()`) exposing:
```kotlin
class NativeAec3(sampleRateHz: Int = 16000) : Closeable {
    fun analyzeRender(frame: ShortArray)                    // 160 samples (10ms @ 16kHz), farend
    fun processCapture(frame: ShortArray): ShortArray        // 160 samples, nearend -> cleaned
    override fun close()
}
```
Proven correct via an **on-device instrumented test** (`connectedAndroidTest`) using synthetic
render/capture signals generated in the test itself: a pure-echo case (capture = attenuated/delayed
copy of render only) where `processCapture` output energy drops sharply relative to input, and a
speech+echo case (capture = independent "speech-like" signal + echo) where the independent
component survives while the correlated echo component is suppressed. **Not yet wired into
`VoicePipeline` or `HarnessService`** — that integration, including resampling the 24kHz TTS
reference down to 16kHz and replacing the gap-gating logic, is Plan B.

Verify the end state by running `./gradlew connectedAndroidTest --offline` (after the one-time
online NDK install) and seeing the AEC3 validation test pass with logged before/after energy ratios.

## What We're NOT Doing

- **No desktop/host-side C++ prototype** (explicit decision this session) — go straight to
  Android/NDK and validate via an instrumented test on-device, accepting that DSP bugs and
  NDK/build bugs get debugged together rather than in isolation.
- **No `armeabi-v7a` or other ABIs** — arm64-v8a only, matching the single dedicated dev device
  (Samsung A34). Add more ABIs later only if a second device type is needed.
- **No wiring into `VoicePipeline`/`WakeWordEngine`/`HarnessService`** — no touching the gap-gating
  logic, no resampling the real 24kHz TTS stream, no replacing Silero's role. That is Plan B, written
  after this module's real JNI shape exists (better to design the integration against a working
  interface than a hypothetical one).
- **No full WebRTC Audio Processing Module** — only AEC3. No AGC, no noise suppression, no
  beamforming, no multi-channel/stereo support (this device is one mic, one speaker).
- **No custom tuning of `EchoCanceller3Config`** beyond its shipped defaults in this plan — tuning
  against this specific device's real echo path is a Plan B (or later) concern once real audio is
  flowing through it.
- **No auto-update / re-vendoring mechanism** — the vendored source is pinned to one commit hash,
  copied in, and documented. Re-vendoring later is a manual, deliberate act, not a build-time fetch
  (the project builds `--offline`; a live fetch from googlesource at build time is a non-starter).

## Implementation Approach

- Vendor AEC3 + its minimal dependency slice from `webrtc.googlesource.com` via the `+archive`
  scoped-tarball endpoint, pinned to one commit, dropped under `app/src/main/cpp/third_party/webrtc/`
  with the pinned commit hash recorded in a `VENDORING.md` next to it (mirrors how
  `THIRD_PARTY_MODELS.md` already documents the wake-word/Silero model provenance).
  Everything WebRTC-side that's clearly unused for a mono-16kHz-only build (AVX2 variant target,
  unit test files, multi-band/`NeuralResidualEchoEstimator` machinery if it turns out to be
  optional) is stripped from the copy rather than compiled and disabled.
- Stand up the NDK/CMake toolchain **incrementally**: first prove the build pipeline itself with a
  trivial JNI passthrough function (no AEC3 code compiled yet), *then* add the real vendored source
  once the toolchain is known-good. This gives an early, cheap checkpoint before the harder vendoring
  work, without requiring a full separate desktop prototype.
- One online step is required up front: install an NDK package via `sdkmanager` (this machine has
  none installed yet). Unlike the prior single-artifact Maven resolves (`ktor-client-websockets`,
  `onnxruntime-android`), this is **not** a similarly low-risk step — it's followed by compiling
  ~94+ vendored C++ files against a not-yet-designed Abseil integration, which is where the real
  risk in this plan actually lives (see Failure Mode / Rollback below). After the NDK install, the
  project's normal `--offline` Gradle builds continue to work; the risk is in what gets built
  offline, not in the fetch itself.
- Kotlin wrapper class deliberately mirrors `SileroVad`'s existing shape (construct with config,
  feed fixed-size frames synchronously, explicit `close()`) so it's a familiar, drop-in-feeling
  pattern for whoever writes Plan B, and reuses the "one instance per armed window" lifecycle idea.
- Validation is fully synthetic and self-contained in the instrumented test (generated tones/noise
  at test time) — no need to record real device audio or manage test-asset WAV files for this plan;
  real device audio validation is inherently a Plan B concern (that's when actual TTS output and
  actual mic capture exist to test against).

## Failure Mode / Rollback

If the NDK+CMake+Abseil toolchain doesn't build cleanly at any point in Phases 2–3, that is a
**stop and reassess with the user** trigger, not something to silently work around:

- Phase 2 (trivial passthrough) failing means the NDK/CMake/Gradle wiring itself is broken — no
  vendored AEC3 code is involved yet, so this is purely a toolchain/environment problem. Isolate via
  `sdkmanager --list` / NDK-version-vs-AGP compatibility before touching anything else.
- Phase 3a (AEC3 + Abseil compiling as a standalone static lib, see below) failing means either the
  Phase 1 dependency slice is incomplete (a file `BUILD.gn` lists wasn't vendored), the Abseil
  surface was scoped incorrectly, or a WebRTC-internal macro (`RTC_LOG`, metrics) needs stubbing in a
  way not yet anticipated. Debug this in isolation — that's the entire point of splitting Phase 3a
  out from JNI work.
- If, after a bounded debugging effort (a working session, not open-ended), the toolchain still
  won't produce a working static lib or `.so`, stop and reassess with the user rather than continuing
  to sink time into it. Bring back to the user at that point: descope to a smaller AEC3 slice,
  reconsider a third-party Android AEC wrapper despite the earlier rejection, or abandon native AEC3
  and stay on the gap-gated barge-in workaround.
- No silent fallback is baked into this plan (e.g. auto-reverting to gap-gating on build failure) —
  that decision belongs to the user if this risk materializes, not something to resolve unilaterally
  mid-implementation.

## Quick Verification Reference

- Build (compile-check only, per `CLAUDE.md`): `./gradlew assembleDebug --offline`
- Install: `./gradlew installDebug --offline`
- Native module instrumented test: `./gradlew connectedAndroidTest --offline` (requires the device
  connected via wireless adb, per `CLAUDE.md`'s device workflow)
- If wireless adb drops mid-phase (per `CLAUDE.md`, this happens periodically) and
  `connectedAndroidTest` can't run, that's a connectivity problem, not a code failure — ask the user
  to reconnect (USB / re-toggle wireless debugging) and retry before debugging the native code
  itself.
- Sanity-check no native/NDK regressions in the rest of the app: existing app still builds and the
  voice loop still runs (this plan adds a new isolated native module; it does not touch any existing
  Kotlin call sites).

---

## Phase 1: Vendor AEC3 source + document provenance/licensing

### Overview

Extract AEC3 and its minimal dependency slice from a pinned WebRTC commit into
`app/src/main/cpp/third_party/webrtc/`, with a `VENDORING.md` recording exactly what was pulled,
from where, and at what commit, plus third-party license notices.

### Changes Required:

#### 1. Vendored source tree
**Files**: `app/src/main/cpp/third_party/webrtc/**` (new)
**Changes**: Pull `modules/audio_processing/aec3/` (minus unit tests and the AVX2 variant target)
plus the specific `rtc_base`, `api/audio`, `system_wrappers`, and `common_audio/third_party/ooura`
files it depends on (per `aec3/BUILD.gn`'s `deps`), via
`https://webrtc.googlesource.com/src/+archive/<pinned-commit>/<path>.tar.gz` for each needed
subdirectory. Record the exact commit hash used.

#### 2. Provenance + licensing doc
**File**: `app/src/main/cpp/third_party/webrtc/VENDORING.md` (new)
**Changes**: Pinned commit hash, source URL, exact list of subdirectories pulled, and a note that
this is AEC3 only (not the full APM) — same spirit as the existing `THIRD_PARTY_MODELS.md`.

**File**: `THIRD_PARTY_MODELS.md`
**Changes**: Add an entry (or a sibling "third-party native code" section) covering WebRTC AEC3
(BSD-3-Clause), Abseil (Apache-2.0), and the Ooura FFT (its own attribution-request license) —
matching the existing per-dependency documentation style already used there for the wake-word and
Silero models.

### Success Criteria:

#### Automated Verification:
- [x] Vendored tree contains no unit-test or AVX2-variant files: `find app/src/main/cpp/third_party/webrtc -iname '*unittest*' -o -iname '*_test.cc' -o -iname '*_test.h' -o -iname '*_gtest*'` returns empty
- [x] Pinned commit hash is recorded and matches what was fetched: `grep -q '<commit-hash>' app/src/main/cpp/third_party/webrtc/VENDORING.md` (commit `99de2d09036e61b72e4e6bba3ab09fedc40d18fe`)

#### Automated QA:
- [x] `scripts/check_aec3_deps.sh` (new): parses `aec3/BUILD.gn`'s `deps` list from the pinned
      commit and diffs it against the vendored tree's file list under
      `app/src/main/cpp/third_party/webrtc/`, exiting nonzero on any dependency with no corresponding
      vendored file — a real, re-runnable command instead of an implicit manual cross-check
      (ran clean: 53 source-file references across 30 deps, 1 documented Abseil skip, 0 failures)

#### Manual Verification:
- [ ] None — this phase is source vendoring + docs only, fully checkable by the automated steps above

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 2: NDK/CMake build toolchain skeleton (trivial passthrough)

### Overview

Stand up the project's first-ever native build step end-to-end — NDK install, CMake config, Gradle
wiring, arm64-v8a-only ABI filter — proven with a trivial JNI function (no AEC3 code compiled yet)
so any toolchain problems are caught before the harder vendoring/compilation work in Phase 3a.

### Changes Required:

#### 1. NDK install
**File**: n/a (SDK-level, one-time)
**Changes**: `sdkmanager --install "ndk;<version>"` (pick the latest LTS NDK available at
implementation time; confirm via `sdkmanager --list`). Document the chosen version in
`app/build.gradle.kts`'s `ndkVersion`.

#### 2. Gradle/CMake wiring
**File**: `app/build.gradle.kts`
**Changes**: Add `ndkVersion`, `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }`,
and `defaultConfig.ndk.abiFilters += "arm64-v8a"`.

**File**: `app/src/main/cpp/CMakeLists.txt` (new)
**Changes**: `cmake_minimum_required`, C++17, a `teya_aec3` shared library target built from a single
placeholder `.cpp` file exposing one trivial `extern "C" JNIEXPORT jint JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_ping(...)`-style function.

#### 3. Kotlin JNI declaration
**File**: `app/src/main/kotlin/com/teya/agent/voice/aec/NativeAec3.kt` (new, skeleton)
**Changes**: `companion object { init { System.loadLibrary("teya_aec3") } }`, one `external fun
ping(): Int` declaration, no real API surface yet.

#### 4. Passthrough test
**File**: `app/src/androidTest/kotlin/com/teya/agent/voice/aec/NativeAec3PingTest.kt` (new)
**Changes**: Instrumented test loading `NativeAec3`, calling `ping()`, asserting the expected
sentinel value — proves the whole toolchain (NDK → CMake → `.so` → JNI → Kotlin) works before any
real DSP code is added.

### Success Criteria:

#### Automated Verification:
- [x] Compile-check: `./gradlew assembleDebug --offline` (NDK r27d + CMake 3.22.1 installed via
      `sdkmanager` — the plan's one online step; build succeeds fully offline after)
- [x] Install: `./gradlew installDebug --offline` (device: SM-A346E via wireless adb)
- [x] Passthrough instrumented test passes: `./gradlew connectedAndroidTest --offline` (required
      one additional one-time online run first, to fetch the Unified Test Platform jars —
      `connectedAndroidTest` had never run in this project before Phase 2, so those artifacts were
      never cached; unrelated to the NDK/CMake/JNI toolchain itself. Confirmed `--offline` passes
      cleanly afterward.)
- [x] Built APK contains only the intended ABI: `unzip -l app/build/outputs/apk/debug/app-debug.apk
      | grep lib/` shows `lib/arm64-v8a/libteya_aec3.so` and no other ABI directories (confirmed;
      other prebuilt native deps — onnxruntime, tensorflowlite — are also arm64-v8a-only already)

#### Automated QA:
- [x] `NativeAec3PingTest` demonstrates the full JNI round-trip end-to-end on-device, not just a
      compile-time check (`ping_returnsSentinelValue` passed on SM-A346E, asserting `ping() == 42`)

#### Manual Verification:
- [ ] None expected — the instrumented test is a genuine on-device proof; flag here only if
      `connectedAndroidTest` behaves unexpectedly on this specific device/adb setup

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 3a: Compile AEC3 + dependencies as a standalone static lib (no JNI yet)

### Overview

Compile the vendored AEC3 sources (from Phase 1) plus the minimal Abseil subset they depend on into
a standalone CMake static library target, with **no JNI glue code at all**. This isolates the two
independently risky pieces flagged in review — (a) vendoring/integrating Abseil and (b) stubbing
`RTC_LOG`/metrics macros to avoid pulling in unwanted `rtc_base` — from JNI-wrapping work, so a
build failure here is caught and debugged before any JNI code is written on top of a foundation that
doesn't actually compile.

### Changes Required:

#### 1. Scope the exact Abseil surface
**File**: `app/src/main/cpp/third_party/webrtc/VENDORING.md`
**Changes**: Before writing any CMake, nail down the concrete Abseil header/target list AEC3's
dependency chain actually touches — starting from `absl::strings:string_view` (used directly at the
`aec3` target level per `BUILD.gn`) plus whatever `rtc_base`/`api` pull in transitively (confirm by
grepping vendored `#include "absl/..."` headers across the Phase 1 tree). Record the final list here
next to the pinned WebRTC commit hash, so Phase 3a's CMake target has a fixed, known target list to
vendor/`FetchContent` rather than guessing during the build.

#### 2. CMake static lib target
**File**: `app/src/main/cpp/CMakeLists.txt`
**Changes**: Add a `teya_aec3_core` **static** library target (no JNI symbols, no `Java_...`
exports) built from the vendored AEC3 + dependency sources plus the scoped Abseil subset from item
1; set `-std=c++17` and stub whatever WebRTC-internal macros (`RTC_LOG`, metrics) are needed to
compile outside the full `gn`/Chromium build. Add a trivial non-JNI consumer (one `extern "C"` smoke
function in a throwaway `.cpp`) that references at least one real AEC3 symbol, so the linker
actually exercises the static lib rather than silently dropping an unused target.

### Success Criteria:

#### Automated Verification:
- [ ] Compile-check: `./gradlew assembleDebug --offline` succeeds with `teya_aec3_core` built and
      linked into its trivial consumer, with no missing-symbol/unresolved-reference errors (which
      would indicate an incomplete Phase 1 dependency slice or Abseil surface)

#### Manual Verification:
- [ ] None expected — a clean compile+link is itself the proof this checkpoint exists to provide

**Implementation Note**: This is the plan's Failure Mode / Rollback checkpoint for the toolchain —
if this phase can't be made to compile within a bounded effort, stop and reassess with the user
rather than pushing forward into Phase 3b's JNI work on top of a shaky foundation. If commit-per-
phase was requested, create a commit after verification passes.

---

## Phase 3b: JNI glue + Kotlin wrapper — real render/capture API

### Overview

With AEC3 + deps proven to compile and link (Phase 3a), add the real JNI glue and replace the
Phase 2 `ping()` skeleton with the real `EchoCanceller3`-backed API (`analyzeRender` /
`processCapture`), configured for mono 16kHz, full-band, single-channel operation with
`EchoCanceller3Config` defaults.

### Changes Required:

#### 1. Native JNI glue
**File**: `app/src/main/cpp/jni_aec3.cpp` (new)
**Changes**: Own an `EchoCanceller3` instance (16kHz, mono render, mono capture, no multichannel
config, `neural_residual_echo_estimator = nullptr`) plus two reusable `AudioBuffer`s (one for
render, one for capture — both single full-band 160-sample frames at 16kHz, no band-splitting).
Links against the `teya_aec3_core` static lib from Phase 3a. Expose:
- `nativeCreate(sampleRateHz: Int): Long` — returns an opaque handle
- `nativeAnalyzeRender(handle: Long, frame: ShortArray)` — converts PCM16→float, calls
  `AnalyzeRender`
- `nativeProcessCapture(handle: Long, frame: ShortArray): ShortArray` — converts PCM16→float, calls
  `AnalyzeCapture` + `ProcessCapture`, converts the cleaned float buffer back to PCM16, returns it
- `nativeDestroy(handle: Long)`
Capture-side calls (`AnalyzeCapture`/`ProcessCapture`) are only ever invoked from the Kotlin
wrapper's single owning thread (matches the existing `vadLock`-style single-writer discipline
already used for `SileroVad`); render calls may come from a different thread per AEC3's own
documented threading contract.

#### 2. Kotlin wrapper — real API
**File**: `app/src/main/kotlin/com/teya/agent/voice/aec/NativeAec3.kt`
**Changes**: Replace the `ping()` skeleton with the real class shape described in Desired End
State — `analyzeRender(frame: ShortArray)`, `processCapture(frame: ShortArray): ShortArray`,
`close()` (calls `nativeDestroy`, sets handle to a sentinel to guard against use-after-close, same
concern the `SileroVad`/`vadLock` comment already documents for a different native runtime).
`require()` frame size == 160 samples (10ms @ 16kHz), matching `SileroVad`'s existing
`require()`-on-frame-size pattern.

### Success Criteria:

#### Automated Verification:
- [ ] Compile-check: `./gradlew assembleDebug --offline`
- [ ] Install: `./gradlew installDebug --offline`
- [ ] Smoke instrumented test passes: `./gradlew connectedAndroidTest --offline` (device connected
      via wireless adb — see the adb-flakiness note in Quick Verification Reference if this can't
      run)

#### Automated QA:
- [ ] Smoke instrumented test constructs `NativeAec3`, feeds one silent render frame and one silent
      capture frame, and asserts **no crash, a 160-sample output, AND that the output is
      bounded/near-silent** (e.g. below a fixed dBFS threshold) rather than NaN or garbage — a
      silent input producing garbage output would otherwise pass a length-only check — then
      `close()`s cleanly

#### Manual Verification:
- [ ] None expected at this phase — correctness of the actual echo cancellation is Phase 4's job;
      this phase only proves it runs without crashing and produces sane output for a trivial input

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes.

---

## Phase 4: On-device validation — prove it actually cancels echo

### Overview

An instrumented test that generates synthetic render/capture signals at test time and proves
`NativeAec3` measurably suppresses the correlated (echo) component while leaving an independent
(speech-like) component intact — the concrete, numeric proof that this module is ready for Plan B
to wire into the real pipeline.

### Changes Required:

#### 1. Echo-cancellation correctness test
**File**: `app/src/androidTest/kotlin/com/teya/agent/voice/aec/NativeAec3EchoCancellationTest.kt`
(new)
**Changes**: Two scenarios, both generated in-test (no asset files):
- **Pure-echo case**: render = a synthetic multi-tone/noise-burst signal; capture = the same signal
  scaled + delayed by a fixed sample offset (simulating speaker→mic leakage), fed through
  `analyzeRender`/`processCapture` frame-by-frame in real frame order (feed enough frames for
  AEC3's adaptive filter to converge — likely on the order of a few hundred ms to a few seconds of
  synthetic audio, per AEC3's typical convergence behavior). Assert cleaned-capture energy drops
  sharply (define a concrete threshold, e.g. ≥20 dB reduction) relative to raw capture, once
  converged. The exact dB threshold and convergence-time window here are placeholders — derive the
  real numbers at implementation time from AEC3's own upstream reference (its `_unittest.cc` files
  at the pinned commit, excluded from vendoring but still readable from the source tarball, or
  WebRTC's documented AEC3 convergence/suppression behavior) rather than picking a self-selected
  number that just barely passes.
- **Speech+echo case**: capture = the same delayed/scaled echo **plus** an independent synthetic
  "speech-like" component uncorrelated with render (e.g., a different-frequency tone burst or
  band-limited noise not derived from the render signal). Assert the independent component's energy
  survives processing much better than the echo component's does — i.e., AEC3 suppresses the known
  echo without zeroing unrelated signal, the exact failure mode observed with this device's platform
  AEC in the original research.
- Log before/after energy ratios for both cases (visible in `connectedAndroidTest` output / logcat)
  so a human can sanity-check the numbers, not just the pass/fail.

### Success Criteria:

#### Automated Verification:
- [ ] `./gradlew connectedAndroidTest --offline` passes both scenarios in
      `NativeAec3EchoCancellationTest`

#### Automated QA:
- [ ] Pure-echo scenario demonstrates a defined, meaningful energy-reduction threshold (not just
      "some" reduction) once the filter has converged
- [ ] Speech+echo scenario demonstrates the independent (non-echo) component is preserved
      substantially better than the echo component — the specific property platform AEC failed at

#### Manual Verification:
- [ ] Skim the logged before/after energy numbers to sanity-check they're not passing on a
      degenerate threshold (e.g., a threshold so loose it would pass even with AEC3 doing nothing)

**Implementation Note**: After this phase, pause for manual confirmation. If commit-per-phase was
requested, create commit after verification passes. This is the last phase of Plan A — completing
it means the native module is ready for Plan B (VoicePipeline/HarnessService integration).

---

## Appendix

- **Follow-up plans**: **Plan B** (not yet written) — wire `NativeAec3` into `VoicePipeline`:
  feed real TTS PCM (resampled 24kHz→16kHz) into `analyzeRender` from the existing
  `streamToSpeaker`/`playMp3` call sites, feed real mic chunks from `forwardArmedChunk` into
  `processCapture` before handing the cleaned frame to `SileroVad`, and remove the
  `currentTrack`/`currentMediaPlayer` self-echo gate + `BARGE_IN_GAP_MS` sentence-gap pause once AEC3
  is confirmed live-effective (allowing continuous, mid-sentence barge-in). Should be scoped as its
  own plan once Plan A's real JNI shape exists, informed by whatever's learned integrating it
  (e.g., real-world convergence time on this specific device/speaker path, actual echo delay).
- **Derail notes**:
  - `NeuralResidualEchoEstimator` appeared in the live `EchoCanceller3` constructor signature during
    research and looks like a relatively recent addition; confirm at vendoring time (Phase 1) whether
    it's safely nullable/optional for the pinned commit, or whether it pulls in unwanted extra
    dependencies that need stubbing.
  - The Ooura FFT license is real and distinct (attribution-request, not BSD/Apache) — don't fold it
    into a generic "WebRTC is BSD" note in `THIRD_PARTY_MODELS.md`; give it its own line.
  - `webrtc-audio-processing` (Meson/Linux) was investigated and explicitly rejected as a shortcut —
    don't re-litigate this if revisited later; the direct `+archive` extraction from `webrtc/src` is
    the better path for this project's needs.
- **References**:
  - Research: `thoughts/shared/research/2026-07-08-barge-in-vad-options.md`
  - `webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/aec3/` (AEC3 source)
  - `webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/aec3/BUILD.gn`
    (dependency list used to scope Phase 1's vendoring)
  - `webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/aec3/echo_canceller3.h`
    (public API used to design Phase 3b's JNI surface)

## Review Errata

_Reviewed: 2026-07-09 (adversarial pass via `desplega:reviewing`)._

### Resolved
- [x] Phase 1's stray-test-file `find` pattern broadened to also catch `*_test.h`/`*_gtest*` naming
      — auto-fixed.
- [x] **No failure-mode/rollback guidance if the toolchain doesn't build cleanly** — added a
      `## Failure Mode / Rollback` section with an explicit "stop and reassess with the user"
      trigger, bounded-effort framing, and concrete options to bring back to the user.
- [x] **Phase 3 bundled three independently risky steps behind one pass/fail gate** — split into
      **Phase 3a** (AEC3 + Abseil compile as a standalone static lib, no JNI — the new checkpoint)
      and **Phase 3b** (JNI glue + Kotlin wrapper), so an (a)/(b)-type failure is caught before any
      JNI work is sunk into it.
- [x] **Abseil vendoring was underspecified** — added an explicit "scope the exact Abseil surface"
      task as Phase 3a item 1, to be nailed down and recorded in `VENDORING.md` before the CMake
      target is written, not during it.
- [x] **Phase 1's Automated QA wasn't actually automated** — replaced with a concrete
      `scripts/check_aec3_deps.sh` diff-check command against `aec3/BUILD.gn`'s deps.
- [x] **Phase 3's smoke test only asserted no-crash + length, not output sanity** — strengthened to
      also assert bounded/near-silent output for silent input, and folded the near-duplicate
      Automated QA bullet into this single strengthened assertion (now in Phase 3b).
- [x] **Phase 4's numeric thresholds lacked a derivation method** — added a note to derive the dB
      threshold and convergence window at implementation time from AEC3's own upstream unit tests /
      documented behavior, rather than a self-selected number.
- [x] **No fallback for wireless-adb flakiness across Phases 2–4** — added a note to Quick
      Verification Reference: treat a dropped adb connection as a connectivity problem, not a code
      failure, and retry after reconnecting.
- [x] **"One-time online step" framing undersold the real risk** — rephrased in Implementation
      Approach to distinguish the low-risk NDK fetch from the actual risk (compiling vendored AEC3 +
      a not-yet-designed Abseil integration), pointing at Failure Mode / Rollback.
