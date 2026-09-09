---
date: 2026-08-02T00:00:00Z
topic: "SMS as a second transport — talking to Teya when you're not standing in front of her"
tags: [sms, transport, messaging, telephony, harnessservice, household, security]
status: phase 1 (outbound) built 2026-09-09, not yet verified live; phases 2-4 not started
---

# SMS Transport: Teya's Async Channel

## Why this doc exists

Every Teya-owned store today is **write-only from outside the house**. You say "we need olive oil",
`add_to_shopping_list` puts it in `ShoppingListManager`'s SharedPreferences — and then it sits there.
The only reader is someone standing in front of the wall device asking out loud. The person who
actually needs that list is in a supermarket aisle three kilometres away.

The shopping list is just the first symptom. The same hole applies to `get_events`,
`query_expenses`, `search_memory` — Teya knows things that are useless at the moment they're needed,
because she has exactly one I/O surface and it's bolted to a wall.

**This doc is about the channel, not the list.** Once Teya can exchange text messages with household
members, every tool she already has becomes reachable from anywhere, with no per-tool work.

## The decision: native SMS

Settled, with reasoning, so we don't relitigate:

| Option | Verdict |
| --- | --- |
| **Native SMS** | **Chosen.** Zero household setup (everyone has a phone number, nothing to install). Bidirectional. Doesn't background Teya. `Member.phone` already exists. |
| Telegram/WhatsApp bot | Rejected on principle. `docs/roadmap.md`'s hard constraint bans per-service accounts the family must configure; "messaging = native SMS / installed-messenger intents". |
| Email (Gmail) | Deferred, different job. Outbound is easy; **inbound** means OAuth + Gmail API polling + refresh-token handling on-device — real setup, real failure modes, to get something worse than a text. Nobody reads email in a supermarket aisle. |
| Messenger share-intent | Rejected. One-way, and launching another app backgrounds Teya (roadmap: "stay resident — don't hand off the screen"). |

**Rough long-term split: SMS = conversation, email = artifacts.** If a weekly expense digest or
something you want to keep and search ever gets built, that's email's job. Not now.

### What we are explicitly NOT doing: per-tool gating on the SMS transport

Considered and dropped. A household has no internal need-to-know boundary — if you can walk up to
the wall and ask, you can ask by text. Gating `query_expenses` "for safety" when the only possible
recipient is the person whose expenses they are is theatre, and it would mean maintaining a second,
divergent tool registry forever.

The plaintext concern is real but it is **not** solved by tool gating — see Security below.

## Architecture: one agent, two transports

The insight worth protecting: this is **not a second agent**. It's the same brain, the same
`AgentTools`, the same stores, reached through a different pipe.

But the reusable core is smaller than it first looks. `HarnessService.respond()` (line 613) is
irreducibly voice-shaped: it streams the LLM token-by-token, cuts it at sentence boundaries
(`lastSentenceEnd`), hands each sentence to `voicePipeline.textToSpeech` while the next is still
generating, arms barge-in around it, and drives `AgentState` for the face. None of that means
anything for SMS.

What is genuinely shared, and what has to be built:

**Shared as-is** — `brainClient` · `AgentTools.all` · `executeTool()` · `buildLiveContext()` ·
`trimHistory()` · `ChatMessage` history · every manager (`ShoppingListManager`, `CalendarManager`,
`ExpenseManager`, `MemoryManager`, `HouseholdManager`)

**New** — a non-streaming sibling to `respond()`. Same tool-round loop (call brain → run tool calls →
feed results back → repeat up to `MAX_TOOL_ROUNDS`), but it returns a `String` instead of speaking,
with no streaming, no sentence splitting, no barge-in, no UI state.

The honest framing: extract the **tool-round loop** — the part that is transport-agnostic — and let
`respond()` and the new text path each wrap it in their own I/O. Do this as a deliberate refactor
(Phase 2) rather than by copy-pasting `respond()` and deleting the audio lines, or the two paths
will drift the moment a tool is added.

## Identity: who is allowed to talk to Teya

There are currently **two** sources of phone numbers, and this needs settling before code:

- `safety/ContactAllowlistManager` — Room table keyed by *name*, gates `place_call`. Roadmap item
  C1 is still "populate the allowlist"; it is effectively empty today.
