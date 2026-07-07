# Teya — Roadmap & Status

Living status doc. Design lives in [ARCHITECTURE.md](../ARCHITECTURE.md); the full findings backlog
is [thoughts/shared/research/2026-07-06-project-audit.md](../thoughts/shared/research/2026-07-06-project-audit.md).
Audit IDs (C1, H2, …) below refer to that file.

_Last updated: 2026-07-07._

## ✅ Done

- Android app + always-on foreground service (`HarnessService`), animated orb (`AgentFace`), dev transcript overlay.
- **Voice loop**, all Mistral, no disk: Voxtral STT → Mistral LLM (tool calling) → Voxtral TTS.
- **TTS working**: correct `voice` field + base64-in-JSON decode; default voice **Marie – Happy** (`fr_marie_happy`).
- **Streaming TTS**: `stream:true` + PCM SSE → `AudioTrack` (int16), ~0.8 s to first word, mp3 fallback.
- **Conversation mode**: multi-turn, bounded history (context), silence timeout, re-entrancy guard (fixes audit C6, M7).
- **Persona extracted** to `com.teya.agent.persona`; capability-style prompt; one-sentence / English-only.
- HTTP timeouts (C5), connection warmup, VAD tuning.
- **Wake-word pipeline implemented & working** (openWakeWord 3-model chain, `hey_jarvis`; threshold tuned to 0.2). ⚠️ *But only near-field (~5 cm from the mic) — the pre-trained model scores this setup too weakly (~0.1–0.43) for room-scale use.*
- Repo hygiene: `.gitignore` build output + `.env`; `.env.example`; voice catalog (`docs/mistral-voices.md`).

## 🔜 Next (recommended order)

1. **Make the call feature actually work** — the product's headline, currently non-functional:
   - ⚠️ **Hardware blocker:** the current dev device has **no SIM / phone number**, so it cannot
     place a cellular call regardless of code. Resolve first: (a) add a SIM+plan to the device, or
     (b) route calls over **data/VoIP** (Twilio, or hand off to WhatsApp/Signal via intent), or
     (c) for now, build + test only the *plumbing* (allowlist + name→number resolution + call intent)
     without a live connection.
   - Populate the allowlist (parent-gated contact UI or DB seed) — **C1**.
   - Become the default dialer (`RoleManager` / `ROLE_DIALER`) or use `ACTION_CALL` for MVP — **C2**.
   - Exact-match single lookup + phone-number validation (no `LIKE` wildcard bypass) — **C3**.
   - Runtime permission recheck at call time — **H11**.
2. **Wake word** — diagnosed: it fires, but **only ~5 cm from the mic** (pre-trained `hey_jarvis`
   scores this setup too weakly at room distance). Not viable as a hands-free home assistant yet.
   Durable fix: **train a custom "Hey Teya" model** on real samples (drop-in classifier swap;
   `hey_jarvis` is also dev-only / non-commercial — see `THIRD_PARTY_MODELS.md`). Note: far-field
   wake-word is genuinely hard — may also need better mic capture / gain / noise handling, and
   possibly a "Hey Teya" trained with far-field + augmented samples. Tap-to-talk remains the
   reliable interaction until then.
3. **Security pass** — no plaintext key fallback (**C4**), `allowBackup=false` (**H1**), gate PII logs behind `BuildConfig.DEBUG` (**H2**).
4. **Resource leaks** — close TFLite interpreters + `HttpClient` in `onDestroy` (**H3/H4**).

## 🏠 Household setup & personalization (agentic onboarding)

A guided, conversational setup that captures household context and feeds it into STT/LLM/TTS.

- **Languages** — capture the language(s) the household speaks during setup, and pass an explicit
  `language` to Voxtral STT so it stops auto-detecting wrong (observed: Voxtral guessed Chinese).
  Pick TTS voice(s) to match. *Verify the transcription API's language parameter.*
- **Household context (names & members)** — capture family members' names + relationships at setup.
  Use it two ways: (a) bias STT toward those names if the API supports a prompt/vocabulary hint, so
  names transcribe correctly; (b) inject into the agent's system prompt so it understands references
  ("call her", "tell Mom"). This is also the natural source for the **call allowlist** (C1).
- **Per-speaker voice ID / learning (stretch — research)** — recognize *who* is speaking and adapt
  per person. Voxtral doesn't expose speaker identity, so this needs separate speaker
  diarization/verification (on-device model or a service) — feasibility + approach TBD.
- **Setup UX** — make onboarding itself agentic/conversational (Teya asks, family answers) rather
  than forms, storing a structured "household profile" the harness reads.

## 🧊 Backlog / ideas

- Settings **voice picker** (live from `/audio/voices`) + persist choice.
- More tools/capabilities (reminders, smart home, messaging) — extend `AgentTools`.
- Feed tool results back to the model for confirm/repair (**M7** follow-on).
- Re-open cue (soft chime) when the mic re-opens mid-conversation.
- UI: fix orb wave animation + idle-gate the 24/7 animations (**H8/H9**); StateFlow instead of broadcasts (**M3**).
- Release readiness: R8/ProGuard + signing config (**H6**); LiteRT version-catalog cleanup (**H7**).
