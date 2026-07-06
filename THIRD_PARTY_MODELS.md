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