- `household/HouseholdManager.members()` — real household members from native Contacts +
  `ContactExtra`, each with `phone` and `email`, populated by onboarding.

**Decision: household members are the SMS peer set.** They already have numbers, onboarding already
collects them, and "only household members" is exactly the boundary that makes the no-gating
argument above valid. `ContactAllowlistManager` stays what it is — the *call* safety gate — and the
two are not merged in this slice.

Consequence worth calling out: inbound SMS gives Teya **sender identity for free** (number →
`Member`), which is strictly stronger than the audio speaker-ID path (`SpeakerIdManager`). The
existing `HouseholdManager.speakerContextBlock()` plumbing can be fed from it directly, so
per-member context ("Dad is asking") works on the text transport from day one.

Number matching must be normalized, not string-compared — `PhoneNumberUtils.compare()` for the
pragmatic native version (lenient trailing-digit match), since a contact saved as `0612345678` and
an inbound `+33612345678` are the same person.

## Session semantics

Voice conversations end on silence (`FOLLOWUP_LISTEN_MS`). SMS has no silence. Turns can be four
hours apart, and — critically — **an SMS conversation outlives the process**, unlike every voice
conversation, whose `history` is a local in `runConversation()`.

So SMS needs its own session model:

- **Per-member session**, keyed by member. Two people texting concurrently are two conversations.
- **Idle timeout** (start at ~30 min) after which the next inbound message starts fresh history.
- **Persisted history**, because `HarnessService` can be killed between turns. Room, alongside the
  other stores.
- Reuse `trimHistory()` for the context window, and `captureEpisodic()` on session close so texted
  conversations feed memory the same way spoken ones do.

## Concurrency with the voice loop

`conversationActive` (an `AtomicBoolean`, line 105) guards re-entrancy. An SMS arriving mid-voice
turn must **queue, not drop** — an ignored text is invisible to the sender, who gets nothing back
and no explanation, which is worse than a slow reply.

The reverse also holds: a long tool round on the SMS path must not block the wake word. Text turns
run on their own coroutine, never touch `voicePipeline`, and never call `updateUiState` — the wall
face should not flicker into THINKING because someone texted.

Open: should an inbound text *ever* be spoken aloud in the house ("Dad texted: running late")? That
is a real feature and a real privacy decision, and it is **out of scope here** — this doc is
transport only. Noted so it isn't accidentally half-built.

## Persona: SMS is written, not spoken

`TeyaPersona.systemPrompt` is tuned for speech — short, spoken-sounding, no markup. Text wants
different shaping: a shopping list should arrive as lines you can scan, not as a sentence you'd say
out loud.

