---
date: 2026-07-08T00:00:00Z
topic: "Barge-in interruption: VAD options research"
tags: [voice, barge-in, vad, wake-word, mistral, android-vad]
status: in-progress
---

# Barge-in interruption: VAD options research

_2026-07-08. Context for the barge-in feature in progress (see roadmap.md's "Barge-in interruption
+ mishearing awareness" entry). Captures what was tried, what was learned, and the open decision._

## Problem

Teya can't be interrupted while speaking. First attempt: keep the wake-word engine's mic live
during SPEAKING/THINKING and run a plain RMS-energy check on the raw chunks, gated by an `armed`
flag. **Live-tested on-device and it never fired once**, even on deliberate loud speech, across a
38-second reply — no diagnostic logging existed to tell whether the threshold (1200, untested
guess) was wrong or something was structurally broken. Root problem: RMS loudness is not "is this
speech," it's just "is this loud," with no adaptation to noise floor.

## Attempt 2 (implemented, committed to code, not yet live-verified after rework)

Replaced RMS with **Mistral Voxtral Realtime** — a genuine streaming STT over WebSocket, confirmed
via reading `mistralai/client-python`'s actual source (the hosted docs only show a high-level
Python SDK call, no raw wire-protocol doc):

- `wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=voxtral-mini-transcribe-realtime-2602`,
  `Authorization: Bearer <key>` header (same auth as the rest of the app).
- Handshake: connect → wait for `{"type":"session.created",...}` → send
  `{"type":"session.update","session":{"audio_format":{"encoding":"pcm_s16le","sample_rate":16000},"target_streaming_delay_ms":240}}`.
- Stream audio: `{"type":"input_audio.append","audio":"<base64 pcm_s16le>"}` per chunk.
- End: `{"type":"input_audio.flush"}` then `{"type":"input_audio.end"}`.
- First `transcription.text.delta` event = real recognized speech = interrupt signal.

