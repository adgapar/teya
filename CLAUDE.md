# CLAUDE.md — Teya

Family voice assistant on a spare Android phone (wall device). **Read [docs/roadmap.md](docs/roadmap.md) first** for
current status + what's next. Vision + architecture: [README.md](README.md).
Open-ended investigations (e.g. barge-in/AEC) keep a separate experiment trail in
[docs/experiments.md](docs/experiments.md) — what's been tried, what worked/didn't, what's being
tested now — so roadmap.md can stay goal-level.

## Architecture

On-device Android app (the "harness") + Mistral cloud (the "brain"). Voice loop:
**wake word / tap → STT → Mistral LLM (tool calling) → TTS**, conversational (multi-turn, history, ends on silence).

Key files:
- `harness/HarnessService.kt` — the agent loop. `onTrigger()` → `runConversation()` (multi-turn, re-entrancy guard,
  conversation history), `handleToolCall()`, foreground service, UI state broadcasts.
- `brain/MistralClient.kt` — Mistral provider only. `processText(List<ChatMessage>)` (chat + tools),
  `transcribe()` (Voxtral STT), `synthesizeSpeech()` (mp3) + `streamSpeechPcm()` (streaming TTS), `warmUp()`.
  Interface + types in `brain/BrainClient.kt`, wire types in `brain/MistralModels.kt`.
- `persona/` — **where the agent's identity & capabilities live** (kept out of the provider):
  `TeyaPersona` (system prompt), `AgentTools` (tool specs), `ToolSpec`.
- `voice/VoicePipeline.kt` — VAD mic capture (`listenForCommand`), TTS playback, `pauseWakeWord`/`resumeWakeWord`,
  barge-in detection. Render/capture both go through `voice/aec/WebViewAecHost.kt` (see below) when it's active —
  continuous mid-sentence barge-in with real echo cancellation; falls back automatically to plain `AudioTrack`
  playback + gap-gated capture (listens only between sentences, no echo cancellation) if the host fails to start.
- `voice/aec/WebViewAecHost.kt` — session-scoped WebView hosting Chromium's own `getUserMedia({echoCancellation:true})`
  for barge-in's real AEC. Hosted via `WindowManager.addView(TYPE_APPLICATION_OVERLAY)`, not an Activity, so it
  runs with `HarnessService` regardless of what's on screen. Bridge page: `assets/aec_bridge.html`.
- `voice/WakeWordEngine.kt` — microWakeWord: our own `hey_teya.tflite` classifier fed by
  `voice/MicroFrontend.kt` (JNI wrapper around the vendored native `app/src/main/cpp/microfrontend/`
  feature extractor); `NoiseSuppressor` + software input gain/threshold/patience (`ConfigManager`).
- `safety/` — Room contact allowlist (call safety). `telephony/` — dialer/actuator (call feature).
- `MainActivity` (orb + dev overlay), `SetupActivity` (LAUNCHER; API-key entry), `SettingsActivity`.

### Adding a tool (the next phase)
1. Add a `ToolSpec` to `AgentTools.all`. 2. Handle it in `HarnessService.handleToolCall()`. 3. Mention it in `TeyaPersona`.

## Config / models
- Mistral API key: entered in-app (EncryptedSharedPreferences via `ConfigManager`); also in **`.env`** (gitignored,
  `MISTRAL_API_KEY`) for CLI/curl.
- Models: chat `mistral-small-latest`; STT `voxtral-mini-latest`; TTS `voxtral-mini-tts-latest`, voice `fr_marie_happy`
  (catalog: `docs/mistral-voices.md`).

## Build / install / test  (device is on wireless adb)
- Install: `./gradlew installDebug --offline`  · Compile-check only: `./gradlew assembleDebug --offline`
- adb drops off periodically → `No connected devices!`; ask the user to reconnect (USB / re-toggle wireless debugging).
- Triggering: the service is non-exported (`am start-foreground-service` is refused). Drive the orb via
  `adb shell input tap <cx> <cy>` (needs app foreground + unlocked); otherwise **ask the user to tap/speak, then read logs**.
- Logs: `adb logcat -d -s HarnessService VoicePipeline MistralClient WakeWordEngine` (filter out the big voices JSON).

## Gotchas
- **Ktor JSON uses `encodeDefaults=false`** → request models must not rely on default field values (they get omitted from the body).
- Mistral `/audio/speech` returns base64 audio inside JSON (not raw bytes); streaming = SSE `speech.audio.delta` (base64 float32 PCM).
- Never commit `.env`; `app/build/` is gitignored.
- Wake word: our own trained microWakeWord model, `hey_teya.tflite` (unrestricted license — see
  `THIRD_PARTY_MODELS.md`), via a vendored native `app/src/main/cpp/microfrontend/` module — the app's
  only native/NDK code. Tuning knobs live in `ConfigManager`/Admin's Voice tuning panel
  (`wakeWordThreshold`/`wakeWordPatience`, defaults 0.53/3 from `hey_teya.json`'s calibration), not
  hardcoded constants. **Config toggles here need an actual process kill to take effect** —
  `HarnessService` is a foreground `START_STICKY` service, so swiping the app from recents does not
  restart it; use `adb shell am force-stop com.teya.agent` then relaunch. Tap-to-talk remains the
  reliable fallback path.
- **WebView AEC (barge-in) needs Android 8.0+ (API 26) — this is `minSdk`, not headroom.**
  `WebViewAecHost` hosts its overlay via `TYPE_APPLICATION_OVERLAY` (introduced in API 26), no
  fallback for older devices. Also needs `SYSTEM_ALERT_WINDOW` ("draw over other apps"), which
  Android makes the user grant manually via Settings — not a standard runtime permission dialog —
  so it's a one-time setup step on a fresh install; until granted, barge-in silently falls back to
  gap-gated with no echo cancellation (`WebViewAecHost.isActive()` reports `false`, nothing crashes).
  Validated on one device only (Samsung A34, MediaTek chipset) — a real regression this session
  traced to the device's own platform `AcousticEchoCanceler` interfering with Chromium's separate
  `getUserMedia` AEC (see `docs/experiments.md`) suggests this interaction may be chipset-specific;
  a different phone model could need its own tuning pass.
- Commit trailers: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` + `Claude-Session:` (see `git log`).
