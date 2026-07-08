# Teya — Roadmap & Status

Living status doc. Design lives in [ARCHITECTURE.md](../ARCHITECTURE.md); the full findings backlog
is [thoughts/shared/research/2026-07-06-project-audit.md](../thoughts/shared/research/2026-07-06-project-audit.md).
Audit IDs (C1, H2, …) below refer to that file.

_Last updated: 2026-07-08._

## ✅ Done

- Android app + always-on foreground service (`HarnessService`), **particle-field voice face** (`AgentFace`), centred live transcript.
- **Voice loop**, all Mistral, no disk: Voxtral STT → Mistral LLM (tool calling) → Voxtral TTS.
- **TTS working**: correct `voice` field + base64-in-JSON decode; default voice **Marie – Happy** (`fr_marie_happy`).
- **Streaming TTS**: `stream:true` + PCM SSE → `AudioTrack` (int16), ~0.8 s to first word, mp3 fallback.
- **Streaming LLM**: `MistralClient.streamChat` reads the chat `chat.completion.chunk` SSE (mirrors the
  TTS SSE reader), accumulating `delta.content` and assembling `delta.tool_calls` by `index`. The
  harness (`respond()`) queues each completed sentence to a parallel TTS consumer (Channel) so
  speech starts while the model is still generating — killing the dead "thinking" pause. The centred
  transcript is revealed **from the speaker** — one sentence at a time as each is voiced, with a
  char-by-char typewriter — so the caption **tracks the audio** instead of racing ahead of it (it
  self-resyncs at each sentence boundary). Tool rounds stay silent (THINKING) and the tool loop is
  unchanged. Verified live.
- **Conversation mode**: multi-turn, bounded history (context), silence timeout, re-entrancy guard (fixes audit C6).
- **Tool-result feedback loop (M7)**: the harness now runs the tool the model asks for, feeds the
  result back, and lets the model phrase the spoken reply (bounded, multi-round). This is the
  plumbing every *query* tool needs. `place_call` now also routes its result through the model
  (cleaner confirm/deny).
- **First native tools on the loop**: `set_timer` / `set_alarm` (`AlarmClock` + `EXTRA_SKIP_UI`,
  so they set without foregrounding the clock app) — verified live.
- **Parallel tool-calling**: the loop runs *every* tool the model returns in a response (was
  `firstOrNull` → dropped the rest), sequentially in code (no store races), feeding each result back
  by `tool_call_id`. Batch when independent, chain across rounds when dependent. Verified live.
- **Shopping list** (`shopping/ShoppingListManager.kt`): Teya-owned, **persistent** (SharedPreferences,
  survives reboots). `add` / `remove` / `read` / `clear` — comma-separated multi-item add; the model
  groups by aisle at read time (categorization = LLM's job, not stored). Verified live.
- **Ambient context (time + location)**: a small "live device state" block (current time in
  12-hour form + last-known location) is injected into the system prompt **every turn**, so the
  model answers time/date/location with **no tool round-trip**. `get_time` the tool was removed
  (ambient replaces it). Location via `getLastKnownLocation`; the model infers the city from raw
  coords. Verified live. (Location is PII in the prompt/logs → gate logs behind DEBUG — audit H2.)
- **Persona extracted** to `com.teya.agent.persona`; capability-style prompt; one short sentence,
  reply-language driven by the household profile (was English-only).
- **Household onboarding + Admin + contextual profile** (`household/`): guided 4-step first-run
  wizard (`SetupActivity` — API key · members · languages · home) and an **Admin** console
  (`SettingsActivity`, opened by **long-pressing the face** — gear removed; responsive **two-pane
  landscape / stacked portrait** + manual rotate toggle). Members live in **native Contacts**
  (`ContactsRepository` — Google-account sync when present, else local; feeds the call list) with
  first/last, **aliases** (what the family calls them), email, phone, **birthday** (Contacts Event);
  Teya's private brain (full alias list + future KNOWN people/memory) in **Room v2** (`Migration(1,2)`
  CREATE-only, DB singleton). `HouseholdManager.profileContextBlock()` injects the roster (+ birthdays
  + shared-alias disambiguation) and a **reply-language directive** into the live context every turn —
  fully **generic / config-driven** (speakable = household ∩ TTS-9; match the user's message language,
  ignore device location). Dark-theme Compose (`HouseholdComposables`) matching the particle face.
  **Verified live on-device** (migration, Contacts round-trip, both orientations). Ported from the
  design-locked plan `thoughts/shared/plans/2026-07-08-household-onboarding.md`.