Implemented in `MistralClient.detectBargeInSpeech()` (brain/MistralClient.kt), wire types in
brain/MistralModels.kt, wired through `WakeWordEngine` (forwards raw armed chunks via
`onArmedAudioChunk` callback instead of doing local RMS) and `VoicePipeline` (owns a
`CoroutineScope` + `Channel<ByteArray>` per armed window, feeds `detectBargeInSpeech`). Added
`ktor-client-websockets` dependency (required one online Gradle resolve — this project builds
`--offline`, artifact wasn't cached). Compiles; installed on device; **not yet live-tested**
end-to-end after the rework (device was locked when we got to testing).

**Known limitation, called out to the user before building it**: this doesn't solve self-echo.
If Teya's own voice leaks through the speaker into the mic clearly enough to be intelligible,
Voxtral *will* transcribe it as real words → false self-interrupt. `AcousticEchoCanceler` is
enabled on the shared capture session as the only defense; its effectiveness is unverified on this
specific device (Samsung A34, wall-mounted). This migration fixes *recall* (a real ASR reliably
detects genuine speech, unlike the RMS approach which detected nothing) — it does not fix the
underlying self-echo risk, which is a hardware/AEC question, not a detector-choice question.

Also a real cost: continuous audio streaming to the cloud while Teya is thinking/speaking, ~$0.006/min
(trivial), but a genuine network dependency for a feature that ideally reacts in well under 300ms.

## Attempt 3 (current ask): local VAD research (WebRTC / Silero / Yamnet)

User asked to research vendoring a local VAD by copying/reimplementing code directly (not adding a
prebuilt Maven dependency), evaluating WebRTC VAD, Silero VAD, and Yamnet as candidates. Findings
below are paraphrased/generalized from that research rather than tied to any one third-party
wrapper repo — none of that wrapper code was ultimately used (see "Decision" further down: Silero
VAD was implemented as original code directly against Silero's own upstream model/reference code).

All three candidate approaches are permissively licensed (BSD/MIT-family) at the underlying
algorithm/model level. The wrapper research surveyed three independent backends with no shared
Kotlin interface between them (structurally similar by convention, not an actual `interface`):
one per algorithm (WebRTC-style, Silero-style, Yamnet-style), each effectively its own module.

Common per-backend contract:
```kotlin
fun isSpeech(audioData: ShortArray | ByteArray | FloatArray): Boolean  // one call per fixed-size frame
fun close()  // Closeable
```
Both WebRTC's and Silero's `isSpeech()` already do hysteresis internally (`isContinuousSpeech()`,
identical code copy-pasted between the two modules) — tracks consecutive speech/silence frames
against `speechDurationMs`/`silenceDurationMs` before flipping state. This is exactly the
false-positive debouncing the RMS attempt lacked.

### WebRTC VAD — native (JNI), not pure Kotlin

This was the biggest surprise: **it's Google's actual 2013 WebRTC C VAD code (`common_audio/vad`),
compiled via `ndk-build`**, not a Kotlin port. The VAD-specific C is small (~52KB across
`vad_core.c`/`vad_gmm.c`/`vad_sp.c`/`vad_filterbank.c`), but it depends on WebRTC's shared
fixed-point signal-processing library (~30 more `.c`/`.S` files, incl. ARM-NEON/SSE2 asm variants),
built via `Android.mk` per-ABI. **Vendoring this means adding a native/NDK build step to Teya**,
which is currently pure-JVM+TFLite — a real toolchain addition, not a drop-in file copy.

API: `Vad.builder().setSampleRate(SampleRate.SAMPLE_RATE_16K).setFrameSize(FrameSize.FRAME_SIZE_320)
.setMode(Mode.VERY_AGGRESSIVE).setSilenceDurationMs(300).setSpeechDurationMs(50).build()`. Frame
sizes @16kHz: 160/320/480 samples (10/20/30ms). Teya's existing 80ms/1280-sample wake-word chunks
split **evenly** into 4×320 or 8×160 — clean, no cross-chunk buffering needed.

### Silero VAD — ONNX Runtime, not TFLite (contradicts the initial assumption)

Assumed this would reuse Teya's existing TFLite/`Interpreter` stack (already used for the
wake-word chain) — **wrong**. Silero here uses `onnxruntime-android:1.22.0`, a second, separate ML
runtime with its own native `.so`. Model: `silero_vad.onnx`, ~1.76MB, bundled as an asset.

It's a stateful recurrent net (LSTM-style `h`/`c` hidden state, `[2,1,64]` reshape, carried across
calls) — frames **must** be fed in strict order on one persistent instance, unlike the wake-word
classifier's effectively-stateless-per-chunk design (it does keep its own mel/embedding rolling
buffers, but tolerates gaps better than an RNN hidden state would). Output: single scalar float
confidence, same shape as the wake-word model's output. Threshold by `Mode`: NORMAL=0.5,
AGGRESSIVE=0.8, VERY_AGGRESSIVE=0.95 (note: `LOW_BITRATE` isn't handled in upstream code, falls
through to threshold 0 — a real bug in the library worth knowing if vendored).

Frame sizes @16kHz: 512/1024/1536 samples (32/64/96ms) — **does not** evenly divide Teya's
1280-sample chunks (1280/512=2.5, 1280/1024=1.25), so a rolling carry-over buffer is required, and
timing bugs here are more consequential than for WebRTC given the RNN state.

### Yamnet VAD — the one that actually matches the existing TFLite pattern

Not in the original ask, but the report flagged it: `yamnet.tflite` (~3.94MB), a general 521-class
audio event classifier (not VAD-specific) with a "Speech" class — `classifyAudio("Speech", data)`.
Would slot into the existing `Interpreter.run()` pattern most directly of the three, at the cost of
a much bigger model and no VAD-specific tuning.

### None of the three solve the echo problem

Straight from the research: none of WebRTC/Silero/Yamnet have any built-in "ignore audio that's an
echo of what we just played" logic — AEC is orthogonal to VAD choice. Whichever is picked still
sits on top of `AcousticEchoCanceler` (already enabled) as the only defense against self-triggering
on Teya's own voice. This is the same caveat as the Mistral-realtime approach — switching detectors
does not change this constraint, it only changes *false-positive-if-echo-leaks-through* from
"semantic mistranscription" to "GMM/RNN scoring the residual echo as speech."

## Open decision (as of writing, not yet resolved)

Four live options on the table:
1. **Mistral Voxtral Realtime** (implemented, uncommitted rework, not live-verified) — semantic,
   network-dependent, adds `ktor-client-websockets`.
