# CLAUDE.md — Teya

Family voice assistant on a spare Android phone (wall device). **Read [docs/roadmap.md](docs/roadmap.md) first** for
current status + what's next. Vision: [README.md](README.md); design decisions: [ARCHITECTURE.md](ARCHITECTURE.md).
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
- `voice/VoicePipeline.kt` — VAD mic capture (`listenForCommand`), TTS playback (streaming `AudioTrack` int16 +
  mp3 fallback), `pauseWakeWord`/`resumeWakeWord`.
- `voice/WakeWordEngine.kt` — openWakeWord 3-model chain (assets: melspectrogram/embedding/hey_jarvis tflite);
  `NoiseSuppressor` + software `INPUT_GAIN`, `THRESHOLD`, `PATIENCE`.
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
- Wake word: generic `hey_jarvis` is near/mid-field only + CC-BY-NC (dev-only). Tuning knobs in `WakeWordEngine`
  (`THRESHOLD`, `INPUT_GAIN`, `PATIENCE`). Durable far-field/commercial fix = custom "Hey Teya" model. Tap-to-talk is the reliable path.
- Commit trailers: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` + `Claude-Session:` (see `git log`).
