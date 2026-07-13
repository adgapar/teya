# Architecture & Build Approach

> *How we actually build the family agent described in the [README](./README.md).*

This document records the core architectural decisions and the reasoning behind them. It is a decision record, not a spec — it explains *why* we're building it this way so future choices stay consistent.

---

## 1. The core insight: brain / harness / actuator

The report talks about "the harness" as one thing. For every build decision, split it into **three layers** — they have opposite constraints, and conflating them is the main way to get the architecture wrong.

| Layer | What it is | Where it must live | Why |
|---|---|---|---|
| **Brain** | LLM inference + STT + TTS (Mistral) | **Cloud, always** | A €50 phone cannot run a frontier model. This is an API call, full stop. |
| **Harness** | Agent loop, tool routing, memory, scheduling, integrations | **Our choice** | The one layer with real freedom. We put it on the phone (see §2). |
| **Actuator** | Telephony, mic/speaker, camera, BLE/WiFi/NFC, notifications, smart-home control | **On the phone, always** | These are Android-local APIs. No cloud server can reach them except by proxying through software running on the device. |

**The decisive consequence:** the actuator layer forces a substantial native Android app no matter what we do. The phone is not a dumb terminal that a server could replace — it is the *hands*. Once that app exists, the cheapest place to put the harness is *also on the phone*.

---

## 2. Decision: on-device harness, cloud brain, no server for v1

**The harness runs on the phone as an always-on foreground service. It calls out to the cloud LLM for reasoning. There is no backend server in v1.**

Rationale:

- The actuator layer already requires a capable native app — so the marginal cost of also hosting the agent loop there is near zero.
- It matches the project's instinct that a cloud-hosted agent is undesirable: family data and device control stay local by default.
- It is genuinely *simpler*. No server to deploy, secure, or pay for to get a working v1.

### Foreground Service Management (Android 14+)
To ensure the harness isn't killed by the OS, it runs as a persistent Foreground Service. For Android 14 (API 34) and higher, we declare specific service types:
- `microphone`: For wake-word detection and STT.
- `phoneCall`: Was for managing telephony via the `InCallService`; no longer needed under the
  outbound-only revision in §4 (placing a call via `TelecomManager` doesn't require it).
- `connectedDevice`: For Bluetooth/Matter/USB-OTG actuators.
- `specialUse`: For the core agent loop (with a detailed explanation for Play Store review).

The only mandatory cloud dependency is the **brain** (the LLM API). Everything else can be local.

### When a thin backend becomes worth it (post-v1, optional)

Add a small server **only** for things the phone is genuinely bad at — never as the core:

- **Cross-device continuity** — the "same assistant on your personal phone" use case.
- **Secret / OAuth token storage** — safer off-device for third-party integrations.
- **Reliable proactive triggers** — scheduled or event-driven nudges that shouldn't depend on one device staying awake.

Treat this as optional infrastructure layered on later, not the foundation.

---

## 3. Decision: native Android (Kotlin + Jetpack Compose)

**Build the home device as a native Android app in Kotlin with Jetpack Compose. Not Flutter, not React Native.**

The decision hinges on one fact: **~70% of the differentiated work is deep platform integration**, and almost none of it has first-class cross-platform support. We'd end up writing native code through a bridge either way — paying the cross-platform tax for no benefit.

| Capability | Android API | Cross-platform support |
|---|---|---|
| Place calls (outbound only — §4) | `TelecomManager` / `ACTION_CALL` | None first-class — platform channels required |
| Read every app's notifications | `NotificationListenerService` | None — native only |
| Act inside other apps (e.g. WhatsApp) | `AccessibilityService` | None — native only |
| Always-on background presence | Foreground service + Doze/battery handling | Native lifecycle |
| BLE / USB-OTG / NFC | Native APIs | Plugins exist but are leaky |
| Animated face | Compose Canvas, Rive, or Lottie — **Compose Canvas is what shipped** (§5) | Flutter's only real edge — not worth the rest |

And the clincher: **we target one device, Android only.** Cross-platform's entire value proposition (one codebase for iOS + Android) does not apply. Flutter's sole advantage is its renderer for the face; that does not justify wrapping the telephony / notification / accessibility / BLE stack in platform channels. React Native is the weakest fit (poor at both background services and custom rendering) and is ruled out.

> **Note on the future companion app.** The later "on the go" personal-phone client is a separate, lighter product. *It* may be cross-platform if we want. Do not let that future client dictate the home device's stack.

---

## 4. Decision: the agent is the call *operator*, not a call *participant*

> **Revised 2026-07-13** (see `docs/roadmap.md`): the original plan below made the app the
> **default dialer** via `InCallService`, so it could also route to speaker and end the call
> programmatically. That's now dropped in favor of a plainer **outbound-only** design — Teya is a
> fixed home appliance, not a number anyone dials, so there's no inbound side to justify the
> default-dialer role, `ANSWER_PHONE_CALLS`, or an `InCallService` at all. Teya just places the
> call (`TelecomManager.placeCall()` / `ACTION_CALL`, `CALL_PHONE` permission only); the system's
> own Phone app takes over the live call UI (speaker, hang-up) from there. `TeyaInCallService.kt`
> still exists in the tree and needs deleting as part of implementing this. The "why a plain
> cellular call, not VoIP" reasoning below is unaffected by this revision.

This is the telephony design, and getting the framing right is what keeps it simple.

**The use case:** a kid says "call Dad." The app places a normal phone call to Dad. The two of them talk like any ordinary call. The AI initiates the call and then steps aside — it is never *in* the conversation, and (per the revision above) no longer manages it once dialed either.

### Why this matters

There is a well-known landmine here that we explicitly avoid. If the AI needed to *be in* the call (transcribe it, talk on it), we'd have to **capture the call audio** — and **Android 10+ deliberately blocks third-party apps from capturing in-call audio** (the remote party's voice). That would force a VoIP/SIP number so calls arrive over data where audio is fully accessible.

