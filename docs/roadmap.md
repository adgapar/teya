# Teya — Roadmap & Status

Living status doc. Design lives in [ARCHITECTURE.md](../ARCHITECTURE.md); the full findings backlog
is [thoughts/shared/research/2026-07-06-project-audit.md](../thoughts/shared/research/2026-07-06-project-audit.md).
Audit IDs (C1, H2, …) below refer to that file.

_Last updated: 2026-07-12 (**Wake word cut over to our own model**: trained `hey_teya` via
microWakeWord, vendored TFLite Micro's native "microfrontend" feature extractor (the app's first
NDK/CMake module), validated live on-device (5/5 detections, incl. ~1.5m far-field, no false
accepts), then removed openWakeWord/`hey_jarvis` entirely once real usage confirmed `hey_teya`
performs better — the CC-BY-NC-SA commercial-use blocker this whole effort started from is now
fully resolved. Full trail: `docs/experiments.md` → "Problem: wake word",
`thoughts/shared/plans/2026-07-12-microwakeword-android-integration.md`.

Also this date: Per-speaker voice ID shipped as a soft signal — see the household
setup & personalization section — hand-written on-device speaker-embedding matching, verified live
end-to-end with real household voices; a prebuilt-AAR integration attempt was tried and reverted
after a real on-device crash, full trail in `docs/experiments.md`. Also: Admin rebuilt full-bleed
and particle-driven — same field as the face and onboarding, no more bordered-card dashboard; a
`BRAIN_OFF` face state + gate now surfaces a bad/expired Mistral API key visually, since TTS itself
is what breaks in that case; fixed the API key not hot-reloading after an Admin edit. See Backlog
for the reset/backup gap this surfaced.)._