- HTTP timeouts (C5), connection warmup, VAD tuning.
- **Wake word working at ~1.5 m** (openWakeWord `hey_jarvis`). Fixed the near-field-only problem
  with a software audio front-end: `VOICE_RECOGNITION` source + `NoiseSuppressor` + **6× software gain**
  (device has no hardware AGC), threshold 0.2, patience 1. Ambient floor ~0.03 vs detections ~0.26–0.34.
  Further range / commercial use still needs a custom "Hey Teya" model + likely a mic array.
- **Voice face redesign — one field of points that morphs per state** (`AgentFace`): ~830 points
  reassemble into a form per mode — idle **sea** (rolling perspective grid), listening **inhale
  rings** (drawn inward), thinking **swirl** (orbiting), speaking **waveform ribbon** (banded dots,
  fanning at loud peaks). Per-state colour (sea-blue / aqua / violet / amber), additive glow,
  per-point easing for the morph, driven by a frame clock (`withFrameNanos`). Replaces the old
  filled orb + the `System.currentTimeMillis()` wave hack and idle-gates the animation (**H8/H9**).
  **Live transcript moved to screen centre** (`MainActivity`): the user's words while listening,
  Teya's reply while speaking. Dead `OrbStyle` scaffolding removed; Settings back to API-key only.
  *Built + compiles; pending a live on-device test.* Direction locked via an interactive preview.
- Repo hygiene: `.gitignore` build output + `.env`; `.env.example`; voice catalog (`docs/mistral-voices.md`).

## 🔜 Next (recommended order)

1. **Make the call feature actually work** — currently non-functional. **Outbound only**: the device
   is a personal home assistant, not a number anyone dials, so drop the inbound side —
   `TeyaInCallService` + `ANSWER_PHONE_CALLS` + default-dialer-for-inbound are dead weight. Just
   `ACTION_CALL` ("call Grandma"). See [[device-form-factor]].
   - ⚠️ **Hardware check:** the dev device is a dedicated phone with a **fresh SIM** — confirm the
     plan actually allows outbound cellular calls before assuming code is the blocker. Calls go over
     the **native cellular dialer + SIM only** — no Twilio/VoIP (zero-setup principle: no one will
     configure an account). If the plan can't call, build + test the *plumbing* (allowlist +
     name→number resolution + call intent) without a live connection until a callable SIM is in.
   - Populate the allowlist — Teya **seeds** it (blank-slate device has no contacts); parent-gated
     contact UI or DB seed — **C1**.
   - Use `ACTION_CALL` for outbound — **C2**. (Default-dialer / `ROLE_DIALER` was for inbound; not needed.)
   - Exact-match single lookup + phone-number validation (no `LIKE` wildcard bypass) — **C3**.
   - Runtime permission recheck at call time — **H11**.
2. **Wake word** — ✅ now works at ~1.5 m (software front-end: `NoiseSuppressor` + 6× gain, threshold
   0.2 / patience 1). Remaining: **train a custom "Hey Teya" model** for further range + commercial use
   (`hey_jarvis` is dev-only / non-commercial — see `THIRD_PARTY_MODELS.md`); true whole-room likely
   also needs a mic array. Tuning knobs live in `WakeWordEngine` (`THRESHOLD`, `INPUT_GAIN`, `PATIENCE`).
3. **Security pass** — no plaintext key fallback (**C4**), `allowBackup=false` (**H1**), gate PII logs behind `BuildConfig.DEBUG` (**H2**).
4. **Resource leaks** — close TFLite interpreters + `HttpClient` in `onDestroy` (**H3/H4**).

## 🏠 Household setup & personalization

**v1 shipped** (see the Done bullet above): guided form onboarding + Admin, members in Contacts
(names/aliases/email/phone/birthday), languages, home, and the profile context block. Remaining:

- ✅ **Languages** — captured at setup (the 13 Voxtral STT languages; 9 speakable flagged 🔊). The
  reply language is controlled by the **prompt directive**, not an STT param. **STT stays
  auto-detect** — accepted: accurate on real sentences, and single-language homes (the common case)
  are unambiguous; only multilingual homes see mis-detection on one-word greetings (e.g. "hello" →
  "hola"), which the model self-corrects. *Optional later: pin the STT `language` when exactly one
  household language is set.*
