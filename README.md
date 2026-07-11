# Teya

> *A family agent for the home — a warm, intelligent presence that listens, understands, remembers, and does. Built on a spare Android phone.*

**Status:** Working prototype (July 2026). A dedicated Android device runs the full voice loop, the animated face, the first native tools, and a household profile that makes Teya contextual (knows the family, their names, and the languages they speak).

### Documents

- **This file** — the vision: the problem, the product, and why now.
- **[docs/roadmap.md](./docs/roadmap.md)** — current status: what's built and what's next.
- **[ARCHITECTURE.md](./ARCHITECTURE.md)** — how we build it: the technical decisions and the reasoning behind them.

---

## 1. The Problem

Current voice assistants (Alexa, Siri, Google Assistant) are deeply disappointing for everyday family life. They were designed in the early 2010s around simple voice commands — essentially fancy if-then systems. They don't understand context, they forget everything between sessions, and they can't act across apps in any meaningful way.

The result: people use them to set timers and play music, then ignore the rest.

At the same time, modern AI is genuinely intelligent — but it sits behind a chat interface, disconnected from the physical and digital infrastructure of the home.

The gap between these two worlds is the opportunity.

---

## 2. The Vision

A **smart family agent** that lives in the home as a physical presence — a glowing, animated face on a screen — and acts as a true coordinator of family life. Not a speaker. Not a chatbot. An agent that **listens, understands, remembers, and does**.

The key shift: from *command executor* to *family coordinator*.

> "We're out of milk" → it adds it to the shopping list.  
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

A wall-mounted Android phone (even an old €50 device, plugged in) becomes the **single visible node** of an invisible home network. Everything else — smart bulbs, locks, calendars, messaging apps — is hidden infrastructure. The face on the wall is the only interface the family needs to see.

**No server, no local agent stack.** Today's AI-agent wave runs heavy — people buy Mac Minis to host coding agents and orchestration frameworks locally. Teya needs none of it: the intelligence lives in the cloud, reached with a few API calls per turn, so the hardware is just a cheap phone that makes those calls, renders the face, and drives the device. The point isn't autonomous coding on expensive silicon — it's **everyday family life, delivered from the cheapest hardware that can already do it**.

> **What phone to buy:** any Android phone running **Android 8.0 (Oreo, 2017) or newer** — so basically anything from the last ~8 years, including old hand-me-downs. Below Android 8, voice still works, but you lose the ability to naturally interrupt Teya mid-sentence (you'd have to wait for her to finish talking before speaking over her). During first-time setup, you'll also flip one extra permission toggle ("display over other apps") — a one-time step, not something you do again.

### What the phone commands via Android

The agent works through the phone's **own native surface** — no DIY electronics, no wiring:

- **Telephony** → place calls over the cellular dialer + SIM ("call Grandma")
- **Messaging** → SMS and installed-messenger apps
- **Calendar, alarms & timers** → the family's schedule
- **Location & audio** → "home"/weather context, volume, playback
- **Notifications** → read and act on what other apps surface
- **Google Home / Matter** → the smart-home ecosystem (bulbs, plugs, locks, thermostats)

---

## 4. The Face

An animated, living presence on the screen changes the relationship with the device entirely — people engage more naturally, and less transactionally, with something that feels alive than with a blank box or a flat waveform bar. Especially children.

### Design direction

It is **not** a face with eyes and a mouth — that risks the uncanny valley and dates quickly. It's a single **field of ~830 points** that reassembles into a different living form for each state, so the presence reads through **motion and colour**, not features:

- *Idle* — a slow rolling sea (sea-blue)
- *Listening* — rings drawn inward, like an inhale (aqua)
- *Thinking* — an orbiting swirl (violet)
- *Speaking* — a waveform ribbon that fans at the loud peaks (amber)

The points glow additively, so the screen is literally a **warm ambient light source** on the wall. A live transcript sits centred over the field — the words it hears while listening, its reply while speaking.

### Gender

The face and personality should be **gender neutral** — calm, warm, wise, belonging to the whole family. Not subservient (challenging the female-default AI trope), but not cold or mechanical either. The goal is a presence that family members project their own relationship onto, rather than one that imposes a gender.

---

## 5. Family Use Cases

### Home logistics
- Shopping list management by voice
- Calendar coordination across all family members
- School and activity reminders pushed to everyone's devices

### Kids
- Homework help — patient, at the child's level
- Personalised bedtime stories with favourite characters
- Screen time management and enforcement

### Calling & staying in touch
- "Call Dad", "Call Grandma" — the agent places a normal phone call to anyone on the family contacts list, completely hands-free. Especially valuable for young kids who can't (or shouldn't) navigate a dialer themselves.