_2026-07-13: **Calendar attendees/email invites shipped** (native capabilities → add order, slice
4b): `add_event` now invites the whole household by real email by default (`CalendarContract.
Attendees` on the synced Google calendar), with `notify_family=false` for personal reminders/chores,
and `attendees`/`exclude_attendees` to override which people specifically. Needed a real
household Google account (`teya@household-account`) added to the device first — `CalendarManager`'s
existing hybrid backing picked up the synced calendar automatically once the account was present,
no code change needed there. **Verified live**: a real event with the invite-everyone default sent
actual Google Calendar invite emails to both parents' real inboxes. Also confirmed as a mechanism
(not yet fully round-tripped): inviting `teya@household-account` to an event from any outside Gmail
lands it on Teya's calendar too, since `CalendarManager.events()` already reads every calendar on
the device — a free way to add things to Teya's calendar without voice. Full trail:
`docs/experiments.md` → "Problem: calendar attendees / email invites"._

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
- **Wake word: our own "hey_teya" model, commercial-use blocker resolved.** Trained via the Mac
  microWakeWord-Trainer-AppleSilicon app (personal recordings + Piper-TTS synthetic positives +
  standard negative datasets; unrestricted license, 99.85% recall / ~0.93 false-accepts/hour on
  ambient validation). Required vendoring TFLite Micro's native "microfrontend" feature extractor
  (`app/src/main/cpp/microfrontend/` — the app's first NDK/CMake module) since it's a fixed-point
  DSP step not expressible as a portable TFLite graph; the model itself is a genuinely stateful
  streaming TFLite model (TFLite resource-variable conv state). **Verified live on real hardware**:
  5/5 detections, scores 0.70–0.98 including ~1.5m far-field attempts, no observed false accepts.
  Replaced openWakeWord/`hey_jarvis` (CC-BY-NC-SA, non-commercial) **entirely** the same session,
  once real usage confirmed `hey_teya` performs better. Tuning knobs (`wakeWordThreshold`/
  `wakeWordPatience`, now defaulting to `hey_teya`'s calibrated 0.53/3) live in Admin's Voice
  tuning section. Full trail: `docs/experiments.md` → "Problem: wake word". Remaining: more
  validation sessions (different rooms/times/speakers); true whole-room likely still needs a mic
  array.
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
- **Barge-in interruption + mishearing awareness**: fixes the "Teya rambled on about the wrong
  thing and couldn't be stopped" failure (STT misheard a son's name as "Ireland" and she kept
  talking). Two independent changes:
  - **Barge-in reacts to real speech, not a wake phrase** — deliberately: interrupting has to
    react to the user just *talking* ("stop!" or anything else), the way real voice agents do it.
    Went through three detectors before landing on one that actually fires (full trail:
    `thoughts/shared/research/2026-07-08-barge-in-vad-options.md`): a plain RMS-energy check never
    fired even on loud deliberate speech (loudness ≠ speech); streaming raw audio to Mistral's
    Voxtral Realtime STT over a WebSocket also never produced a single transcription event across
    several live tests, including gain and NoiseSuppressor-off fixes — root cause unresolved, but
    the network dependency and cost weren't worth chasing further. Landed on **Silero VAD, run
    fully on-device** (`voice/vad/SileroVad.kt`, an original implementation of Silero's own
    streaming algorithm against its ONNX model, `silero_vad.onnx` from
    [snakers4/silero-vad](https://github.com/snakers4/silero-vad), MIT — see
    `THIRD_PARTY_MODELS.md`), a small stateful RNN purpose-built for speech/non-speech
    discrimination, with debounce hysteresis on top to avoid firing on one-frame spikes.
  - `WakeWordEngine` forwards its raw always-open-mic chunks via `onArmedAudioChunk`, gated by
    `bargeInArmed` — Android can't reliably open a second concurrent `AudioRecord`, so this taps
    the same stream rather than using a separate recorder. `VoicePipeline.forwardArmedChunk`
    reassembles the 1280-sample chunks into Silero's 512-sample frames (they don't divide evenly),
    applies the same software gain `WakeWordEngine` applies before its own classifier (this device
    has no hardware AGC), and checks each frame synchronously — cheap enough to run inline on the
    capture thread, no coroutine/channel hand-off needed. Armed only while Teya is
    thinking/speaking (`VoicePipeline.setBargeInArmed`, driven by `HarnessService.runConversation`)
    — never idle, never during actual command capture (wake word is paused there anyway).
    Firing calls `HarnessService.onBargeIn()`: `VoicePipeline.interrupt()` cuts the
    `AudioTrack`/`MediaPlayer` immediately, and the in-flight think+speak round (its own
    cancellable `Job`, `activeTurnJob`) is cancelled — no tool calls fire off an interrupted turn.
    **No VAD choice solves self-echo**: `AcousticEchoCanceler` (added alongside existing AGC +
    NoiseSuppressor) is still the only defense against the mic picking up Teya's own voice off the
    speaker; this device's AEC effectiveness is unverified. Threshold/debounce
    (`SileroVad(threshold=0.8, speechDurationMs=100, silenceDurationMs=300)`, set in
    `VoicePipeline.setBargeInArmed`) are untuned starting points; needs live calibration.
  - **Mishearing awareness** (`TeyaPersona`): explicit instruction to sanity-check the transcript
    (does a name match the household profile? does the request cohere?) and briefly confirm/repeat
    back instead of confidently answering on a probable mis-transcription. Prompt-only — no STT
    changes.
  - *Not done*: biasing the STT itself toward household member names (a Voxtral vocabulary/prompt
    hint, if the API supports one) — would fix this class of error at the source instead of after
    the fact. Worth revisiting; unverified whether Voxtral's `/audio/transcriptions` accepts it.
  - **Built + compiles; pending a live on-device test.**
- **Native WebRTC AEC3 module — built and wired in, real mid-sentence barge-in still not
  reliable.** Vendored WebRTC's AEC3 as a JNI module (`voice/aec/NativeAec3.kt`), wired into
  `VoicePipeline` session-scoped, kill-switch `VoicePipeline.AEC3_BARGE_IN_ENABLED = false`. Two
  real platform-AEC wiring bugs found and fixed along the way (kept, unrelated to the switch). Two
  deeper root causes found since via `EchoCanceller3::GetMetrics()` diagnostics — render/capture
  timing drift (partially fixed) and a signal-energy convergence gate AEC3's own filter-confidence
  check never crosses on this device's quiet self-echo (open). **Net effect today**: same as the
  gap-gated bullet above — no regression, no mid-sentence capability yet. Full experiment trail,
  what's been tried and ruled out, and what's currently being tested: **`docs/experiments.md`**.

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
2. **Make interruption work well** — ✅ continuous mid-sentence barge-in during Teya's own speech
   now ships as the default, via a WebView/Chromium-hosted AEC (`getUserMedia`'s own echo
   cancellation). `NativeAec3` (vendored WebRTC AEC3, never achieved real suppression on this
   device) has been removed entirely, along with every kill-switch flag — this is no longer an
   experiment. Confirmed live across many real conversation turns: real interrupts fire reliably,
   Teya's own voice doesn't falsely trigger one. Gap-gated barge-in (listens only between
   sentences) remains as the automatic fallback if the WebView host fails to start. Full phased
   history: `thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md`.
   Full status + experiment trail: **`docs/experiments.md`**.
3. **Wake word** — ✅ done: our own commercial-use-clear "hey_teya" model shipped and validated live
   (5/5 detections incl. ~1.5m far-field), openWakeWord/`hey_jarvis` removed entirely. Remaining:
   more validation sessions across different rooms/times/speakers; true whole-room likely still
   needs a mic array. Tuning knobs live in Admin's Voice tuning section (`ConfigManager`).
4. **Security pass** — no plaintext key fallback (**C4**), `allowBackup=false` (**H1**), gate PII logs behind `BuildConfig.DEBUG` (**H2**).
5. **Resource leaks** — close TFLite interpreters + `HttpClient` in `onDestroy` (**H3/H4**).

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
- ✅ **Per-speaker voice ID (shipped, soft signal)** — recognizes *who's likely* speaking off a
  wake-word-time audio window, to help resolve shared nicknames like "Dad" = two people. Voxtral
  doesn't expose speaker identity, so this runs a separate on-device speaker-embedding model
  (CAM++, hand-written Kotlin fbank extractor + the already-vendored ONNX Runtime — see
  `THIRD_PARTY_MODELS.md`), matched by cosine similarity against voiceprints enrolled per household
  member via Admin's "Voice ID / Wake Word Samples" panel. **Verified live end-to-end**: real
  household members enrolled, a real wake-word trigger, a real conversation turn — capture → embed
  → compare → threshold → context injection all confirmed working on-device (full trail:
  `docs/experiments.md`). Deliberately a **soft, unconfirmed signal** (persona instructed never to
  state it aloud), not authoritative identity — matches were below the tuning threshold in the
  first live test (expected; needs more enrollment samples + real threshold tuning, tracked in
  Admin's Voice tuning section). Real dead end along the way, worth knowing: an initial attempt to
  vendor k2-fsa/sherpa-onnx's prebuilt AAR (to avoid hand-writing the fbank extractor) crashed the
  app on every conversation — its bundled ONNX Runtime is binary-incompatible with the one Silero
  VAD depends on. Reverted; hand-written fbank was the right call.
- **Conversational onboarding (deferred)** — v1 is a **form** on purpose (STT isn't reliable before
  the language is set — chicken/egg). A later agentic setup (Teya asks, family answers) could layer on.
  Shape (user feedback 2026-07-10): Typeform-style chat, one question per screen advancing turn by
  turn ("What is your first name?" → "What is your last name?" → …), agent-framed ("I'm going to ask
  you a few questions about X, Y, Z") instead of a bunch of form fields at once. Admin's forms are
  fine as-is (editing existing records, not first-run onboarding) — this only applies to the guided
  first-run wizard.
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
   recurrences), today's remaining events in the ambient context. ✅ **Slice (b) attendees/email
   invites verified live**: `add_event` invites every household member with an email on file by
   real Google Calendar email, unless `notify_family=false` (personal reminders/chores) or narrowed
   via `attendees`/`exclude_attendees`. Needed the household's own Google account added to the
   device (`teya@household-account`) — the existing hybrid backing picked its calendar up
   automatically. Full trail: `docs/experiments.md`. Remaining follow-on slices:
   **(a) advance voice-reminders** (reuse the timer `AlarmManager`+announce → "football in 30 min");
   **(c) leave-time / distance** (event location + ambient location).
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

- **Bug**: onboarding "add person" form — the X (close) button shifts the **last name** field down,
  misaligning it with first name (reported 2026-07-10).
- **16 KB page-size alignment** — every install shows Android 16's "isn't 16 KB compatible" warning
  for `libonnxruntime.so` (Silero VAD) and `libtensorflowlite_jni.so` (wake word), both prebuilt
  third-party AARs that'd need newer 16 KB-aligned releases (our own native AEC3 module, previously
  also flagged here, was removed entirely on 2026-07-11 — see `docs/experiments.md`). Harmless
  today — this device's actual page size is 4 KB and Teya is sideloaded, never Play-distributed —
  but worth cleaning up eventually if either dependency ships an aligned build (noted 2026-07-10).
- ✅ **Admin-configurable barge-in/wake-word tuning** (2026-07-11) — the 8 previously-hardcoded knobs
  (`VoicePipeline`'s Silero `threshold`/`speechDurationMs`/`silenceDurationMs`/`bargeInGain`,
  `HarnessService.bargeInGapMs`, `WakeWordEngine`'s `threshold`/`inputGain`/`patience`) now live in
  `ConfigManager` (same `EncryptedSharedPreferences` store as the Mistral API key) with today's old
  hardcoded values as defaults, editable from a new "Voice tuning" section in Admin, plus a
  "Set defaults" button (each field also shows its default inline) to recover a mistyped value
  without hunting down the original number. Read live at each use site (per-arm / per-chunk), not
  cached at construction, so a save takes effect on the next turn without restarting the service —
  no broadcast plumbing needed. **Live-tested on-device**: caught and fixed two real layout bugs in
  the process — the landscape Admin nav rail didn't scroll (a 6th section, "Voice tuning", was
  clipped off-screen along with "API"), and long field labels overlapped the new default-value hints
  in the two-column rows. Both fixed; the section now renders correctly and a save round-trip works.
- **Reset + backup/export** (noted 2026-07-12) — there's currently no reliable way to recover
  household/memory/config if the app is deleted by accident, and no "factory reset" action either
  (deliberately not built yet — a destructive reset shouldn't exist before a real backup does).
  Checked the actual state: `android:allowBackup="true"` is set with no custom rules, but that's not
  the safety net it looks like — (1) the encrypted prefs (API key, voice tuning, dream log, the
  auth-error tracking) are `EncryptedSharedPreferences`, whose decryption key lives in the Android
  Keystore and does **not** travel with Auto Backup, so even a successful restore comes back as
  undecryptable ciphertext; (2) the Room DB (`teya-db`: aliases, memory) is plain SQLite and
  technically backup-eligible, but Android's restore flow mostly fires during a fresh device/app
  setup, not an ordinary delete-then-reinstall on an already-set-up phone, so it likely won't fire
  in the actual accident scenario; (3) raw conversation transcripts are never persisted at all, by
  design (`HarnessService` keeps `history` in memory for the live turn only; `captureEpisodic`
  distills it into one short Memory note, then discards it) — nothing to lose there beyond what's
  already in Memory. Household members themselves (name/email/phone/birthday) are the one part
  that's already safe, since they live in native Contacts and sync via the device's Google account.
  Real fix: a proper **export** (household + memory + config to a file the user controls, e.g. via
  Android's share sheet) that doesn't depend on Keystore-tied encryption or Auto Backup's flaky
  restore timing — build that before any destructive Reset button.
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