2. **WebRTC VAD vendored** — local, fast, clean frame math, but adds an NDK/native build step;
   just a better-tuned energy/GMM check, same fundamental class as the failed RMS attempt.
3. **Silero VAD vendored** — local, better accuracy per README, adds ONNX Runtime as a second ML
   runtime, frame-buffering complexity from the 1280-vs-512/1024 mismatch, stateful RNN care needed.
4. **Yamnet vendored** — reuses existing TFLite infra, but large general-purpose model, not
   VAD-specific tuning.
5. **Hybrid** (not yet discussed with user) — a cheap local VAD as a fast first-pass gate, only
   opening the Mistral realtime WebSocket when local VAD fires, cutting cost/false-positive-from-
   pure-loudness while keeping the network path as a semantic confirmation layer.

Next step: present this to the user, get a decision on which to vendor (or keep Mistral-only, or
hybrid), then implement + live-test on-device (barge-in has not been successfully verified live in
any form yet — the RMS attempt failed, the Mistral rework hasn't been re-tested since the device
was locked when we reached that point).

## Status update — decision made, live test in progress

User chose: **test Mistral Realtime first** before touching WebRTC/Silero/Yamnet (none of which
would be simpler — see tradeoffs above). Device unlocked, `MainActivity` confirmed foreground via
`adb shell dumpsys window | grep mCurrentFocus`. Triggered a conversation via
`adb shell input tap 1170 540` (landscape, screen 2340x1080) and started a background logcat
capture to `/private/tmp/.../scratchpad/bargein_test2.log` (filters: `HarnessService:D
VoicePipeline:D MistralClient:D WakeWordEngine:D AndroidRuntime:E OkHttp:D`). Asked the user to:
(1) prompt her with something that makes her talk for a while, (2) talk over her mid-reply.

