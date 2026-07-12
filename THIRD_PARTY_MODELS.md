# Third-Party Models

The wake-word detector (`app/src/main/assets/`) uses **openWakeWord** — a 3-model chain.
All models are from the [openWakeWord](https://github.com/dscripka/openWakeWord) release `v0.5.1`.

| File | Purpose | License | Commercial use |
|---|---|---|---|
| `melspectrogram.tflite` | raw audio → mel spectrogram | Apache-2.0 | ✅ Yes |
| `embedding_model.tflite` | mel window → 96-dim embedding (Google `speech_embedding`) | Apache-2.0 | ✅ Yes |
| `hey_jarvis_v0.1.tflite` | embeddings → P(wake word) | **CC BY-NC-SA 4.0** | ❌ **No** |

## ⚠️ Commercial-use blocker

The pre-trained **`hey_jarvis_v0.1`** classifier is licensed **CC BY-NC-SA 4.0
(Attribution–NonCommercial–ShareAlike)** — it may **not** be used in a commercial product.
This is the same constraint that ruled out Porcupine.

**The two backbone models (melspectrogram, embedding) are Apache-2.0 and are fine to ship.**

### How to make this shippable

Train our own wake-word classifier using openWakeWord's
[training pipeline](https://github.com/dscripka/openWakeWord#training-new-models) (synthetic
data via Piper TTS; runs in Colab). The output is a `[1,1536] → [1,1]` TFLite model — the exact
same tensor slot as `hey_jarvis_v0.1`. **Swapping it in is a one-line asset change
(`MODEL_WAKE` in `WakeWordEngine.kt`) — no other code changes.** Until then, `hey_jarvis_v0.1`
is fine for development only.

## Pipeline reference

Documented in `WakeWordEngine.kt`. Key parameters (from openWakeWord source):
- 16 kHz mono; processed in 1280-sample (80 ms) chunks.
- Mel: 32 bins; normalization `x / 10 + 2` applied to melspec output.
- Embedding window: 76 mel frames → 96-dim embedding (1 per chunk).
- Classifier input: newest 16 embeddings (1536 floats); default threshold 0.5.

Download URLs (release v0.5.1):
- https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite
- https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite
- https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.tflite

## Barge-in VAD: Silero VAD (model from upstream, wrapper is original code)

`app/src/main/assets/silero_vad.onnx` + `app/src/main/kotlin/com/teya/agent/voice/vad/SileroVad.kt`
detect real user speech while Teya is thinking/speaking, so she can be interrupted (barge-in).

| Component | Source | License | Commercial use |
|---|---|---|---|
| `silero_vad.onnx` (model weights) | [snakers4/silero-vad](https://github.com/snakers4/silero-vad), `src/silero_vad/data/silero_vad.onnx` | MIT, Copyright 2020-present Silero Team | ✅ Yes |
| `SileroVad.kt` (Kotlin wrapper) | Original implementation — see below | N/A (our code) | ✅ Yes |

The model is downloaded directly from the primary Silero VAD repo (not a third-party mirror), git
blob SHA `80c5592ef1f4c9ede3e357bbd02eb863358a6a9d`, MIT, no commercial-use restriction (unlike the
wake-word classifier above). Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`.

`SileroVad.kt` is our own code, not a port of any third-party Android wrapper. It implements the
streaming algorithm Silero documents in its own reference code
(`src/silero_vad/utils_vad.py`'s `OnnxWrapper`/`VADIterator`), read directly off that repo and
verified against the model's actual ONNX graph (`input`/`state`/`sr` inputs, `output`/state
outputs) — a fixed-config version taking Teya's specific need (16kHz, 512-sample frames, one
detector instance per armed barge-in window) rather than a general-purpose multi-backend library.

## Per-speaker voice ID: CAM++ (model from upstream, wrapper + fbank extractor are original code)

`app/src/main/assets/speaker_embedding.onnx` +
`app/src/main/kotlin/com/teya/agent/voice/speaker/{Fbank,CamPlusPlusSpeakerEmbedder,SpeakerEmbedder}.kt`
turn a short recording into a voiceprint vector, so Teya can guess which household member is
likely speaking (a soft, non-authoritative disambiguation signal — see `docs/roadmap.md` →
Household setup & personalization).

| Component | Source | License | Commercial use |
|---|---|---|---|
| `speaker_embedding.onnx` (model weights) | [iic/speech_campplus_sv_en_voxceleb_16k](https://www.modelscope.cn/models/iic/speech_campplus_sv_en_voxceleb_16k) (ModelScope, Alibaba 3D-Speaker) — downloaded via [k2-fsa/sherpa-onnx's speaker-recognition-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models) as `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, SHA256 `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b` | Apache-2.0 | ✅ Yes (see VoxCeleb note below) |
| `Fbank.kt` (Kotlin fbank extractor) | Original implementation — see below | N/A (our code) | ✅ Yes |
| `CamPlusPlusSpeakerEmbedder.kt` / `SpeakerEmbedder.kt` (Kotlin ONNX wrapper + interface) | Original implementation — see below | N/A (our code) | ✅ Yes |

**VoxCeleb training-data caveat**: this checkpoint is trained partly on VoxCeleb, whose raw dataset
access is gated to non-commercial research by Oxford VGG — separate from the Apache-2.0 license
3D-Speaker tags the resulting weights with. Whether that access restriction legally flows through
to derived model weights is unsettled (no clean precedent either way); Apache-2.0 is the best
signal available from the maintainer, not a legal guarantee. Full trail: `docs/experiments.md` →
"Problem: per-speaker voice ID".

Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` — the same dependency already
vendored for Silero VAD, not a second runtime. **A prebuilt AAR from k2-fsa/sherpa-onnx was tried
first** (bundles a ready feature-extraction + embedding API) but was reverted after a live,
on-device crash: its bundled ONNX Runtime (1.27.0) is binary-incompatible with the one Silero VAD's
Java bindings are built against (1.22.0), and no sherpa-onnx release has ever bundled exactly
1.22.0. Full trail: `docs/experiments.md`.

`Fbank.kt` is our own code, implementing Kaldi's published fbank algorithm
(`feature-window.cc`/`mel-computations.cc`) directly — not a port of any third-party wrapper.
Config (16kHz, 25ms/10ms framing, povey window, dither=0, preemph=0.97, remove-DC, 80 mel bins
20Hz-7600Hz, global-mean normalization) was reverse-engineered from sherpa-onnx's
`FeatureExtractorConfig` defaults and the model's own ONNX metadata (`feature_normalize_type=
global-mean`), then verified numerically against the real Python `kaldi_native_fbank` library's
output on an identical synthetic signal (see `app/src/test/kotlin/.../speaker/FbankTest.kt` +
`app/src/test/resources/fbank_fixture.json`) — this is the one part of the feature with genuine
hand-written-DSP correctness risk, so it's the one part with a numeric regression test against a
known-correct reference implementation, not just a compile check.

`CamPlusPlusSpeakerEmbedder.kt` mirrors `SileroVad.kt`'s pattern (asset-loaded ONNX Runtime
session, verified directly against the model's own ONNX graph: input `x` float32 `[1, T, 80]`,
output `embedding` float32 `[1, 512]`) behind a small `SpeakerEmbedder` interface, so the concrete
backend can be swapped later without touching any caller.

## Wake-word feature extraction: TFLite Micro "microfrontend" (vendored native C, JNI wrapper is adapted third-party code)

`app/src/main/cpp/microfrontend/` vendors the native audio feature extractor our custom
**"hey teya"** wake-word model (trained via Google's microWakeWord pipeline) requires at inference
time: a fixed-point mel-filterbank feature extractor, implemented as native C in TensorFlow Lite
Micro, not expressible as a portable TFLite graph (no custom-op interpreter path exists for it on
Android/Kotlin). This is the first native (`app/src/main/cpp/`) module in the app.

| Component | Source | License | Commercial use |
|---|---|---|---|
| `tflite-micro/` (microfrontend lib, `tensorflow/lite/experimental/microfrontend/lib/`) | [tensorflow/tflite-micro](https://github.com/tensorflow/tflite-micro), commit `2747abd5c82a95fb1624106a946fc671c31f16e8` | Apache-2.0 | ✅ Yes |
| `kissfft/` (FFT dependency, compiled internally by `kiss_fft_int16.cc`) | [mborgerding/kissfft](https://github.com/mborgerding/kissfft), tag `131.2.0` (commit `7bce4153c6bc8aba2db0e889e576f9d00505cbe1`) | BSD-3-Clause | ✅ Yes |
| `MicroFrontendWrapper.cpp`/`.h`, `MicroFrontend_jni.cpp` (JNI wrapper) | Adapted from [home-assistant/android#6312](https://github.com/home-assistant/android/pull/6312)'s Apache-2.0-licensed equivalents, targeting `com.teya.agent.voice.MicroFrontend` | Apache-2.0 | ✅ Yes |

Both pinned commits match home-assistant/android's own `microfrontend/src/main/cpp/CMakeLists.txt`
pins exactly (their module — shipped, working prior art — solves the same JNI-wrapping problem;
also credited there: [brownard/Ava](https://github.com/brownard/Ava) as independent prior art).
Unlike HA's build-time `FetchContent` fetch, this project vendors the actual pinned source into the
repo tree (matching the WebRTC AEC3 precedent below) since the build is `--offline`. Full
provenance, exact file list, and what's deliberately not vendored (`*_io.*` host-tool files, kissfft's
N-dimensional/C++-template/KFC variants, etc.): `app/src/main/cpp/microfrontend/VENDORING.md`.

Both licenses are permissive with no non-commercial/share-alike restriction — unlike the
CC-BY-NC-SA `hey_jarvis_v0.1.tflite` classifier this module exists to replace (see the
"Commercial-use blocker" section above). No CMakeLists.txt/Gradle NDK plumbing or Kotlin wrapper
exists yet — this entry covers vendored native source only; those are separate, later steps.

## Native echo cancellation: WebRTC AEC3 — abandoned, removed

An earlier phase vendored Google WebRTC's Acoustic Echo Canceller v3 (AEC3) under
`app/src/main/cpp/third_party/webrtc/` and drove it via a `NativeAec3.kt` JNI wrapper, built as a
native `.so` via NDK/CMake (pinned commit `99de2d09036e61b72e4e6bba3ab09fedc40d18fe`, BSD-3-Clause,
plus an Ooura FFT dependency and an unvendored Abseil dependency). Commit `2222c9c` ("Drop
NativeAec3; WebView AEC becomes the sole default barge-in path") removed all of it — the
`cpp/` tree, the Kotlin wrapper, and the NDK/CMake build config — after live testing showed
Chromium's own `getUserMedia({echoCancellation:true})` via `voice/aec/WebViewAecHost.kt` performed
decisively better. **No native code or vendored WebRTC source remains in the app.** This section is
kept only as provenance for why that approach was tried and dropped; see `docs/experiments.md` for
the comparison.