> **Safety by design:** the agent can only call people on an approved family **contacts allowlist**. A spoken name ("call Dad") is matched against that list, and anything not on it is refused — there is no path to dialing an unknown, arbitrary, or premium number. A child can reach exactly the people they're allowed to, and no one else. The allowlist *is* the security model.

### Scheduling
- "When is everyone free this weekend?" — checks all calendars, gives a real answer
- Booking appointments that don't clash with school
- Holiday research with budget constraints

### Admin
- Voice-logged expenses and spending summaries ("paid 12 for lunch" → weekly and by-category totals)
- Reminders the family sets by voice ("remind us to cancel the trial on the 15th")

### Locked down by design

Teya is a **boxed home appliance, not a phone you carry**. It works only in its home-assistant role — no personal-phone or on-the-go mode. It runs on **fresh, neutral accounts** created just to operate the device and reach people, never the family's personal Google, social, or banking logins — so there's **nothing personal on it to hijack or steal**. Together with the calling allowlist, that makes it safe to leave on a wall within reach of kids and guests.

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
        Teya               ← the family-facing product (face + personality)
         |
   THE HARNESS             ← the agent framework this repo builds
         |
   ______|________________________________
  |          |           |               |
Android    AI Model    Smart home    Family data
phone HW   (Mistral)   (WiFi/BT/     (calendar,
                        Matter)       memory,
                                      contacts)
```

### Core responsibilities of the harness

1. **Voice pipeline** — wake word detection, speech-to-text, text-to-speech
2. **AI routing** — sending context to the AI model and handling responses
3. **Action execution** — translating AI intent into real-world actions (send message, set reminder, control device)
4. **Family memory** — persistent context about family members, preferences, routines
5. **App integrations** — WhatsApp, calendar, shopping, smart home APIs
6. **Face rendering** — animated face that reflects the agent's state

### The AI backend — Mistral (swappable)

The AI runs on **Mistral**: one European provider that covers many of the agent's needs at once —
speech-to-text, the reasoning LLM (with tool calling), and text-to-speech. It's chosen for strong
performance-per-cost and for keeping the stack on European AI. The harness treats the provider as
swappable behind a single interface, so **other providers can be added later where Mistral falls
short** — for example, languages its speech models don't yet voice.

### What's built, what's next

| Piece | Status |
|---|---|
| Animated face | ✅ Built — a particle-field face that morphs per state |
| Voice loop (STT → LLM + tools → TTS) | ✅ Built, streaming, running on a dedicated device |
| Household profile / family memory | ✅ Members, aliases, languages, home; deeper per-person memory next |
| App integrations | Growing — calendar, timers/alarms, shopping list live; calling next |
| Always-on wake word, plugged in | ✅ Working at ~1.5 m; a custom "Hey Teya" model for range + commercial use is next |

A working v1 already runs on a dedicated Android phone — the animated face, the full voice loop, the first native tools, and the household profile — with Mistral as the AI backend. Hardware cost: near zero.

---

## 8. Competitive Landscape

The incumbents each solved one piece and stopped there. Amazon's Alexa and Google's Nest Hub brought voice control and smart-home routines into the mainstream, but both are essentially command parsers: they don't reason, they forget everything between requests, and they carry no sense of the family they live with. Apple's HomePod is a polished speaker locked inside Apple's ecosystem, with no screen and the weakest assistant of the three. Meta's Portal bet on video calling and was quietly discontinued. The newer AI gadgets — Rabbit's R1, Humane's Pin — put a real model in a pocket device but have no home presence, and both have largely faltered.

So the thing families actually want has not been built: a warm, intelligent presence that knows the household, acts on its behalf, and lives in the home rather than in a pocket or a distant cloud. That space is still wide open.

---

## 9. Why Now

Everything this needs has arrived at roughly the same moment. Large language models are finally good enough to understand family context and messy, real-world intent reliably, and the agentic patterns around them — tool use, multi-step reasoning — have matured from demos into something dependable. The hardware is effectively free: capable Android phones are sitting unused in drawers everywhere. Smart-home integration, for years a tangle of incompatible ecosystems, is converging on Matter. And the market is already primed — a decade of Alexa and Google Assistant taught families the habit of talking to their home; they are simply waiting for it to actually work.

---

## 10. The Emotional Core

The best way to understand what this project is building is a feeling:

> A home should feel like it's looking after you — not because you programmed it to, but because it knows you.

That is what Teya is chasing. Not a smart speaker and not a robot, but a genuine presence: warm, capable, always there, and belonging to the whole family.