**Not yet resolved** — waiting on the user's live-test result and the log capture to review. When
resuming: read that log file (or re-run the same tap+logcat-capture recipe — device env needs
`export ANDROID_HOME="$HOME/Library/Android/sdk"; export PATH="$ANDROID_HOME/platform-tools:$PATH"`
since `adb` isn't on PATH by default in this shell) and look for:
- `MistralClient: Barge-in: recognized speech ("...")` — success signal.
- `HarnessService: Barge-in — interrupting Teya` — confirms the interrupt fired end-to-end.
- Absence of both after the user talked over her → still broken, check for WS connect/auth errors
  (`Realtime handshake error`, `Realtime barge-in session ended`) in the same log.

Known pre-existing environment gotchas hit this session: `timeout`/`gtimeout` don't exist on this
macOS box (don't wrap background adb logcat captures in `timeout`, just background them plain and
read the file, or kill the process by name later); wireless adb devices sometimes need
`am force-stop com.teya.agent` + relaunch to pick up a fresh `installDebug`, which can leave the
device on its lockscreen — don't blind-swipe repeatedly (risk of triggering a biometric lockout),
ask the user to unlock physically instead. Also: `installDebug` over a running instance kicks the
foreground activity back to the home launcher — always re-`am start -n com.teya.agent/.SetupActivity`
after every reinstall before tapping the orb.

## Status update 2 — root cause found, fix applied, re-test pending

User chose "test Mistral Realtime first" (over vendoring WebRTC/Silero/Yamnet from
gkonovalov/android-vad — see full tradeoffs above; none of those are simpler either, so the
decision was to validate what's already built before adding a second detector).

**Live-tested 3 times with escalating diagnostics** (recipe: `export ANDROID_HOME="$HOME/Library/Android/sdk"; export PATH="$ANDROID_HOME/platform-tools:$PATH"`, then `adb shell am start -n com.teya.agent/.SetupActivity` (device kicks to launcher after every `installDebug`), `adb logcat -c`, `adb shell input tap 1170 540` (screen 2340x1080 landscape, orb is centered), then background a filtered `adb logcat -s HarnessService:D VoicePipeline:D MistralClient:D WakeWordEngine:D AndroidRuntime:E > <scratchpad>/log 2>&1` and ask the user to talk to Teya + talk over her):

1. First pass: connection worked (`session.created`, `session.updated` both logged every turn),
   but **zero transcription events ever came back**, not even from Teya's own clearly-spoken
   Spanish sentences across a 19-second reply. No evidence either way on whether audio was
   actually being sent (the one "sent N chunks" log was placed after the send loop, which never
   completes normally — the job gets *cancelled* when the turn ends, not drained — so that log
   never fired, telling us nothing).
2. Added periodic (every 25 chunks, ~2s) counters at both hops: `VoicePipeline.forwardArmedChunk`
   (mic → channel) and `MistralClient.detectBargeInSpeech`'s sender loop (channel → WebSocket).
   Re-tested: **counts matched and climbed cleanly every time** (25/50/75/100+ chunks, several
   turns) — so audio is genuinely, reliably reaching Mistral. Still zero transcription events.
3. **Root cause hypothesis**: this device logs `AGC NOT available on this device` every single
   `WakeWordEngine` start (confirmed in every log capture). The wake-word TFLite model already
   compensates for this with a 6x software gain (`WakeWordEngine.INPUT_GAIN`) applied before
   feeding its classifier — but that gain is applied to a *local copy* inside `processChunk`, never
   to the raw `ShortArray` itself, so `onArmedAudioChunk` (and therefore Mistral) was receiving
   **ungained, likely too-quiet-to-transcribe raw samples**. This fully explains detecting nothing
   even for Teya's own reasonably loud voice.

**Fix applied**: `VoicePipeline.forwardArmedChunk` applies a 6x gain (`BARGE_IN_GAIN`, per-sample,
clamped to Short range) before base64/sending to Mistral. **Retested — did not help.** Chunk
delivery still confirmed solid (200+ chunks, matching forwarded/sent counters), zero transcription
events, including across a full ~16s reply where Teya clearly narrated a long story out loud
(should be trivially transcribable if the audio were reaching Mistral in usable form). Also newly
observed: one session died mid-turn with `Connection reset` around chunk 200 (~16s) — no
auto-reconnect exists, so barge-in silently goes dark for the rest of a long reply if this happens.

**Second experiment**: temporarily forced `NoiseSuppressor.setEnabled(false)` on the shared
WakeWordEngine capture session (`enableAudioEffects` in WakeWordEngine.kt) to test whether it was
scrubbing real speech. Retested — **still zero events** across 200+ chunks. Also added one-shot
payload dumps (`Barge-in: chunk #N raw bytes=... b64Len=... b64Head=...`) at chunk 0 and chunk 100
of each session to eyeball for an encoding bug. Chunk 0's base64 decoded to all-zero bytes
(`AAAA...`) — **ambiguous, likely just `AudioRecord` post-restart warm-up silence** (barge-in arms
right after `resumeWakeWord()` restarts a fresh `AudioRecord`), not necessarily a real bug. Never
got to check chunk 100 (mid-stream, ~8s in) — the natural next data point, since if a chunk 8
seconds into continuous audio is *also* all-zero, that would conclusively point at the capture
itself (not warm-up) carrying no signal, independent of Mistral/gain/noise-suppression entirely.

Started a third experiment (also forcing `AcousticEchoCanceler.setEnabled(false)` — plausible
culprit since AEC is specifically active *because* capture happens while Teya's own speaker plays,
and an overzealous AEC could over-subtract and zero real speech, not just true echo) but **the user
stopped here and decided to abandon the Mistral Realtime approach** rather than keep iterating.
**Both temporary experiment toggles (NoiseSuppressor, AEC) were reverted back to enabled** before
moving on — see git diff / WakeWordEngine.kt, should already be back to normal by the time this is
read. The one-shot chunk-payload diagnostic logging (`MistralClient.detectBargeInSpeech`, `sent==0
|| sent==100`) and the periodic chunk-count counters (both `VoicePipeline.forwardArmedChunk` and
the sender loop) were **not** reverted — harmless diagnostic-only logging, left in place since it's
useful groundwork if the Mistral path is ever revisited (see "what's left of Mistral path" below).

## Decision: abandon Mistral Realtime, vendor a local VAD instead

After the RMS attempt, the Mistral Realtime attempt (2 fix rounds: gain, then NoiseSuppressor-off),
neither produced a single detection — not even on Teya's own loud, clear, multi-sentence TTS
speech, which should be the easiest possible case. The remaining untested hypothesis (AEC
over-subtracting) was reasonable but the user chose to stop chasing this path and pivot to a local
VAD (from the gkonovalov/android-vad research above) instead of a fourth round-trip.

**Open question, unresolved**: we never definitively learned *why* Mistral Realtime detected
nothing. It could be device/AEC-specific (this exact hardware + audio routing), a genuine protocol
misunderstanding on my part (we always killed sessions by cancellation, never a clean
flush+end — worth knowing if picked back up), or something about `target_streaming_delay_ms=240`
interacting badly with a stream that's mostly one continuous voice (Teya's) rather than natural
human pauses. If a future session revisits the cloud approach, start from the mid-stream
(`sent==100`) chunk-content check that got interrupted, since that's the one experiment that would
have been genuinely conclusive either way.

## What's left of the Mistral Realtime code (as of the pivot)

Still in the tree, compiling, but **unused going forward** unless explicitly revived:
- `app/build.gradle.kts` / `gradle/libs.versions.toml`: `ktor-client-websockets` dependency added
  (required one online Gradle resolve, now cached — safe to leave, or remove if truly abandoning).
- `brain/KtorClientFactory.kt`: `install(WebSockets)` on the shared HttpClient.
- `brain/MistralModels.kt`: `RealtimeAudioFormat`, `RealtimeSessionUpdatePayload`,
  `RealtimeSessionUpdateMessage`, `RealtimeInputAudioAppend`, `RealtimeInputAudioFlush`,
  `RealtimeInputAudioEnd`, `RealtimeEventEnvelope` — the reverse-engineered wire types (still
  useful reference if the wire protocol section above is ever needed again).
- `brain/MistralClient.kt`: `detectBargeInSpeech()`, `awaitSessionCreated()`, `sendRealtimeJson()`,
  plus `realtimeModel`/`realtimeTargetDelayMs` fields.
- `voice/WakeWordEngine.kt`: `onArmedAudioChunk` callback param (renamed from an earlier
  `onBargeIn` — this part IS still needed for local VAD too, just needs a different consumer).
- `voice/VoicePipeline.kt`: `bargeInScope`/`bargeInChannel`/`bargeInJob`, `setBargeInArmed()`,
  `forwardArmedChunk()` (now with the 6x gain and diagnostic counters) — **this plumbing is mostly
  reusable for local VAD**: swap what `forwardArmedChunk` does with the audio (currently: base64 +
  send to a Mistral WebSocket channel) for a direct local `isSpeech()` call, and drop the
  channel/coroutine/WebSocket machinery entirely (a local VAD call is synchronous and cheap, no
  need for the async channel hand-off this network approach required).
- `harness/HarnessService.kt`: `onBargeIn()`, `activeTurnJob`, the arm/disarm calls around
  `respond()` in `runConversation()` — **all of this stays as-is**, it's provider-agnostic (doesn't
  know or care whether detection is local or cloud).

