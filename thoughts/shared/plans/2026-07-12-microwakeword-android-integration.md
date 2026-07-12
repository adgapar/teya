---
date: 2026-07-12T00:00:00Z
topic: "Integrate the trained hey_teya microWakeWord model into the Android wake-word engine"
tags: [voice, wake-word, microwakeword, tflite, jni, ndk, cmake, wakewordengine, third-party-models]
status: "completed — hey_teya validated live on-device (5/5 detections, incl. ~1.5m) and fully cut over; openWakeWord/hey_jarvis removed entirely"
---

# Plan: Integrate the trained `hey_teya` microWakeWord model into the Android app

## Context

We trained a custom "hey teya" wake word model via the Mac WakeWord Trainer app
(`microWakeWord-Trainer-AppleSilicon`), using ~9 personal recordings captured through
`WakeWordSamplePanel.kt`'s temporary Admin panel plus ~50k Piper-TTS-synthesized positives and
standard negative datasets (AudioSet/FMA/WHAM/CHiME/dinner_party/no_speech/speech). Training
finished clean: 40,000 steps, calibrated cutoff=0.53, window=3, **99.85% recall, ~0.93 false
accepts/hour** on ambient validation. Output sits on the Mac at:

```
/Users/adgapar/.taterwakewordtrainer/app/current/trained_wake_words/hey_teya.tflite  (63.5KB, int8)
/Users/adgapar/.taterwakewordtrainer/app/current/trained_wake_words/hey_teya.json    (metadata/calibration)
```

**This model cannot be dropped into the current `WakeWordEngine.kt` as-is.** Confirmed by directly
inspecting both models' actual tensors (not just filenames):

| | Input tensor | Architecture |
|---|---|---|
| `hey_jarvis_v0.1.tflite` (currently bundled, dev-only, CC BY-NC-SA) | `[1, 1536]` float32 | openWakeWord: raw audio → `melspectrogram.tflite` → `embedding_model.tflite` → classifier. All 3 are standard-op float32 TFLite models, no custom ops. |
| `hey_teya.tflite` (ours, unrestricted license — **solves the commercial-use blocker documented in `THIRD_PARTY_MODELS.md`**) | `[1, 2, 40]` int8 | microWakeWord: requires TFLite Micro's **"microfrontend"** — a fixed-point mel-filterbank feature extractor implemented as native C, not expressible as a portable TFLite graph. |

### Project history (why this gap exists)

- Commit `7513410` (2026-07-05): first wake-word attempt, genuinely microWakeWord-based, loaded a
  pretrained `okay_nabu.tflite`. But `processAudio()` was **never finished** — it only logged mic
  amplitude ("Placeholder for TFLite inference. In a real implementation, we'd feed the spectrogram
  here"). Never did real detection.
- Commit `d2ddf5d`: swapped in a `hey_jarvis` model, same (still-stubbed) engine.
- Commit `1268588` (2026-07-06): rewrote to openWakeWord's 3-model chain specifically because it's
  all-standard-ops and portable — "*Real detection replacing the log-only stub*." This is the
  currently-shipping engine.

So the microfrontend problem was never actually solved in this repo, just abandoned in favor of an
architecture that doesn't need it. Now that we have our own microWakeWord model worth using (better
license, tuned to our actual device/voices), it needs solving for real.

### Research done this session (verdict: don't port from scratch)

No portable Kotlin/Java/Python library or TFLite-interpreter-loadable custom op exists for the
microfrontend. But **Home Assistant's own Android Companion app already solved this exact problem**,
shipped and working:

- PR **home-assistant/android#6312** "Implement microwakeword detection" adds a Gradle module
  `microfrontend/` that vendors the **unmodified upstream C files** from `tensorflow/tflite-micro`
  (`tensorflow/lite/experimental/microfrontend/lib/`): `fft.cc`, `fft_util.cc`, `kiss_fft_int16.cc`
  (+ `mborgerding/kissfft` dep), `filterbank.c`, `filterbank_util.c`, `frontend.c`, `frontend_util.c`,
  `log_lut.c`, `log_scale.c`, `log_scale_util.c`, `noise_reduction.c`, `noise_reduction_util.c`,
  `pcan_gain_control.c`, `pcan_gain_control_util.c`, `window.c`, `window_util.c` — compiled via
  CMake/`FetchContent` with `FIXED_POINT=16`.
- `MicroFrontendWrapper.cpp`/`.h` + `MicroFrontend_jni.cpp` expose it via JNI; `MicroFrontend.kt` is
  a thin Kotlin wrapper (`nativeCreate`/`nativeProcessSamples`/`nativeReset`), output scaled by
  `0.0390625f` (`1/256*10`).
