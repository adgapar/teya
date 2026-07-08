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