**We do none of that.** Because the AI never touches the audio stream — it only initiates, routes to speaker, and ends — a **plain cellular call on the SIM is exactly right.** No VoIP, no SIP, no audio capture, no root, no carrier status.

> **Rejected alternative — VoIP/SIP number (Twilio, LiveKit, etc.).** Correct *only* if the AI must participate in the call audio. It isn't, so VoIP adds a number, a provider dependency, and complexity for zero benefit. Revisit only if we later want the agent itself to speak on calls.

### How it's built

Place the call outbound-only, no dialer role needed:

- **Place** — `TelecomManager.placeCall()` (or `Intent(ACTION_CALL)`), gated by `CALL_PHONE`
  permission and the allowlist below.
- **Everything after that is the system's job.** Once placed, the stock Android Phone app owns
  the live call — speakerphone routing, mute, and hang-up all happen there, not in Teya. Teya's
  face returns to idle/listening; it has no further role in an active call.

### The flow

```
wake word → "call Dad"
  → agent resolves "Dad" → contact
  → confirms ("Calling Dad…")        [optional; good for kids]
  → places a normal cellular call via TelecomManager
  → system Phone app takes over (speaker, hang-up); Teya's face returns to idle
```

### Tool Use Protocol
The Brain (LLM) triggers actions via a structured Tool Use (Function Calling) protocol.
1. **Schema Definition**: The Harness provides the Brain with a set of JSON schemas for available tools (e.g., `place_call(contact_id)`, `send_message(text, contact_id)`, `toggle_light(room, state)`).
2. **Intent Execution**: When the Brain responds with a tool call, the Harness validates the parameters against the safety allowlist and local state before passing it to the relevant Actuator.
3. **Feedback Loop**: The result of the Actuator's action (success/error) is fed back to the Brain as a tool response, allowing the agent to confirm or troubleshoot.

### Safety: a contacts allowlist, not open dialing

The agent places calls **only** to entries on an approved family contacts list. A spoken name ("call Dad") is resolved against that allowlist; anything not on it is refused. This *is* the security model for the feature — a child can reach the people they're allowed to and no one else, with no path to dialing arbitrary, premium, or unknown numbers. The allowlist lives on-device alongside the rest of family memory (§5) and is editable only by a parent/admin.