- ✅ **Household context (names & members)** — captured and injected into the profile context block
  ("call Dad", "tell Mama"). Also the natural source for the **call allowlist** (C1). *Not yet: bias
  STT toward member names (prompt/vocabulary hint) so unusual names transcribe correctly.*
- **Per-speaker voice ID / learning (stretch — research)** — recognize *who* is speaking and adapt
  per person (resolves shared nicknames like "Dad" = two people automatically, instead of asking).
  Voxtral doesn't expose speaker identity → needs separate diarization/verification. Feasibility TBD.
- **Conversational onboarding (deferred)** — v1 is a **form** on purpose (STT isn't reliable before
  the language is set — chicken/egg). A later agentic setup (Teya asks, family answers) could layer on.
- **KNOWN people + memory** — the deferred half of the Room schema (`persona` / `memory_entry`
  tables exist); see the Backlog "Implement memory" item + the Admin People/Memory sections.

## 📱 Native capabilities — the phone *is* the platform

The reason Teya lives on a phone (not a smart speaker) is to command the device's **entire native
Android surface**. The dev device is dedicated, in developer mode, with **every permission
pre-granted** — a blank slate (no pre-loaded contacts, fresh SIM). So **runtime-permission cost is
not a design axis**; group work by *how* Teya reaches a capability, not by what it's allowed to touch.

> **Hard constraint — zero *household* setup.** The bar: the family plugs in a phone and it works.
> The disqualifier is **household setup burden** — "no one will set it up." This is NOT "only
> LLM/STT/TTS forever": a **keyless / no-account API** (e.g. weather) or a **native SDK that needs no
> household config** is fine, decided per-capability as specific painless work. Still banned:
> anything the family must configure — Twilio/VoIP numbers, smart-home hubs, per-service accounts.
> Calls = native cellular dialer + SIM; messaging = native SMS / installed-messenger intents.

Two mechanisms:
- **Direct APIs / content providers** — data flows *both ways*, so Teya can speak the answer.
  Uses the tool-result feedback loop (done). Examples: time (done), alarms/timers, calendar,
  location, audio/volume, battery, telephony.
- **Intents into installed apps** — hand off / launch. Mostly fire-and-forget; an app generally
  can't hand data *back* to speak. Examples: open a maps app for navigation, share to a messenger.

**Stay resident — don't hand off the screen.** Launching another app *backgrounds Teya*, and modern
Android won't reliably let a backgrounded app pull itself back (background-activity-start limits) — so
the user would have to tap Teya again. Therefore **speak-it via system APIs is the default**;
app-launch is the rare exception. For the exceptions + re-engagement we rely on the **always-on wake
word** (mic never stops — talk to Teya over any app) and the **overlay permission**
(`SYSTEM_ALERT_WINDOW`) — floats the orb on top *and* is the documented exception that lets Teya
resurface herself. **Calls** are the one legitimate full-takeover: hand off for the call, re-engage
via wake word after (longer term, default dialer / `ROLE_DIALER` (C2) lets Teya host the call itself).
*Needs on-device validation — OEM overlay quirks on the Samsung A34.*

For anything with a companion app: **speak it** needs a data source (API/provider); **show it** just
fires an intent. Weather is the canonical case — **decided: speak it** via a keyless API (e.g.
Open-Meteo), with location from the household profile or native device location.

> **Pattern — every create/set tool ships with its cancel/delete in the same slice.** A model that
> lacks the inverse improvises and lies (calendar had no delete → it re-added events and claimed it
> removed them; timers shipped set-only). Pair them, say "only way to cancel" in the prompt, and
> scope destructive ops safely.

**Add order (each is: `ToolSpec` in `AgentTools` → `when` branch in `executeTool` → mention in `TeyaPersona`):**
1. ✅ time / date / location — done, but as **ambient context**, not a tool (`get_time` removed).
2. ✅ `set_alarm` — done; `AlarmClock` + `EXTRA_SKIP_UI` (sets without foregrounding the clock app),
   `SET_ALARM` permission. `set_timer` shipped the same way but is being **reworked** in (2b).
