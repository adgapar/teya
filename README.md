# teya

> *A family agent for the home — a warm, intelligent presence that listens, understands, remembers, and does. Built on a spare Android phone.*

**Status:** Vision & design stage (June 2026). Product and harness names are still TBD; `teya` is the working repo name.

### Documents

- **This file** — the vision: the problem, the product, and why now.
- **[ARCHITECTURE.md](./ARCHITECTURE.md)** — how we build it: the technical decisions and the reasoning behind them.

---

## 1. The Problem

Current voice assistants (Alexa, Siri, Google Assistant) are deeply disappointing for everyday family life. They were designed in the early 2010s around simple voice commands — essentially fancy if-then systems. They don't understand context, they forget everything between sessions, and they can't act across apps in any meaningful way.

The result: people use them to set timers and play music, then ignore the rest.

At the same time, modern AI (Claude, Gemini, GPT-4) is genuinely intelligent — but it sits behind a chat interface, disconnected from the physical and digital infrastructure of the home.

The gap between these two worlds is the opportunity.

---

## 2. The Vision

A **smart family agent** that lives in the home as a physical presence — a glowing, animated face on a screen — and acts as a true coordinator of family life. Not a speaker. Not a chatbot. An agent that **listens, understands, remembers, and does**.

The key shift: from *command executor* to *family coordinator*.

> "We're out of milk" → it adds it to the shopping list or orders it.  
> "The kids need to be at football at 5 and I have a meeting until 4:30" → it checks both parents' calendars and suggests who can take them.  
> "Message Ana that we'll be 20 minutes late" → done.

---

## 3. Why Android Phone as Hardware

The insight that makes this project viable: **a spare Android phone is a supercharged Arduino with a face**.

| Arduino / Raspberry Pi | Android Phone |
|---|---|
| Requires coding to set up | Consumer-ready out of the box |
| No screen | Beautiful display built in |
| No cellular | 4G/5G built in |
| No AI | Can call AI models directly |
| No microphone | Mic + speaker built in |
| Fragile ecosystem | Millions of existing apps |

A wall-mounted Android phone (even an old €50 device, plugged in) becomes the **single visible node** of an invisible home network. Everything else — smart bulbs, locks, sensors, calendars, messaging apps — is hidden infrastructure. The face on the wall is the only interface the family needs to see.

### What the phone controls via Android

- **Bluetooth** → speakers, locks, wearables
- **WiFi** → smart bulbs, plugs, thermostats
- **USB OTG** → connects to low-level sensors (soil moisture, door sensors via ESP32 chips)
- **Camera** → motion detection, package delivery alerts
- **NFC** → tap to trigger automations
- **Notifications API** → reads and acts on all apps
- **Google Home / Matter SDK** → controls the entire smart home ecosystem

The phone does not replace Arduino for low-level electronics — it replaces it as the **coordination brain**. Cheap ESP32 chips still handle physical sensors; the phone is the hub they report to.

---

## 4. The Face

An animated face on the phone screen changes the relationship with the device entirely. Research shows people trust a face more, engage with it more naturally, and interact with it less transactionally — especially children.

### Design direction

- **Not a human face** — abstract enough to not trigger uncanny valley
- **Soft robotic** — glowing, geometric, expressive
- **Emotionally readable** — the face conveys state:
  - *Listening* — eyes widen, subtle lean
  - *Thinking* — eyes shift, processing animation
  - *Alerting* — colour change, expression shift
  - *Happy to help* vs *uncertain*

The face is literally a light source — warm, ambient, always present. This is both a design principle and a possible naming inspiration.

### Gender

The face and personality should be **gender neutral** — calm, warm, wise, belonging to the whole family. Not subservient (challenging the female-default AI trope), but not cold or mechanical either. The goal is a presence that family members project their own relationship onto, rather than one that imposes a gender.

---

## 5. Family Use Cases

### Home logistics
- Shopping list management and ordering
- Calendar coordination across all family members
- School and activity reminders pushed to everyone's devices

### Meals & food
- Dinner suggestions based on fridge contents
- Weekly meal planning and automatic shopping list generation
- Ordering from favourite restaurants ("the usual")

### Kids
- Homework help — patient, at the child's level
- Personalised bedtime stories with favourite characters
- Screen time management and enforcement