This needs a **transport-aware addendum** to the system prompt, and per the project's standing rule
it must be **generic and derived from config**, not hardcoded per-case — same discipline as the
reply-language directive. The addendum describes the medium ("you are replying in writing, to be
read on a phone screen"), it does not enumerate specific formats for specific tools.

Length matters too: an SMS segment is 160 GSM-7 chars (70 if any emoji forces UCS-2), and multipart
messages cost per segment. The prompt should carry a soft length target; the sender splits with
`divideMessage`/`sendMultipartTextMessage` regardless.

## Security

**Inbound sender spoofing is the real threat.** An allowlist on the sender number is
authentication-by-caller-ID, and SMS originating addresses are spoofable. Someone who spoofs a
household number can drive every tool Teya has — including `place_call`, `cancel_event`, and
`clear_shopping_list`.

Proportionate response for a home appliance, in order:

1. **Accept it for read/append operations.** The blast radius of a spoofed "add olive oil" is olive oil.
2. **Do not expose destructive or outward-facing tools on the inbound text path.** Not "gating by
   sensitivity" (rejected above) — gating by *irreversibility and reach*. `place_call`,
   `clear_shopping_list`, `cancel_event`, `forget` are a different risk class from reading a list,
   because their damage doesn't need the attacker to see any reply. This is the one carve-out.
3. **Rate-limit inbound**, per number. Cheap, and blunts both spoofing and a stuck sender loop.

**Carrier plaintext** is the other one: SMS transits the carrier in the clear and lands in their
logs, regardless of who receives it. Fine for olive oil; worth knowing before expense queries and
`search_memory` results are flowing over it routinely. No mitigation planned — recorded so the
tradeoff is a choice rather than a surprise. This is the strongest argument for eventually adding
email (TLS to Gmail) as the channel for anything substantial.

Per the no-PII rule: inbound bodies and sender numbers must **not** be logged outside
`BuildConfig.DEBUG` (roadmap H2, same treatment).

## Android specifics

- **Outbound**: `context.getSystemService(SmsManager::class.java)` (`getDefault()` is deprecated on
  API 31+). `divideMessage()` + `sendMultipartTextMessage()` for anything over one segment. Sent /
  delivered `PendingIntent`s so a failure surfaces instead of vanishing.
- **Inbound**: manifest-registered `BroadcastReceiver` on
  `Telephony.Sms.Intents.SMS_RECEIVED_ACTION`, parsed with `getMessagesFromIntent()`, multipart
  parts concatenated by originating address.
- **Teya does not need to be the default SMS app.** `SMS_RECEIVED_ACTION` + `RECEIVE_SMS` is enough
  to observe incoming messages; only `SMS_DELIVER` requires the default-SMS role. Sending needs only
  `SEND_SMS`. This is the same trap the call feature hit with `ROLE_DIALER` — no role needed here.
- **Permissions**: `SEND_SMS`, `RECEIVE_SMS`. Both are Play-Store-restricted, which is irrelevant —
  Teya sideloads (`.github/workflows/release.yml`).
- **Hardware check first**, same as the call feature: confirm the fresh SIM's plan actually allows
  SMS before assuming code is the blocker. If it doesn't, Phases 2–3 can still be built and tested
  by injecting synthetic inbound messages; only Phase 1's live send is gated on the SIM.

## Phases

Each phase ends at something observable, so a broken phase is caught before the next builds on it.

**Phase 1 — outbound only.** ✅ **Built 2026-09-09**, exactly as designed: `send_message(recipient,
body)` `ToolSpec` → `executeTool` branch → `TeyaPersona` mention, recipient via
`HouseholdManager.resolveMember`, sent by `messaging/SmsSender` (multipart-aware). Two things the
design didn't call: it shares the call path's number validation (one rule for "is this diallable"),
and the sent `PendingIntent` logging needed `buildFeatures.buildConfig` turned on for the first time.
Open question #4 answered in the persona: SMS has no inverse and the prompt says so outright.
*Checkpoint*: **not yet run** — "Teya, text me the shopping list" out loud at the wall → the text
arrives on a real phone. This alone closes the olive-oil hole in one direction.

**Phase 2 — extract the transport-agnostic tool loop.** Refactor only; `respond()` keeps its exact
current behavior on top of the extracted core.
*Checkpoint*: full voice conversation with tool calls and barge-in behaves identically to before.
No new feature ships in this phase — that's the point.

**Phase 3 — inbound.** Receiver → number→`Member` resolution → persisted per-member session →
text turn on the extracted core → SMS reply. Queueing against `conversationActive`, rate limit,
the irreversible-tool carve-out.
*Checkpoint*: text "what's on the shopping list?" from a household phone → correct reply by SMS,
with the wall device idle and its face never leaving IDLE. Then: "add olive oil" by text → visible
at the wall.

**Phase 4 — written-output shaping.** Transport-aware prompt addendum, length targets, session
idle-timeout tuning, `captureEpisodic` on text sessions.
*Checkpoint*: a texted list reads like a list; a texted answer reads like a message, not a
transcript of speech.

**Deferred — proactive push.** Teya texting unprompted (reminders, "you're near the shop"). Real
value, but it needs its own thinking about consent and frequency, and it should sit on a transport
that has proven itself first.

## Open questions

1. ~~**Does the SIM's plan send SMS?**~~ A SIM is now in the device (2026-09-09) and Phase 1 is
   built, so the design is no longer parked. **Still unconfirmed: whether the plan actually
   allows SMS** — that is the first thing to check when Phase 1's checkpoint fails, before
   reading any failure as a code bug.
2. **Speak inbound texts aloud in the house?** Deliberately out of scope; flagged so it isn't
   half-built by accident.
3. **Session idle timeout** — 30 min is a guess. Needs real use to tune.
4. **Does `send_message` need an inverse?** The standing rule is that every create/set ships with
   its cancel. SMS can't be unsent, so the rule doesn't apply literally — but the prompt should say
   so explicitly, or the model will improvise an "I've recalled that message" lie, exactly as
   calendar did before `cancel_event` existed.
