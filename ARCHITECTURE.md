# Architecture & Build Approach

> *How we actually build the family agent described in the [README](./README.md).*

This document records the core architectural decisions and the reasoning behind them. It is a decision record, not a spec — it explains *why* we're building it this way so future choices stay consistent.

---

## 1. The core insight: brain / harness / actuator

The report talks about "the harness" as one thing. For every build decision, split it into **three layers** — they have opposite constraints, and conflating them is the main way to get the architecture wrong.

| Layer | What it is | Where it must live | Why |
|---|---|---|---|
| **Brain** | LLM inference (Claude / Gemini) | **Cloud, always** | A €50 phone cannot run a frontier model. This is an API call, full stop. |
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
- `phoneCall`: For managing telephony via the `InCallService`.
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
| Place / answer / end calls, be the dialer | `TelecomManager`, `InCallService`, `ConnectionService`, `RoleManager` | None first-class — platform channels required |
| Read every app's notifications | `NotificationListenerService` | None — native only |
| Act inside other apps (e.g. WhatsApp) | `AccessibilityService` | None — native only |
| Always-on background presence | Foreground service + Doze/battery handling | Native lifecycle |
| BLE / USB-OTG / NFC | Native APIs | Plugins exist but are leaky |
| Animated face | Compose Canvas, **Rive**, or Lottie | Flutter's only real edge — not worth the rest |

And the clincher: **we target one device, Android only.** Cross-platform's entire value proposition (one codebase for iOS + Android) does not apply. Flutter's sole advantage is its renderer for the face; that does not justify wrapping the telephony / notification / accessibility / BLE stack in platform channels. React Native is the weakest fit (poor at both background services and custom rendering) and is ruled out.

> **Note on the future companion app.** The later "on the go" personal-phone client is a separate, lighter product. *It* may be cross-platform if we want. Do not let that future client dictate the home device's stack.

---

## 4. Decision: the agent is the call *operator*, not a call *participant*

This is the telephony design, and getting the framing right is what keeps it simple.

**The use case:** a kid says "call Dad." The app places a normal phone call to Dad. The two of them talk like any ordinary call. The AI initiates and ends the call — it is never *in* the conversation.

### Why this matters

There is a well-known landmine here that we explicitly avoid. If the AI needed to *be in* the call (transcribe it, talk on it), we'd have to **capture the call audio** — and **Android 10+ deliberately blocks third-party apps from capturing in-call audio** (the remote party's voice). That would force a VoIP/SIP number so calls arrive over data where audio is fully accessible.

**We do none of that.** Because the AI never touches the audio stream — it only initiates, routes to speaker, and ends — a **plain cellular call on the SIM is exactly right.** No VoIP, no SIP, no audio capture, no root, no carrier status.

> **Rejected alternative — VoIP/SIP number (Twilio, LiveKit, etc.).** Correct *only* if the AI must participate in the call audio. It isn't, so VoIP adds a number, a provider dependency, and complexity for zero benefit. Revisit only if we later want the agent itself to speak on calls.

### How it's built

Make the app the **default dialer** by implementing an `InCallService`. That single role grants everything needed, all through intended, supported platform APIs:

- **Place** — `TelecomManager.placeCall()`
- **Answer** hands-free — `acceptRingingCall()`
- **Speakerphone routing** — fully controllable as the `InCallService` (the robust path; ad-hoc `AudioManager` speaker toggling is flaky on newer Android)
- **End** — `endCall()` / `Call.disconnect()`
- **The animated face becomes the in-call UI** — ideal for a wall-mounted device

### The flow

```
wake word → "call Dad"
  → agent resolves "Dad" → contact
  → confirms ("Calling Dad…")        [optional; good for kids]
  → places a normal cellular call
  → speakerphone on; face becomes the call screen
  → humans talk
  → big on-screen End button (or programmatic end) → call ends
```

### Tool Use Protocol
The Brain (LLM) triggers actions via a structured Tool Use (Function Calling) protocol.
1. **Schema Definition**: The Harness provides the Brain with a set of JSON schemas for available tools (e.g., `place_call(contact_id)`, `send_message(text, contact_id)`, `toggle_light(room, state)`).
2. **Intent Execution**: When the Brain responds with a tool call, the Harness validates the parameters against the safety allowlist and local state before passing it to the relevant Actuator.
3. **Feedback Loop**: The result of the Actuator's action (success/error) is fed back to the Brain as a tool response, allowing the agent to confirm or troubleshoot.

### Safety: a contacts allowlist, not open dialing

The agent places calls **only** to entries on an approved family contacts list. A spoken name ("call Dad") is resolved against that allowlist; anything not on it is refused. This *is* the security model for the feature — a child can reach the people they're allowed to and no one else, with no path to dialing arbitrary, premium, or unknown numbers. The allowlist lives on-device alongside the rest of family memory (§5) and is editable only by a parent/admin.

### Two details to design for (both easy)

- **Permissions / role.** `CALL_PHONE`, `ANSWER_PHONE_CALLS`, plus the user grants the default-dialer role once at setup. Fine for a dedicated home device.
- **Hands-free hang-up.** While a call is active, the telephony stack owns the mic, so the wake word likely won't be heard mid-call. **Do not rely on voice to end the call** — show a large, tappable **End** button on the face. (Programmatic end on a timer or tap also works.)

---

## 5. The v1 stack

```
Kotlin + Jetpack Compose app  (always-on foreground service)
│
├─ Face        → Rive/Lottie, driven by an agent-state machine
├─ Voice in    → on-device wake word (Porcupine / openWakeWord)
│                → streaming STT (cloud: Deepgram / Google, or on-device)
├─ Brain       → Claude / Gemini API, tool-use loop   ← the only mandatory cloud piece
├─ Voice out   → TTS (cloud for quality: ElevenLabs / Google)
├─ Telephony   → cellular SIM + default-dialer InCallService   (§4)
├─ Actuators   → NotificationListenerService, AccessibilityService,
│                BLE, NFC, Matter / Google Home SDK
└─ Memory      → on-device store (Room / SQLite) first; cloud sync later
```

---

## 6. Build order (sequenced to de-risk fastest)

1. **Prove it feels alive.** Always-on foreground app + animated face + the core loop: wake word → STT → Claude → TTS. This is the heartbeat; nothing else matters if this doesn't feel right.
2. **One real action, end to end.** e.g. "add milk to the shopping list" or "message Ana." Proves the action/execution path through the agent loop.
3. **Telephony.** Default-dialer / `InCallService`, the "call Dad" flow from §4.
4. **Smart-home actuators.** BLE, Matter / Google Home, notifications, NFC.

**Schedule the hard parts deliberately:** telephony (the default-dialer role) and accessibility-based control of other apps are the two areas that will consume the most time. Don't treat them as casual.

---

## 7. Open questions / things to watch

- **App integrations (§5 Actuators) are the real cost.** Messaging, lists, and smart home are where most ongoing work lives — telephony got easy, these did not.
- **AccessibilityService for in-app actions** (e.g. driving WhatsApp) is powerful but brittle across app updates and OS versions. Evaluate official APIs / share intents first.
- **Wake-word reliability while plugged in** is the make-or-break UX detail for an always-on device.
- **STT/TTS: cloud vs on-device** — start cloud for quality (the device is plugged in and on WiFi), revisit on-device later for privacy/latency.
- **Memory model** — what persists, where, and how a family edits or forgets it. Local-first, but the schema deserves real thought early.

---

*Decisions recorded from design + architecture sessions — June 2026.*
