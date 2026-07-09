---
date: 2026-07-08T15:00:00Z
topic: "Memory: persona blocks, categories, forgetting-curve decay + a nightly dreamer"
tags: [memory, dreaming, decay, persona, room, rag, embeddings, admin, mistral-small]
status: implemented
---

# Memory — persona blocks, category-driven decay, and a nightly "dreamer"

_Status: **fully implemented** (2026-07-09), including slice 5b (episodic capture + LLM consolidation)
and a dream **audit log** in Admin. Live-verified on-device: persona write/recall, general-pool RAG
(`search_memory`), episodic capture. Plus post-review hardening: searchable cooled memories,
safe `forget`, instant recall re-promote, and persona-memory survival across household saves
(lookupKey remap). Remaining follow-ups are minor (see below). Builds on the
dormant Room schema (`MemoryEntry` / `Persona` tables, seeded in `Migration(1,2)`, currently
read/written by nobody) and the render-block pattern already proven by
`HouseholdManager.profileContextBlock()`._

## Goal

Give Teya durable, **human-shaped** memory: she knows lasting facts about the family, learns and
updates preferences, and — like a person — **remembers today in full detail, last week as key
points, and a month ago only the gist**. Memory that grows over a year must not blow up the context
window, and we must be able to **inspect and correct** it from Admin.

The centrepiece the family will feel: **"what do you remember about Grandma?"** — persona-focused
memory is the powerful thing here.

## The mental model (five context layers; memory is the empty one)

Teya's system message today already carries four layers. Memory is the fifth and only dead one:

| Layer | Lifetime | Where | Sent how |
|---|---|---|---|
| Identity (persona + tool specs) | static | `TeyaPersona` / `AgentTools` | top of system msg |
| Profile (members, aliases, languages) | slow, persisted | Contacts + Room | `profileContextBlock()` |
| Ambient (time, location, timers, today) | per-turn | recomputed | live-context block |
| Conversation (the turns) | per-session, RAM | `history` list | message array |
| **Memory (durable family facts)** | **cross-session** | **`MemoryEntry` (unused)** | **nothing yet** |

Key framing: **memory is authoritative facts, not dialogue → it renders into the system block
(alongside profile/ambient), never stitched into the message history.**

## Locked decisions

- **Load small, fetch large.** Two retrieval mechanisms, split by *how you find a memory*:
  - **Persona memory** (about a household member) is retrieved by **association** (`WHERE subjectKey
    = member`) and **assembled into context every turn** — a family is a small, fixed set, so this
    stays a few hundred tokens. This is "what you remember about X."
  - **General pool** (not tied to a person, or cooled-off overflow) is retrieved by **similarity** —
    a `search_memory(query)` tool doing RAG. Not always loaded, so it can grow all year without
    bloating context.
- **A "block" is a render-time assembly, not a stored self-editing object.** We do **not** adopt
  Letta's size-limited, agent-edited block objects. Storage is append-only rows; a "block" is
  `SELECT … render` — exactly what `profileContextBlock()` already does. This is what lets us skip
  the heavyweight consolidation machinery.
- **Append-only writes + `forget`.** No in-place `update` tool. A changed preference is a new, strong,
  recent row; the old one decays and cools out of the always-loaded set. `update` = forget + remember.
- **Category drives semantics AND durability** (see below). This is the field that says *what a
  memory is* and *how fast it should fade / whether it may be superseded*.
- **Decay is the architecture, not a feature.** Every row has a **strength** on a forgetting curve;
  **access reinforces it** ("use it or lose it"). Where a memory lives — always-loaded, RAG-only, or
  forgotten — is a function of strength. This is what makes it *memory*, not a database.
- **Dreaming stays, but light.** A **nightly cron** (`WorkManager`, charging + idle — the wall device
  satisfies both ~3 AM) does: (1) **decay + re-tiering = deterministic math, no LLM**; (2)
  **consolidation = one `mistral-small` pass** that distils recent detailed episodic rows into durable
  facts and thins the detail. **No** tool-using consolidator, **no** `get_source_trace` (there is no
  trajectory here — `addedAt` is all the provenance we want).
- **`mistral-small` everywhere** (live voice + dreamer), **`mistral-embed`** for vectors. Never
  `mistral-large`. NB: `MistralClient` currently hardcodes `mistral-large-latest` in `processText` /
  `streamChat` — fix in this work.
- **Admin must expose memory** for review / correction / monitoring (new requirement — see below).
  We do not ship a memory system we can't inspect.

## Memory categories

The `category` field defines what a memory is and, through it, its **initial strength**, **decay
half-life**, **mutability**, and whether it's a **persona-block** candidate:

