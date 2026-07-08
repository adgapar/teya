---
date: 2026-07-08T12:00:00Z
topic: "Household onboarding + settings + contextual profile"
tags: [onboarding, settings, household, persona, room, languages]
status: design-locked
---

# Household onboarding + settings + contextual profile

_Status: design locked (2026-07-08), prototype approved, **not yet ported to the app**._
_Prototype (design source of truth): the "Teya · Onboarding & Settings" Claude artifact — guided
form, aliases model, Voxtral-13 languages. Iterate UI there first (see the prototype-in-artifacts
preference), then implement in Compose._

> **Next session — start here.** Design is fully locked; build from the "File-by-file (port checklist)"
> at the bottom. The only two references you need are this plan + the prototype artifact. No code is
> ported yet beyond the already-committed particle face and the removed dev status indicator.
> Optional first step: prototype the **landscape two-pane Admin** as an artifact before writing it.

## Goal
A guided first-run onboarding + editable Settings that capture a **household profile**, and wire it
into Teya so she's contextual: knows the family (names + what they're called), the languages spoken,
and home. Also lays the schema for future per-person **memory/learning**.

## Locked decisions
- **Onboarding = guided form** (extends `SetupActivity`, the LAUNCHER). Not conversational (STT isn't
  reliable before language is set — chicken/egg). Steps: 1) API key · 2) household members ·
  3) languages · 4) confirm home location → save → MainActivity.
- **Member fields:** first name, last name (optional), **aliases[]** (what the family calls them:
  "Dad", "Papa", "Babcia"), email (optional), phone (optional). **No role dropdown** — aliases
  replace it (roles are speaker-relative; aliases are the words Teya listens for).
  - Names are exact/unambiguous. Shared nicknames ("Dad" = two people) → Teya **asks which one**.
    Full speaker-relative auto-resolution needs per-speaker voice ID (deferred; roadmap stretch).