2b. ✅ **Timers → Teya-owned** (`timers/TimerManager.kt`): native `AlarmManager`, re-enters
   `HarnessService` via `ACTION_TIMER_FIRED` to announce in her own voice. **Set + fire-announce
   verified live** (fired on time at +30s, spoke "Time's up — your pasta timer is done"). Active
   timers ride in the **ambient context** (time-left is free); `set_timer` / `cancel_timer` are
   tools. **`cancel_alarm`** added for the system Clock (`ACTION_DISMISS_ALARM` by label/time/next/all).
   *Built + installed; cancel_timer / cancel_alarm / time-left paths pending a live test.*
   Known limit: active list is in-memory (a timer still fires + announces after process death, but
   can't be listed/cancelled until it goes off).
3. **`get_weather`** — Open-Meteo (free, no key) + native device location (ambient) → spoken.
4. **`calendar`** (`calendar/CalendarManager.kt`) — hybrid backing (synced Google calendar if
   present → else existing writable/local calendar). ✅ **Slice 1 verified live**: `add_event`
   (title/start/duration/location + `repeat`→RRULE recurrence), `get_events` (Instances expands
   recurrences), today's remaining events in the ambient context. Follow-on slices:
   **(a) advance voice-reminders** (reuse the timer `AlarmManager`+announce → "football in 30 min");
   **(b) attendees/email invites** (needs family emails from the onboarding profile; only real on a
   synced Google calendar); **(c) leave-time / distance** (event location + ambient location).
5. ✅ **Shopping list** (`shopping/ShoppingListManager.kt`) — Teya-owned, persistent
   (SharedPreferences). `add_to_shopping_list` / `remove` / `read` / `clear`; comma-separated
   multi-item; model groups by aisle at read time. Verified live.
6. **Expense tracker** — voice logging + **deterministic math** (the key design: the LLM extracts &
   classifies, a tool computes; the model never sums — see the deterministic-math principle).
   - `log_expense(amount, item, quantity, category)` — from "paid 3.50 for 1 kg of tomatoes"; the
     LLM parses the amount/item/qty and classifies the category, date stamped from ambient `now`.
   - Persistent dated store (SharedPreferences/Room), like the shopping list.
   - **`query_expenses(period, category)`** — filters + aggregates **in code** (total / count /
     by-category breakdown) and returns exact figures for the model to phrase. LLM never adds up.
   - Ship the inverse in the same slice: `delete_expense` / correct a mis-logged entry.
   - Open Qs: currency (from locale?), category taxonomy, period vocabulary (today/week/month).
7. **`send_message`** — SMS / messenger intent to an allowlisted contact; safety-gated like calls.
8. Device state & control — battery, volume/DND, open-app/launch intents.

## 🧊 Backlog / ideas

- Settings **voice picker** (live from `/audio/voices`) + persist choice.
- **Implement memory / learning about people** — build the deferred half of the household model:
  `Person` rows with `kind=KNOWN` captured by voice ("remember Uncle Bob…"), a `MemoryEntry` table
  linked per-person (household-general when unlinked), and `remember`/`recall`/`forget` tools. The
  **Admin** screen reviews & edits all people + memory records. Feeds the persona context so Teya
  recalls facts across sessions. Schema in
  [thoughts/shared/plans/2026-07-08-household-onboarding.md](../thoughts/shared/plans/2026-07-08-household-onboarding.md).
- **Language-learning mode** — Teya as a conversational practice partner/tutor. The dev household is
  multilingual (English, Spanish, Russian) and a member is learning **Brazilian Portuguese (pt-BR)**;
  mode = guided conversation, corrections, vocab drills in a target language. **Feasible on Voxtral
  TTS**: the model speaks **9 languages** (EN, FR, ES, PT, IT, NL, DE, HI, AR) with zero-shot voice
  cloning + cross-lingual + code-mixing — so Portuguese practice is voiceable. (Russian, Chinese,
  Japanese, Korean are in STT's 13 but not TTS's 9 → understand-only.) Pairs with per-speaker voice ID.
- **Custom / cloned voices** — Voxtral TTS does **zero-shot voice cloning** from ~2–3 s of audio
  (voice-as-instruction for emotion/prosody; cross-lingual). Opportunity: a bespoke "Teya" voice or
  per-member voices, no prosody tags. Ties to the live voice picker + "Hey Teya" personalization.
- More capabilities beyond the native surface (smart home hub, media/music providers).
- Re-open cue (soft chime) when the mic re-opens mid-conversation.
- UI: StateFlow instead of broadcasts (**M3**). (Orb wave animation + idle-gate done in the particle-face rewrite.)
- Release readiness: R8/ProGuard + signing config (**H6**); LiteRT version-catalog cleanup (**H7**).