### One detail this revision resolves for free

- **Permissions.** Just `CALL_PHONE` — no `ANSWER_PHONE_CALLS`, no default-dialer role grant at setup.
- **Hang-up** is no longer Teya's problem: since the system Phone app owns the live call, its own
  UI already provides the end-call control. (The wake word likely won't be heard mid-call either
  way, since the telephony stack owns the mic while a call is active — moot now that Teya isn't
  trying to manage the call.)

---

## 5. The v1 stack

*Updated 2026-07-13 to match what's actually running — see `docs/roadmap.md` for the full trail.*

```
Kotlin + Jetpack Compose app  (always-on foreground service)
│
├─ Face        → hand-rolled Compose Canvas particle field (~830 points), driven by an
│                agent-state machine — not Rive/Lottie in the end
├─ Voice in    → our own trained wake word ("hey_teya" via microWakeWord, commercial-use clear),
│                fed by a vendored TFLite Micro "microfrontend" feature extractor (the app's one
│                native/NDK module) — replaced openWakeWord entirely
│                → streaming STT (Mistral Voxtral)
│                → mid-sentence barge-in via a session-scoped WebView hosting Chromium's own
│                   getUserMedia AEC, with an automatic gap-gated (no-AEC) fallback
├─ Brain       → Mistral (mistral-small, tool-use loop)   ← the only mandatory cloud piece
├─ Voice out   → Mistral Voxtral TTS, streamed
├─ Telephony   → cellular SIM, outbound-only via TelecomManager.placeCall()   (§4, revised)
├─ Actuators   → calendar, alarms/timers, shopping list shipped; NotificationListenerService,
│                AccessibilityService, BLE, NFC, Matter / Google Home SDK still ahead
└─ Memory      → on-device Room DB — household roster, aliases, per-person memory; cloud sync later
```

---

## 6. Build order (sequenced to de-risk fastest)

1. ✅ **Prove it feels alive.** Always-on foreground app + animated face + the core loop: wake word → STT → Mistral → TTS. Done, and since extended with a custom wake word and real mid-sentence barge-in.
2. ✅ **One real action, end to end.** Calendar, timers/alarms, and the shopping list are all live; "call Dad" is next.
3. **Telephony.** Outbound-only `ACTION_CALL` / `TelecomManager.placeCall()`, revised per §4 — not yet built.
4. **Smart-home actuators.** BLE, Matter / Google Home, notifications, NFC — not started.

**Schedule the hard parts deliberately:** accessibility-based control of other apps is the area
most likely to consume real time. Telephony turned out easier than originally scoped once outbound-only
replaced the default-dialer plan (§4) — still, don't treat it as trivial.

---

## 7. Open questions / things to watch

- **App integrations (§5 Actuators) are the real cost.** Messaging and smart home are where most ongoing work lives — calendar/timers/shopping list turned out easy, telephony is next, these did not.
- **AccessibilityService for in-app actions** (e.g. driving WhatsApp) is powerful but brittle across app updates and OS versions. Evaluate official APIs / share intents first. Still open — not started.
- ✅ **Wake-word reliability while plugged in** — resolved: our own trained `hey_teya` model, validated live (5/5 detections incl. ~1.5 m far-field, no false accepts). More validation sessions across rooms/times/speakers still worth doing; true whole-room coverage likely needs a mic array.
- ✅ **STT/TTS: cloud vs on-device** — decided: Mistral Voxtral (cloud) for both, for quality. Barge-in/interruption is handled separately, on-device (Silero VAD + WebView-hosted AEC), independent of this choice.
- **Memory model** — partially decided: on-device Room DB holds the household roster, aliases, and per-person memory today. Still open: the deferred "KNOWN people" tier (facts learned about people outside the household, captured by voice) — see the roadmap backlog.

---

*Decisions recorded from design + architecture sessions — June 2026; telephony design revised
2026-07-13 (outbound-only, see §4) per `docs/roadmap.md`.*