- **Languages:** the **13 Voxtral STT languages** only — English, Spanish, French, German,
  Portuguese, Italian, Dutch, Hindi, Arabic, Russian, Chinese, Japanese, Korean (this order).
  Multi-select. **9 are speakable** (Voxtral TTS: EN, FR, ES, PT, IT, NL, DE, HI, AR — via zero-shot
  voice cloning / cross-lingual / code-mixing); **RU, ZH, JA, KO are understand-only** (in STT's 13,
  not TTS's 9). Flag speakable with 🔊. STT auto-detects, so the pick is UX + reply-voice choice, not a
  hard API param.
- **Home location:** from GPS/ambient (already have it). **Confirm, don't type** — no manual city
  field. Onboarding shows detected location to mark as "home".
- **Storage = native Contacts + local Room** (mirrors Calendar-events-in-Android-Calendar): household
  **members live in `ContactsContract`** — sync to Google Contacts if the device has an account
  (transparent/editable anywhere, zero setup) and they *are* the call list. Teya's private brain —
  **KNOWN personas + memory + extra aliases** — lives in **Room**. v1 builds members-in-Contacts +
  the Room schema; KNOWN/memory populate with the memory feature.
- **Post-setup screen is "Admin", not "Settings"** — a management console: review/edit household
  members, `KNOWN` people, **memory records**, languages, home, and API key. v1 = household +
  languages + home + API; the People + Memory review sections land with the memory feature.
- **Orientation per screen:** onboarding (`SetupActivity`) is **portrait** (handheld, pre-mount); the
  main face is **landscape** (wall, locked). **Admin defaults to landscape** (opens in-place on the
  wall) but has a **manual rotate toggle to portrait** — so you can unmount for heavier text entry.
  Admin layout is **responsive**: two-pane (section list ↔ detail/editor) in landscape, single-column
  stacked in portrait. Use an explicit in-app toggle (`setRequestedOrientation`), NOT sensor
  auto-rotate (the wall device is physically fixed and shouldn't flip on its own).
- **Entry to Admin from the wall face:** **short tap = talk** (voice loop, as now), **long-press
  ~800 ms = open Admin.** No visible button (drop the current corner gear) — clean display, self-gated
  from kids/guests; parent-PIN can layer on later. Port: `combinedClickable(onClick = trigger voice,
  onLongClick = launch Admin)` in MainActivity; remove the gear `IconButton`.

## The language/TTS trap (hard requirement)
The LLM will generate any language (observed: it produced Russian, then TTS failed — Russian has no
TTS voice). TTS speaks **9** (EN, FR, ES, PT, IT, NL, DE, HI, AR); RU/ZH/JA/KO are understand-only.
So the profile context block MUST state both halves and constrain output:
> "The household speaks English, Spanish, Russian. You understand all of them. You can speak English
> and Spanish, but NOT Russian. Reply in the language the user used if you can voice it; otherwise
> English. Never answer in Russian, Chinese, Japanese, or Korean."
Derive the speakable set at runtime as (household languages ∩ TTS-9). Prevents the
generate-untts-able-text → TTS-fail bug.

## Storage: Contacts (members) + Room (Teya brain)

**Members → `ContactsContract`** (universal Android provider; syncs to Google Contacts, zero setup,
feeds calling):
- Fields: StructuredName (first/last), Phone, Email, Nickname (primary alias). Seed at onboarding via
  a `ContentProviderOperation` batch; read back for the roster + calling.
- **Account nuance:** `ContactsContract` exists on every Android device, but a contact belongs to an
  *account*. Write members under the device's **Google account** raw-contact when present → they sync
  to Google Contacts (transparent/editable on the web). No Google account → fall back to the local
  "device" account (works fully, just not web-visible). Pick the target account at write time
  (`AccountManager`); this is the one thing that "depends" on the device.
- Supersedes the planned Room `Contact` allowlist for members — the call feature reads these contacts
  (still exact-match + number validation per C1–C3). `READ/WRITE_CONTACTS` (pre-granted).
- Alias nuance: Contacts Nickname is single + transparent; the **full alias list** (STT "listen-for"
  words) is Teya-specific → keep in Room (`contact_extra`), keyed by contact `lookupKey`.

**Teya brain → Room** (`safety/TeyaDatabase`, bump v1→v2 with `Migration(1,2)`, CREATE TABLE only —
don't destroy the existing allowlist):
```kotlin
@Entity(tableName = "persona")            // KNOWN people captured by voice ("remember Uncle Bob")
data class Persona(@PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String, val aliases: String = "")            // aliases CSV
@Entity(tableName = "memory_entry")       // a fact; subject is a contact, a persona, or general
data class MemoryEntry(@PrimaryKey(autoGenerate = true) val id: Int = 0,
    val subjectType: String = "GENERAL",  // CONTACT | PERSONA | GENERAL
    val subjectKey: String? = null,       // contact lookupKey, or persona id, or null
    val text: String, val addedAt: Long)
@Entity(tableName = "contact_extra")      // Teya augmentation for a household contact
data class ContactExtra(@PrimaryKey val lookupKey: String, val aliases: String = "")
```
- DAOs: `PersonaDao`, `MemoryDao`, `ContactExtraDao`. Existing `Contact` allowlist stays for now.
- Languages + `homeConfirmed` → `ConfigManager` (CSV / bool), household-level.

## Wiring into the agent
- New `household/HouseholdManager` exposes:
  - `members()` — read from Contacts (+ aliases from `contact_extra`); `languages()` from ConfigManager;
    personas/memory from Room.
  - `profileContextBlock(): String` — builds the system-prompt block: roster with names + aliases,
    languages understood, and the **reply-language directive** above.
- `HarnessService.buildLiveContext()` appends `profileContextBlock()` (same mechanism as
  time/location ambient context). Rebuilds each turn so Settings edits apply with no restart.
- STT: `transcribe()` currently passes no language (Voxtral auto-detects) — leave as-is for now;
  the reply-language control is via the prompt directive, not the STT param.
- TTS voice: keep `fr_marie_happy` default; live voice picker from `GET /audio/voices` is a separate
  backlog item.

## Scope
**v1 (this build):** Person table (HOUSEHOLD) + PersonDao + DB migration; onboarding wizard
(SetupActivity, 4 steps); Settings editor (household CRUD, languages, home confirm, API key);
HouseholdManager + profile context block + reply-language directive wired into buildLiveContext.

**Deferred:** KNOWN persons + voice "remember this person" tool + MemoryEntry population; call-allowlist
seeding from members (C1); conversational onboarding; live voice picker; per-speaker voice ID (relative
nickname resolution); language-learning mode + a second TTS provider for ES/RU/pt-BR (roadmap).

## File-by-file (port checklist)
1. `household/ContactsRepository.kt` — read/write household members via ContactsContract (account
   selection for Google-sync; seed at onboarding; query roster + for calling).
2. Room: `Persona`, `MemoryEntry`, `ContactExtra` entities + DAOs; `TeyaDatabase` v2 + `Migration(1,2)`.
3. `harness/ConfigManager.kt` — `languages` (CSV/JSON) getter/setter; optional `homeConfirmed`.
4. `household/HouseholdManager.kt` — members/languages accessors + `profileContextBlock()`.
5. `SetupActivity.kt` — multi-step wizard (port from the artifact flow/copy).
6. `SettingsActivity.kt` — add Household / Languages / Home sections (reuse member editor).
7. `harness/HarnessService.kt` — append profile block in `buildLiveContext()`; construct DB with
   migration; inject HouseholdManager.
8. Verify build (`./gradlew assembleDebug --offline`), install, test on device.