## Next: vendor a local VAD (not yet started)

Plan, informed by the earlier android-vad research (see above):
- **Silero VAD is the more practical choice** for this project specifically, despite WebRTC's
  simpler/smaller core: WebRTC VAD requires adding a whole native/NDK build step (Android.mk,
  ~35 C/asm files, ABI filters) to an app that has never had one — a much bigger, riskier lift than
  adding one more prebuilt-binary Gradle dependency. Silero "just" needs `onnxruntime-android` (a
  real Maven artifact, prebuilt native `.so` inside the AAR, no NDK build of our own) + vendoring
  the small Kotlin wrapper class + bundling `silero_vad.onnx` (~1.76MB) as an asset.
- Needs one more online Gradle resolve (same pattern as `ktor-client-websockets` this session) to
  cache `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` before `--offline` builds work again.
- Frame-size mismatch: Silero wants 512 or 1024-sample frames @16kHz; Teya's existing capture
  chunks are 1280 samples (80ms) — needs a small rolling carry-over buffer in `WakeWordEngine` or
  `VoicePipeline` to reassemble valid Silero-sized windows before calling `isSpeech()`.
- Silero is a **stateful RNN** (`h`/`c` hidden/cell state carried across calls) — one persistent
  `VadSilero`-equivalent instance per armed window (created on arm, `close()`d on disarm), fed
  frames strictly in order. Reset/recreate per turn (matches the existing arm/disarm lifecycle).
- Attribution: MIT (repo) + MIT (Silero weights) — add a note to `THIRD_PARTY_MODELS.md` (already
  exists for the wake-word model, natural place to extend).