- Also credits **brownard/Ava** (github.com/brownard/Ava) as independent prior art for the same
  approach.
- Ships `okay_nabu`/`hey_jarvis`/`hey_mycroft` microWakeWord models today, fully on-device.
- Exact frontend parameters are **officially documented**, not reverse-engineered, in ESPHome's
  `esphome/components/micro_wake_word/preprocessor_settings.h`: window 30ms/step 10ms, 40 channels,
  125–7500Hz, noise-reduction `smoothing_bits=10/even=0.025/odd=0.06/min_signal_remaining=0.05`,
  PCAN `strength=0.95/offset=80.0/gain_bits=21`, log-scale `scale_shift=6`. These match our
  `hey_teya.json`'s frontend block (`feature_duration_ms:30, feature_step_ms:10, feature_size:40,
  lower_band_limit:125.0, upper_band_limit:7500.0`).
- The microWakeWord/ESPHome training+export pipeline has **no option** to bake the frontend into
  the exported graph as standard ops — confirmed by checking the trainer's own export code. The
  split is permanent; there's no shortcut around the native step.

**Bottom line: this is a build-system/JNI integration task (vendor HA's approach), not an
algorithm-porting task.** The hard numerical-correctness risk is already solved by shipped code.

### Related, smaller cleanup to do alongside this

`app/src/main/cpp/` **does not exist in the current tree at all**, and there's no
`externalNativeBuild`/NDK block in `app/build.gradle.kts` — this will be the **first native module**
in the app. But `THIRD_PARTY_MODELS.md` still documents a vendored WebRTC AEC3 native module
(`app/src/main/cpp/third_party/webrtc/`) as if it exists — stale. Per
`thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md` (status: "shipped — NativeAec3
dropped, WebView AEC is the sole default") and `.../2026-07-11-webview-aec-shipped-handoff.md`,
barge-in AEC moved to Chromium's own `getUserMedia({echoCancellation:true})` via `WebViewAecHost`
instead — the native AEC3 JNI approach (`thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md`,
`.../2026-07-09-webrtc-aec3-voicepipeline-integration.md`) was superseded and dropped. `WakeWordEngine.kt`
still has a few comments referencing "`NativeAec3`" as a live concept (lines ~219, 233-238) — stale
terminology, harmless but confusing.

**Action for the fresh session**: before starting the microfrontend native module (which will be
the actual first `app/src/main/cpp/`), do a quick pass to (a) confirm AEC3 vendor code is genuinely
gone (git log / check for any other stray references), (b) fix `THIRD_PARTY_MODELS.md`'s WebRTC
AEC3 section to reflect reality (either delete it or mark it clearly as an abandoned/removed
approach superseded by WebView AEC), (c) reword the stale `NativeAec3` comments in
`WakeWordEngine.kt` to stop implying it's active. Small, low-risk, worth doing first since it
touches the same `cpp/`/build-config territory as the real task.

## Implementation plan

1. **Vendor the microfrontend native sources**, modeled directly on HA's `microfrontend/` module —
   either copy their approach/files (check license: their module should itself be Apache-2.0 per
   tflite-micro's license) or pull the same upstream `tflite-micro` files fresh at a pinned commit.
   Land under `app/src/main/cpp/microfrontend/` (mirrors the *documented* — now-removed —
   `app/src/main/cpp/third_party/webrtc/` convention in `THIRD_PARTY_MODELS.md`, so keep that
   `third_party/<name>/VENDORING.md` pattern: pin exact commit hash, file list, license).
2. **Add NDK/CMake to the build** — `app/build.gradle.kts` currently has zero
   `externalNativeBuild`/`ndk` config, so this is new plumbing, not an extension of existing native
   build code. Reference HA's `microfrontend/build.gradle`/`CMakeLists.txt` for the exact
   `FetchContent`/`FIXED_POINT=16` setup.
3. **JNI wrapper**: port/adapt `MicroFrontendWrapper.cpp/.h` + JNI bridge. Expose
   `create`/`processSamples`/`reset` equivalents.
4. **Kotlin wrapper**: new `MicroFrontend.kt` (voice/ package), thin JNI-calling class producing
   40-dim int8 feature vectors per 10ms step, `0.0390625f` output scaling per HA's reference.
5. **New engine path**: either a new `MicroWakeWordEngine.kt` or a mode inside the existing
   `WakeWordEngine.kt` — feed 16kHz mono audio → `MicroFrontend` → buffer 2 frames → run
   `hey_teya.tflite` (`[1,2,40]` int8 in, `[1,1]` uint8 out) → apply calibrated `cutoff=0.53` with
   `sliding_window_size=3` consecutive-frame-over-threshold logic (conceptually the same shape as
   the existing `wakeWordPatience` knob, but check whether the exact semantics match before reusing
   it as-is). Trigger the existing `onDetected` callback — no changes needed downstream of that.
6. **Bundle the model**: copy `hey_teya.tflite` + `hey_teya.json` from the Mac
   (`~/.taterwakewordtrainer/app/current/trained_wake_words/`) into `app/src/main/assets/`.
7. **Decide the cutover**: replace `hey_jarvis_v0.1.tflite` entirely (recommended — solves the
   CC-BY-NC-SA commercial-use blocker `THIRD_PARTY_MODELS.md` already flags, and this model is
   tuned to our actual household/device), or keep both paths behind a config toggle during
   validation. Update `ConfigManager`'s wake-word tuning knobs (`wakeWordThreshold`,
   `wakeWordInputGain`, `wakeWordPatience`) to match the new model's actual calibrated
   values/semantics — don't assume the old openWakeWord-tuned numbers carry over.
8. **Update `THIRD_PARTY_MODELS.md`**: add the microWakeWord model + vendored tflite-micro frontend
   entry (license, source commit, file list — same rigor as the existing WebRTC/CAM++/Silero
   entries), and do the AEC3 stale-doc cleanup from the section above.
9. **Real-device validation**: install, test at multiple distances (the existing `docs/experiments.md`
   already has a far-field baseline — "Wake word: audio front-end makes it work at ~1.5m" — use that
   as the comparison point for whether the new model is better/worse/comparable in the real room).

## Open questions for the fresh session to resolve early

- Exact `tflite-micro` commit to pin (match HA's PR's pinned commit if findable, or use current
  stable — check for any breaking changes in the frontend lib since).
- Whether `kissfft` needs separate vendoring/fetching or can be inlined.
- Whether to keep `hey_jarvis_v0.1.tflite`/openWakeWord assets around at all after cutover (disk/APK
  size vs. keeping a fallback).

## Not in scope here

`WakeWordSamplePanel.kt` grew a parallel "per-household-member voice ID" feature this session
(`CamPlusPlusSpeakerEmbedder`, `VoiceSample` Room storage) — unrelated to this plan, flagging only
so the fresh session doesn't confuse the two pieces of work in that file.

## Outcome (2026-07-12)

All 9 steps done, in 7 commits on `main` (no branches, per this project's solo-dev convention):
cleanup of dead AEC3 test files first, then vendoring, NDK/CMake plumbing, the
`WakeWordDetector` interface + `MicroWakeWordDetector`, asset bundling, doc updates, and finally
a live on-device validation session.

**Initially kept both engines behind `ConfigManager.useMicroWakeWord`** (default off) rather than
replacing `hey_jarvis` outright — the plan itself flagged this as an acceptable alternative ("keep
both paths behind a config toggle during validation"), chosen live with the user given this is the
app's first native module and a genuinely stateful TFLite model. **Superseded the same day**: after
live use showed `hey_teya` clearly outperforming `hey_jarvis`, the user asked to cut over
immediately rather than wait for more sessions — openWakeWord was removed entirely (assets,
`WakeWordDetector` interface collapsed back to a single class, `useMicroWakeWord` toggle deleted,
shared tuning knobs repurposed for `hey_teya`'s calibration). See the final two rows of
`docs/experiments.md`'s wake-word trail.

**Two things found and fixed that the plan didn't anticipate**:
- `ResamplerTest.kt`/`NativeAec3SmokeTest.kt`/`NativeAec3EchoCancellationTest.kt` were dead test
  files left over from the earlier `NativeAec3` removal (commit `2222c9c`), referencing deleted
  classes — both test source sets were silently broken before this session. Deleted.
- `hey_teya.tflite` is a genuinely *stateful* streaming TFLite model (resource-variable conv
  state, needs `resetVariableTensors()`), not the plain `[1,2,40]→[1,1]` classifier the plan
  assumed — confirmed by inspecting the model's actual tensors directly rather than trusting the
  training pipeline's docs. Also int8-quantized with real scale/zero-point values read from the
  model at load time, not hardcoded.

**Live validation result**: after discovering that `HarnessService`'s foreground `START_STICKY`
lifecycle means swiping the app from recents does *not* apply a config toggle (needs an actual
`am force-stop`), 5/5 detections fired on real hardware — scores 0.70-0.98, including ~1.5m
far-field attempts, no observed ambient false-accepts in the session. Full trail:
`docs/experiments.md` → "Problem: wake word".

**Left for a later session**: further real-world validation (different rooms/times/speakers) of
`hey_teya` as the sole engine, now that there's no fallback to openWakeWord if it underperforms.