| Category | Example | Mutability | Decay | Loaded |
|---|---|---|---|---|
| `FACT` | "Sam is allergic to peanuts", "Grandma born in Kraków" | append + explicit correction only | **slow** (near-permanent) | persona block if about a member |
| `PREFERENCE` | "Dad likes his coffee black", "Mia hates cilantro" | **newest-wins**; older supersedes-by-decay | medium | persona block if about a member |
| `ROUTINE` | family: "pizza Fridays" · personal: "Dad runs every morning" | reinforced by repetition | slow while reinforced | general (family habit) or persona (personal habit) |
| `EPISODIC` | "Sam scored a goal Saturday" | consolidate then drop | **fast** (detail fades to gist) | recent only; dreamer distils |

`subjectType` (`CONTACT` | `PERSONA` | `GENERAL`) + `subjectKey` already exist and answer *who it's
about*; `category` answers *what kind it is*. Together they route a memory to a persona block vs the
general pool and set its decay behaviour.

## Storage / schema (Room v2 → v3)

Extend `MemoryEntry` (`household/HouseholdEntities.kt`) — additive `ALTER TABLE ADD COLUMN` migration
`Migration(2,3)`, defaults so existing (empty) data is untouched:

```
MemoryEntry(
  id, subjectType, subjectKey, text, addedAt,   // existing
  category: String       = "FACT",              // FACT|PREFERENCE|ROUTINE|EPISODIC
  strength: Float        = 1.0f,                 // current strength on the forgetting curve
  lastAccessedAt: Long,                          // reinforcement timestamp (init = addedAt)
  embedding: ByteArray?  = null,                 // float32 blob; general-pool RAG only
  tier: String           = "HOT",                // HOT (loaded) | COLD (archival/RAG) — dreamer-maintained
)
```

- **Vector search = brute-force cosine in Kotlin.** No vector DB / ANN index — hundreds-to-thousands
  of 1024-dim vectors is sub-millisecond; we'd need tens of thousands before it shows in a profiler.
  Embeddings are computed **on write** (and during dreaming), not per query on the hot path except the
  one query embed inside `search_memory`.
- `MemoryDao` grows: query by subject, by tier, cosine candidates (fetch HOT/COLD + embeddings),
  update strength/lastAccessedAt (reinforcement), the dreamer's batch re-tier, delete.

## Retrieval & the live tool surface

Three tools (`AgentTools.all` + `HarnessService.executeTool` + mention in `TeyaPersona`), following
[[create-needs-cancel]]:

- `remember(fact, about?, category?)` — append a row. `about` = a member (→ persona, `subjectType=
  CONTACT`) or omitted (→ general pool, gets embedded). Model classifies `category`.
- `forget(query, about?)` — the destructive inverse; the *only* way to truly delete.
- `search_memory(query)` — RAG over the general/COLD pool; **retrieval reinforces** the hits.

Persona blocks need no tool — they assemble every turn. A new **`MemoryManager`** (parallel to
`HouseholdManager`) owns writes, retrieval, reinforcement, and the **render into the context block**
(folded next to `profileContextBlock()` so the "what you remember about the family" section sits with
"who the family is").

## The dreamer (nightly cron)

`DreamWorker` (`WorkManager`, `setRequiresCharging(true)` + `setRequiresDeviceIdle(true)`, periodic
~daily). Survives reboot (unlike the exact `AlarmManager` alarms we use for timers — those stay for
must-fire events). Two stages, deliberately split:

1. **Decay + re-tier — deterministic, no API.** `strength(now) = strength · exp(−Δt / halflife(category))`
   since `lastAccessedAt`. Re-tier: `strength ≥ HOT_THRESHOLD` → HOT (loaded); below → COLD (RAG-only);
   `EPISODIC` below `DEAD_THRESHOLD` → pruned. (Same "code does the exact math, LLM does the
   language" principle as the expense tracker.)
2. **Consolidate — one `mistral-small` pass.** Read the last day's detailed `EPISODIC` rows → extract
   the key durable facts/preferences worth keeping → write them as `FACT`/`PREFERENCE` rows (to a
   persona block if about a member, else general) → thin/drop the raw episodic detail. This is
   "learn overnight, forget the details, keep the gist."

Cost: one small pass over a day's rows = pennies/night. Failure-safe: if the pass fails, decay still
ran; nothing is lost.

**Forgetting policy (locked):** `EPISODIC` detail is pruned for real once dead; **durable facts
(`FACT`/`PREFERENCE`/`ROUTINE`) never hard-delete on their own — they only cool to COLD/archival**
(still `search_memory`-able). Hard delete requires an explicit `forget` (voice) or Admin delete.
"Forgetting" = demotion, not destruction, unless a human says otherwise.

## Admin — Memory section (review / correct / monitor)

Extend the Admin console (`SettingsActivity`) — it already lists this as a planned section:

- Add `MEMORY("Memory")` to the `AdminSection` enum; a `MemorySectionBody` composable rendered by
  both `PortraitContent` (stacked) and `LandscapeContent` (two-pane) like the other sections.
- **Review:** list memories **grouped by subject** (per member, then General), each showing `text`,
  `category`, `strength`/`tier`, and `addedAt` / `lastAccessedAt`. This is the "what does Teya think
  she knows, and is any of it wrong" view.
- **Correct:** edit `text`/`category`, and **delete** a row (the manual `forget`). Editing is how a
  human fixes a mis-heard or wrong fact.
- **Monitor:** surface counts per tier / per category and total pool size, plus **last dream run**
  time + what it consolidated/pruned — so we can watch the decay + dreamer actually behaving and
  catch drift. (A "Run dream now" debug button for testing the cron without waiting for 3 AM.)
- PII note (audit **H2**): memory is the most sensitive on-device data (verbatim-ish family facts).
  Keep it on-device (app-private Room), never log contents, gate any debug dumps behind
  `BuildConfig.DEBUG`. Aligns with [[neutral-accounts-home-only]].

## Constraints kept in view (not solved here)

- **Household-level, not per-speaker.** No speaker ID (Voxtral doesn't expose it) — memory is the
  family's; `subjectKey` links a memory to *who it's about*, not *who said it*. Per-speaker
  attach/detach of persona blocks is the growth path once speaker-ID lands (roadmap stretch).
- **Persona blocks = always-load all members** for v1 (a family is 4–6 people). Attach-on-mention is
  a premature optimisation needing topic detection we don't have.

## Implementation slices (each independently shippable + testable)

0. ✅ **Schema + plumbing.** `Migration(2,3)`, `MemoryEntry` fields, `MemoryManager`, `MemoryDao`.
   (The `mistral-small` flip landed separately.) Live-verified.
1. ✅ **Persona memory live.** `remember` / `forget`; persona blocks assembled into context
   (always-load members); categories from the start. **Verified live** (allergy/Real-Madrid write → recall, no tool round-trip).
2. ✅ **Admin Memory section.** Review (grouped per member + General) / delete / monitor counts.
   Sequenced early on purpose. **Verified live.**
3. ✅ **General pool + RAG.** `mistral-embed` on write; `search_memory` tool; brute-force cosine;
   general pool made **search-only** (not always-loaded). **Verified live** (wifi-password write → `search_memory` recall).
4. ✅ **Decay + reinforcement.** `runDecay()`: strength = 0.5^(daysSinceLastAccess / halfLife),
   per-category half-lives (FACT ~perm / ROUTINE 120d / PREFERENCE 45d / EPISODIC 3d); re-tier
   HOT↔COLD at 0.5, prune dead EPISODIC (<0.05); recall resets strength to 1.0. *Built + compiling;
   visible cooling needs elapsed days.*
5. ✅ **The dreamer (deterministic half).** Nightly ~3 AM via **AlarmManager** — not WorkManager
   (avoids a new dep that would break `--offline`; reuses the timer-reentry pattern, `ACTION_RUN_DREAM`).
   Admin "Run dream now" + last-run monitor. *Built + compiling; pending a live run.*
5b. ✅ **Episodic capture + LLM consolidation.** End-of-session `mistral-small` summary → EPISODIC note
   (1–3 sentences, or NONE for trivia; embedded, 3d decay). The nightly dream then feeds notes added
   since the last run to `mistral-small`, which conservatively promotes durable facts/preferences/
   routines (`CATEGORY | SUBJECT | TEXT`), attributing to a member when named. Verified live (capture).
6. ✅ **Dream audit log.** Rolling, capped history of each dream run (cooled/pruned + what it learned)
   in `ConfigManager`, surfaced as "Recent dreams" in Admin; "Run dream now" fires the full dreamer.

## Remaining follow-ups (minor)
- Fully-incremental Contacts update (preserve lookupKeys instead of delete+reinsert+remap).
- Orphan cleanup for member-deleted rows; richer per-session capture tuning; consolidation dedup.
- On-device: exercise decay over real elapsed days; confirm the 3 AM `AlarmManager` fires while dozing.

## Open questions (non-blocking)

- Exact half-lives / thresholds per category — tune on-device against real family use.
- Should the dreamer ever **surface** ("should I remember you do pizza Fridays?") or stay silent?
  Product-voice call, deferred.
- Semantic recall for **persona** blocks (currently association-only) — probably never needed; a
  member's rows are few and all loaded.