- Wire into the SAME `WakeWordEngine.onArmedAudioChunk` → `VoicePipeline.forwardArmedChunk` path
  already built for barge-in — just replace the body of `forwardArmedChunk` (drop the
  channel/coroutine/base64/WebSocket bits, do a direct synchronous `vad.isSpeech(chunk)` call after
  reassembling the frame-size buffer, invoke `onBargeIn()` directly on a positive detection with
  the same hysteresis debouncing the library already provides internally).
- `HarnessService.onBargeIn()`/`activeTurnJob` cancellation logic needs **no changes** — it's
  already detector-agnostic.
- Still true regardless of detector: **no VAD solves the self-echo problem** — whichever is used
  still needs `AcousticEchoCanceler` (already enabled) as the only defense against Teya
  self-triggering on her own voice bleeding through the speaker; this is now doubly relevant since
  we just learned this device's AEC/NoiseSuppressor behavior is not fully understood (see above).

## Status update 3 — Silero VAD implemented and live-tested; self-echo is the real blocker

Silero VAD was built as an **original implementation**, not vendored from gkonovalov/android-vad
(the user explicitly asked to avoid copying that repo's code, to keep the license/provenance
chain limited to Silero's own model+code, even though gkonovalov's repo is MIT and would've been
fine). `voice/vad/SileroVad.kt` reads Silero's own reference implementation
(`src/silero_vad/utils_vad.py`'s `OnnxWrapper`) and calls the model directly via ONNX Runtime.
This surfaced a real correctness issue: the model file gkonovalov's repo bundles uses an **older
interface** (separate `h`/`c` states, plain 512-sample frames); the current primary
`snakers4/silero-vad` model uses a different interface (single combined `state` tensor `[2,1,128]`,
plus a 64-sample "context" from the previous frame prepended to each new 512-sample frame). Model
downloaded directly from `snakers4/silero-vad` (`src/silero_vad/data/silero_vad.onnx`, MIT, git
blob SHA `80c5592ef1f4c9ede3e357bbd02eb863358a6a9d`), not a mirror. Verified the tensor wiring with
a local Python sanity check before ever touching the device: near-zero confidence on true silence,
0.4-0.76 on a synthetic voiced-speech-like harmonic signal.

`VoicePipeline.forwardArmedChunk` reassembles `WakeWordEngine`'s 1280-sample mic chunks into
512-sample Silero frames (rolling carry-over buffer), applies the same 6x software gain
`WakeWordEngine.INPUT_GAIN` uses (no hardware AGC on this device), and calls `isSpeech()`
synchronously right on the mic capture thread — no channel/coroutine/WebSocket machinery needed
since local inference is fast and synchronous. This fully replaced the Mistral-realtime plumbing
(still in the tree, unused — see "what's left of the Mistral Realtime code" above).

**Live-tested, four attempts, in order:**

1. **AEC on (original default) + TTS as `USAGE_MEDIA`**: barge-in VAD confidence flatlined near
   0.001-0.009 for the *entire* duration of every reply, even a full ~50s multi-sentence answer,
   including while the user was actively trying to interrupt. Diagnostic logging (peak confidence
   + peak raw pre-gain amplitude, logged every ~25 chunks) showed the confidence smoothly
   asymptoting to a fixed near-zero value — the signature of a stateful RNN being fed constant
   (near-silent) input repeatedly. **Conclusion: this device's `AcousticEchoCanceler` over-
   subtracts and zeroes real speech captured during playback, not just true echo.** This matches
   the untested hypothesis from the abandoned Mistral-realtime round (see above) — now confirmed
   independently with a completely different detector, which rules out "wrong detector" as the
   explanation once and for all.
2. **AEC off + TTS as `USAGE_MEDIA`**: real speech reliably scored 0.6-0.99 and barge-in fired
   within ~1s. But raising the VAD threshold from 0.6 to 0.85 (to fight ambient-noise
   sensitivity) did **not** help — Teya's own voice, leaking into the mic with AEC off, scored
   just as high (0.6-0.99) as genuine user speech on *every single reply*, no matter how short
   ("Yes?", "No entendí bien."). This produced a **100% self-trigger feedback loop**: barge-in
   fires on her own voice → turn cancelled → immediately starts "listening for command" → STT
   transcribes residual audio as garbage ("charged for the conspiracy to the fellow", "sterlado.",
   "Лекарство.", "你吃饭了") → treated as a real command → repeats. **Conclusion: no VAD confidence
   threshold can fix this, structurally — Teya's own voice through the speaker IS real speech to
   a VAD, indistinguishable by audio content alone from a genuine interruption.** This is worse
   than the original "can't interrupt" bug, not just "somewhat too sensitive."
3. **AEC on + TTS as `USAGE_VOICE_COMMUNICATION`** (testing whether Android's AEC needs
   call-style routing to recognize the echo reference): broke audio output entirely — "I don't
   hear her at all, only see completion text." `USAGE_VOICE_COMMUNICATION` silently rerouted
   playback to the earpiece speaker instead of the loudspeaker (a known Android quirk). Reverted
   before even confirming whether the AEC fix itself would have worked.
4. **AEC on + TTS as `USAGE_VOICE_COMMUNICATION` + `AudioManager.mode = MODE_IN_COMMUNICATION` +
   forced `isSpeakerphoneOn = true`** (fixing attempt 3's routing): audio broke *again*, worse
   than before — the diagnostic log showed `audioManager.mode` reading back as `MODE_NORMAL` (0)
   immediately after being explicitly set to `MODE_IN_COMMUNICATION`, i.e. **the mode change
   silently no-op'd**. Root cause: this app never declares `android.permission.MODIFY_AUDIO_SETTINGS`
   (confirmed absent from `AndroidManifest.xml`) and/or doesn't hold audio focus, both of which
   `AudioManager.setMode()`/`isSpeakerphoneOn` require to take effect on modern Android. Raw mic
   amplitude during this attempt was exact **`0`** throughout (not just quiet) — worse than
   attempt 1. Reverted fully back to attempt 2's baseline code shape (AEC on, `USAGE_MEDIA`,
   no `AudioManager` manipulation) — the known-safe, audio-actually-works state. Both playback
   paths (`streamToSpeaker`'s `AudioTrack` and `playMp3`'s `MediaPlayer` fallback) and
   `HarnessService`'s call sites were reverted; `VoicePipeline.setConversationAudioMode()` was
   removed entirely (was net-new this session, never worked).

**Current shipped state**: AEC on (the safe default), `USAGE_MEDIA` playback, Silero VAD threshold
0.85 (a real, live-derived data point — genuine speech scored 0.6-0.99 in attempt 2's testing).
Barge-in essentially does not fire in this state (AEC suppresses the real speech it needs to see),
but critically **does not self-trigger** — a strictly safer failure mode than attempt 2's loop.
Diagnostic logging added this session and left in place (harmless, useful for any future
attempt): `VoicePipeline`'s periodic peak-confidence/peak-raw-amplitude log (every ~25 chunks) and
`SileroVad.lastConfidence` (public, diagnostic).

## Why platform AEC keeps failing — and what real products actually do

User pointed out Perplexity's mobile voice mode achieves reliable barge-in; researched why ours
doesn't. Conclusion, and the most important finding of this session: **products with reliable
cross-device barge-in almost certainly bundle their own software AEC (most plausibly WebRTC's
AEC3) rather than trusting the OS's per-device `AcousticEchoCanceler` effect**, precisely because
that effect's quality is whatever the OEM's chip vendor shipped, and varies wildly — our A34
happens to have a bad one that over-subtracts. This isn't us doing something wrong; it's a known
weak point of relying on the platform effect at all. (Unverified from public sources what
Perplexity specifically uses — this is the plausible, industry-standard explanation, not a
confirmed fact.)

Researched real options for bringing our own AEC (approaches summarized below; deliberately not
linking to the specific third-party wrapper repos surveyed — none were used, and the decision below
is to build from WebRTC's own primary source rather than adopt someone else's wrapper):

- **No maintained, drop-in Android library exposes AEC3 via a simple Kotlin API.** What exists is
  either an old prebuilt AAR wrapping the *older, lower-quality* AECM algorithm (last touched
  ~2019-2021, effectively unmaintained), a raw ~2017 compiled snapshot of real AEC3 with zero
  Java/Kotlin surface, or the actual current AEC3 source (`webrtc-audio-processing`, the extracted
  library PulseAudio/PipeWire use for their own AEC) which has **never been ported to Android** —
  "tested on Linux only" per its own docs.
- **Hand-rolled NLMS adaptive filter in Kotlin** — no native dependency at all. Standard textbook
  AEC approach (~200-500 tap FIR filter adapted against the known reference, echo estimate
  subtracted from mic signal). Tractable in a few hundred lines, well-documented in DSP literature.
  Caveat: lacks AEC3/SpeexDSP's double-talk detection and nonlinear echo suppression, so likely
  "good enough to unblock barge-in," not full production quality — echo-tail residue/distortion
  may still cause some false triggers.
- **SpeexDSP via a small custom JNI wrapper** — mature BSD-licensed C API
  (`speex_echo_cancellation(state, mic_frame, ref_frame, out_frame)`), much smaller/simpler build
  surface than WebRTC. Roughly a week of effort (build + JNI glue), better documented than WebRTC.
- **Full WebRTC APM via NDK, built from WebRTC's own primary source** (either the `webrtc-audio-
  processing` extraction or the full Chromium WebRTC tree) — highest quality (real, current AEC3),
  highest effort (a genuine native-porting project: cross-compile + JNI glue), but the one that
  actually matches how production voice assistants solve this rather than relying on Android's
  per-OEM effect. **This is the direction chosen going forward** — see "Decision" below.
- Notable real-world data point: the Home Assistant Voice ecosystem gives up on software AEC
  entirely on commodity hardware and recommends dedicated mic-array hardware with a hardware AEC
  chip instead, explicitly because software-only AEC on generic hardware "cannot match" hardware
  AEC reliability. This validates that this session's struggle is a recognized hard problem, not a
  sign of doing something wrong.

## Open decision (as of writing, current session)

Barge-in shipped in the safe-but-inert state (no self-trigger, but also no real detection) before
this decision point. User's priority was explicit: a working interrupt matters regardless of how
long it takes to get there.

## Decision: gap-gated barge-in shipped now; a real WebRTC AEC3 build is the follow-up

**Shipped (implemented, live-tested):** barge-in now only listens in the gaps between TTS
sentences, never while our own `AudioTrack`/`MediaPlayer` is actively playing —
`VoicePipeline.forwardArmedChunk` gates on `currentTrack != null || currentMediaPlayer != null` and
skips VAD processing entirely while true. This is structural, not threshold-based: there's no echo
to confuse with real speech when nothing is playing, so it can't self-trigger by construction.
`HarnessService`'s sentence-by-sentence speaker loop (`respond()`) plays sentences back-to-back
with only a single-digit-millisecond gap between them, too short to react to — so a deliberate
pause was added after each sentence (`BARGE_IN_GAP_MS`) to create a real, catchable listening
window; it's cancelled immediately via `activeTurnJob` the instant barge-in fires, so it costs
nothing when unused. `SileroVad`'s threshold could drop back toward Silero's own recommended 0.5
default (raised to 0.7 defensively, since self-echo is no longer the threshold's job — the gate is)
and `speechDurationMs` debounce was shortened (a first live test at 500ms gap / 100ms debounce
failed to catch a deliberate interrupt attempt; both were loosened — 900ms gap / 50ms debounce —
pending a re-test). Tradeoff, accepted deliberately: can only interrupt between sentences, not
mid-sentence.

Also fixed while testing this: replies were multi-sentence monologues (e.g. a 6-sentence potted
history in response to one question) — `TeyaPersona`'s brevity instruction existed but wasn't
holding for open-ended topics. Strengthened to explicitly frame the interaction as dialogue, not
monologue, applying even to naturally long-form topics (explanations, facts, stories): give one
short piece, then stop and let the person ask for more.

**Follow-up decision, not yet started:** the user wants to move toward a real WebRTC AEC3
implementation — built from WebRTC's own primary source (not a third-party wrapper repo, deprecated
or otherwise), kept small and purpose-built for exactly this app's needs (we already know both the
reference/farend signal and the captured/nearend signal precisely) rather than pulling in the whole
WebRTC codebase. Acknowledged upfront as a genuinely complex undertaking — a real native-porting
project (cross-compile + JNI glue), not a quick add. Once built, this would remove the "only between
sentences" limitation entirely, since it fixes self-echo at the signal level instead of the timing
level. This is a separate, larger piece of work from this session's gap-gated fix — not yet scoped
or started.