### Calling & staying in touch
- "Call Dad", "Call Grandma" — the agent places a normal phone call to anyone on the family contacts list, completely hands-free. Especially valuable for young kids who can't (or shouldn't) navigate a dialer themselves.
- Answering calls hands-free and putting them on speaker, so the wall device doubles as the family phone.

> **Safety by design:** the agent can only call people on an approved family **contacts allowlist**. A spoken name ("call Dad") is matched against that list, and anything not on it is refused — there is no path to dialing an unknown, arbitrary, or premium number. A child can reach exactly the people they're allowed to, and no one else. The allowlist *is* the security model.

### Scheduling
- "When is everyone free this weekend?" — checks all calendars, gives a real answer
- Booking appointments that don't clash with school
- Holiday research with budget constraints

### On the go (phone)
- Same assistant available on personal phones
- Hands-free operation while driving
- Continuity — picks up where the home device left off

### Admin
- Monitoring bills and subscriptions
- Spending summaries
- Trial cancellation reminders

### The deeper value

The biggest beneficiary is the person who carries the family's **mental load** — tracking appointments, meals, logistics, school admin. The assistant becomes a **second brain for the household**, running silently in the background.

---

## 6. What This Actually Is — An Agent

This is not an assistant. It is an **agent**.

| Assistant | Agent |
|---|---|
| Answers questions | Takes actions |
| Reactive | Proactive |
| One turn | Multi-step |
| Talks | Does |

The harness being built here is an **agent framework** — it gives the AI brain hands, eyes, and a voice in the physical and digital world. The family-facing product (the face, the name, the personality) sits on top of this harness. The harness is the engine.

---

## 7. The Harness — Technical Architecture

```
[Family face & name]      ← the visible product (name TBD)
        |
   THE HARNESS             ← what this project builds (name TBD)
        |
   _____|_________________________________
  |          |           |               |
Android    AI Model   Smart home     Family data
phone HW   (Claude/    (WiFi/BT/     (calendar,
           Gemini)      Matter)       memory,
                                      contacts)
```

### Core responsibilities of the harness

1. **Voice pipeline** — wake word detection, speech-to-text, text-to-speech
2. **AI routing** — sending context to the AI model and handling responses
3. **Action execution** — translating AI intent into real-world actions (send message, set reminder, control device)
4. **Family memory** — persistent context about family members, preferences, routines
5. **App integrations** — WhatsApp, calendar, shopping, smart home APIs
6. **Face rendering** — animated face that reflects the agent's state

### What's missing today to build this

| Gap | Status |
|---|---|
| Good animated face app | Doesn't exist in polished form |
| Unified AI with persistent family memory | Getting close |
| Reliable app integrations | Patchy but improving |
| Wake word + always-on without battery drain | Solvable with plugged-in dedicated phone |

A compelling v1 is buildable today with an old Android phone, an animated face layer, and Claude or Gemini as the AI backend. Hardware cost: near zero.

---

## 8. Competitive Landscape

| Product | What it does well | What it lacks |
|---|---|---|
| Amazon Alexa | Voice control, smart home | Dumb, no real AI, forgets everything |
| Google Nest Hub | Screen + voice | Not truly agentic, no personality |
| Apple HomePod | Audio quality, Apple ecosystem | Closed, no screen, weak AI |
| Meta Portal | Video calling, face tracking | Discontinued, limited scope |
| Rabbit R1 / Humane Pin | Agentic AI hardware | No home presence, early stage |

Nobody has built the warm, intelligent, family-aware, agentic home presence yet. The space is open.

---

## 9. Why Now

- **LLMs are finally good enough** to understand family context and intent reliably
- **Android phones are cheap** — old devices are widely available and powerful enough
- **Smart home standards** (Matter) are finally converging, making integrations easier
- **Agentic AI frameworks** (tool use, multi-step reasoning) are maturing rapidly
- The market has been primed by Alexa/Google — families already have the habit, they just want it to actually work

---

## 10. The Emotional Core

The best way to understand what this project is building:

> A home should feel like it's looking after you. Not because you programmed it to, but because it knows you.

That's the feeling this project is chasing. Not a smart speaker. Not a robot. A **presence** — warm, capable, always there, belonging to the whole family.

---

*Report compiled from design exploration sessions — April 2026*
*Name (product + harness): TBD*
