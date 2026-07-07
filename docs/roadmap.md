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
- **Wake-word pipeline implemented** (openWakeWord 3-model chain, `hey_jarvis`) — ⚠️ *not yet confirmed firing on device*.
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
2. **Wake word** — diagnose why "Hey Jarvis" never fired (Logcat scores/threshold); then train a custom **"Hey Teya"** classifier (drop-in swap, `hey_jarvis` is dev-only / non-commercial — see `THIRD_PARTY_MODELS.md`).
3. **Security pass** — no plaintext key fallback (**C4**), `allowBackup=false` (**H1**), gate PII logs behind `BuildConfig.DEBUG` (**H2**).
4. **Resource leaks** — close TFLite interpreters + `HttpClient` in `onDestroy` (**H3/H4**).

## 🧊 Backlog / ideas

- Settings **voice picker** (live from `/audio/voices`) + persist choice.
- More tools/capabilities (reminders, smart home, messaging) — extend `AgentTools`.
- Feed tool results back to the model for confirm/repair (**M7** follow-on).
- Re-open cue (soft chime) when the mic re-opens mid-conversation.
- UI: fix orb wave animation + idle-gate the 24/7 animations (**H8/H9**); StateFlow instead of broadcasts (**M3**).
- Release readiness: R8/ProGuard + signing config (**H6**); LiteRT version-catalog cleanup (**H7**).
