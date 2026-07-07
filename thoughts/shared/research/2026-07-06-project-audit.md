---
date: 2026-07-06T00:00:00Z
topic: "Teya Project Audit — full-project code review"
tags: [audit, android, telephony, security, compose, concurrency]
status: complete
---

# Teya — Project Audit (2026-07-06)

Full-project review of the Android app (written by a prior coding agent), across four domains:
telephony/safety, config/secrets/build, UI/Compose, and service/concurrency/networking.
Findings verified against the code (grep-confirmed where marked ✓).

## TL;DR — three themes + one broken animation

1. **The core feature does not work end-to-end.** Placing an allowlisted call — the product's
   entire point — cannot happen today: the allowlist is never populated, the app is never made the
   default dialer, and the name→number matching is unsafe. It currently *fails closed* (every call
   denied), but two of the bugs are one edit away from silently *failing open*.
2. **Security is not production-ready.** The encrypted API-key store silently falls back to
   plaintext, `allowBackup=true` can exfiltrate that plaintext, user speech is logged, and the
   release build isn't shippable (no minify, missing ProGuard file, no signing).
3. **Always-on robustness gaps.** No HTTP timeouts, no re-entrancy guard, and native/HTTP resources
   are never released — each of these specifically bites a device meant to run 24/7.
4. **The orb animation is actually broken** (frozen/jittery) and burns power 24/7.

Good news: the two openWakeWord backbone models are Apache-2.0; most fixes are small and local.

---

## CRITICAL

**C1 — Call feature is dead: the allowlist is never populated.** ✓
`ContactAllowlistManager.addContact()` is the only write path and is *never called* anywhere (no
setup/settings UI, no DB seed). `findByName()` always returns null → every call denied. The child
can never call Dad.
*Fix:* add a parent-gated contact-management UI (or a seeded DB) that calls `addContact`.

**C2 — App is never the default dialer; `TelecomManager.placeCall` won't route.** ✓ (no `RoleManager`/`ROLE_DIALER` anywhere)
`placeCall` needs the app to be default dialer + a registered `PhoneAccount`, or to fall back to
`ACTION_CALL`. Neither exists. `TeyaInCallService` is declared correctly but the system never binds
it (only binds the default dialer), so it's a logging-only stub. `MANAGE_OWN_CALLS` is declared but
unused (no self-managed `ConnectionService`).
*Fix (MVP):* use `Intent.ACTION_CALL` + `CALL_PHONE`. *Fix (real):* request `ROLE_DIALER` via
`RoleManager.createRequestRoleIntent` at setup and implement call routing; else delete the InCallService.

**C3 — Allowlist matching is unsafe and bypassable (safety-critical).** ✓ (`ContactDao.kt:12` uses `LIKE ... LIMIT 1`)
`WHERE name LIKE :name` is case-insensitive and treats `%`/`_` in the model-supplied name as
wildcards; `LIMIT 1` with no `ORDER BY` returns a nondeterministic row. A name of `"%"` matches an
arbitrary contact. Worse, `isAllowed(name)` and `getPhoneNumber(name)` are two decoupled queries
(TOCTOU) — the safety decision is on the name but the dialed number comes from a separate lookup,
with no number-format validation. The security boundary should be the *number*.
*Fix:* single lookup returning the `Contact`; `WHERE name = :name` (exact, explicitly normalized);
dial that row's number; validate number format before dialing.

**C4 — API key silently downgrades to plaintext.** ✓ (`ConfigManager.kt:21-24`)
Any exception building `EncryptedSharedPreferences` (Keystore corruption after restore/OS upgrade,
`AEADBadTagException`) is caught and the code writes the key to a plaintext `MODE_PRIVATE` file —
no user-visible signal.
*Fix:* never fall back to plaintext; on failure, wipe the corrupt keyset and re-prompt for the key.

**C5 — No HTTP timeouts → a stalled request wedges the agent forever.** ✓ (no `HttpTimeout`)
The Ktor/OkHttp client installs only ContentNegotiation + Logging. A stall in STT/chat/TTS blocks
the coroutine indefinitely; the UI sticks in LISTENING/THINKING and the wake-word recorder is never
resumed (the `listenForCommand` `finally` runs on return/throw, not on a hang). One flaky request
permanently disables an always-on device.
*Fix:* `install(HttpTimeout){ request/connect/socket }` (+ optional `HttpRequestRetry`).

**C6 — No re-entrancy guard on `handleVoiceTrigger`.** ✓ (`HarnessService.kt`)
Every orb tap (`ACTION_TRIGGER_VOICE`) and every wake-word detection launches a new
`handleVoiceTrigger`. Overlap (tap while listening, or "hey jarvis" during the "Yes?" prompt) →
two `AudioRecord`s on the mic + interleaved TTS + interleaved wake-word stop/start. Most likely
real-world crash/garble path.
*Fix:* `AtomicBoolean`/`Mutex` — `if (!busy.compareAndSet(false,true)) return`, reset in `finally`.

---

## HIGH

**H1 — `allowBackup="true"` can exfiltrate the plaintext-fallback key.** ✓ (`AndroidManifest.xml:16`)
The encrypted blob is useless off-device (Keystore master key isn't backed up — also a restore
data-loss bug), but the C4 plaintext fallback file restores intact.
*Fix:* `allowBackup="false"` (single-purpose device), or exclude both prefs via `dataExtractionRules`.

**H2 — User speech (PII) + API error bodies logged to Logcat.** (`MistralClient.kt`, `HarnessService.kt`)
Transcripts, TTS text, tool args, and error bodies are logged. Household voice data readable via ADB.
Note: the Ktor `Logging` plugin at `INFO` does *not* leak the `Authorization` header/body — that's fine.
*Fix:* gate content logs behind `BuildConfig.DEBUG`; strip in release; keep Ktor at NONE in release.

**H3 — TFLite Interpreters never closed → native memory leak.** ✓ (no `close()`)
`melspec`/`embedding`/`wakeword` + mapped model buffers leak on every service destroy/recreate
(`START_STICKY` guarantees recreation).
*Fix:* add `WakeWordEngine.release()` closing all three; call via `VoicePipeline.stop()` in `onDestroy`.

**H4 — `HttpClient` never closed.** ✓
OkHttp dispatcher/connection-pool threads leak on each service teardown.
*Fix:* close the client (or `MistralClient.close()`) in `onDestroy`.

**H5 — Wake-word worker Thread never joined; `stop()`/`start()` races the read loop.** (`WakeWordEngine.kt`)
`start()` spawns an anonymous `Thread` with no reference; `stop()` releases/nulls the `AudioRecord`
while the worker may be mid-`read()`. The try/catch + null-safety prevents crashes, but the fast
stop→start on every command leaves a brief two-readers-one-mic window and dropped detections.
*Fix:* keep the Thread in a field; `join(timeout)` in `stop()` before releasing AudioRecord.

**H6 — Release build not shippable.** ✓ (`isMinifyEnabled=false`, `proguard-rules.pro` MISSING, no signing config)
No shrinking/obfuscation (full symbols in the APK), the referenced ProGuard file doesn't exist, and
there's no release signing config (`assembleRelease` will fail).
*Fix:* create `app/proguard-rules.pro` with keep rules (kotlinx.serialization models, Ktor, TFLite),
enable `isMinifyEnabled`+`isShrinkResources`, add a signing config.

**H7 — LiteRT dependency drift.** ✓ (`build.gradle.kts` hardcodes `litert:1.4.1`; catalog says `2.1.6`; `litert`/`litert-api` aliases unused)
Three inconsistencies: hardcoded version bypasses the catalog, catalog version is unused/misleading,
and the code relies on the legacy `org.tensorflow.lite.Interpreter` surface that the LiteRT artifact
happens to re-export (fragile across bumps).
*Fix:* one source of truth — add `libs.litert` alias and use it, or delete dead entries; pin deliberately.

**H8 — Orb "waves" animation is frozen/jittery.** (`AgentFace.kt` ~120-134)
Wave phase is computed from `System.currentTimeMillis()` inside the `Canvas` draw lambda —
not Compose-observed, so it never drives redraw; it only samples incidentally when the `pulse`
InfiniteTransition invalidates. The listening/speaking feedback looks broken.
*Fix:* drive the phase from an `InfiniteTransition.animateFloat` and pass it into the Canvas.

**H9 — All infinite animations + full-screen gradient redraw run 24/7, even IDLE.** (`AgentFace.kt`)
`pulse`/`rotation` run unconditionally; `pulse` is read every state so the full-screen gradient
Canvas re-rasterizes every frame forever (IDLE is the dominant state). Battery, heat, OLED burn-in.
*Fix:* gate `rotation` behind THINKING; slow/static IDLE; consider dim/screensaver after inactivity.

**H10 — Tool-arg parsing assumes `Map<String,String>`; non-string values silently drop the tool call.** (`MistralClient.kt:110`)
`decodeFromString<Map<String,String>>` throws on `{"name":"Dad","confirm":true}` → caught →
`emptyMap()` → `place_call` gets a null name → misfires/denied.
*Fix:* decode to `JsonObject` and coerce values (`jsonPrimitive.contentOrNull`).

**H11 — No runtime permission recheck at call time.** (`TelephonyActuator.kt`)
`CALL_PHONE` can be revoked after startup; `placeCall` has no `checkSelfPermission` guard/try-catch →
`SecurityException` swallowed as a generic error, no spoken feedback.
*Fix:* check permission + wrap in try/catch; return a typed failure.

---

## MEDIUM

- **M1 — FGS `phoneCall`/`microphone` preconditions + swallowed `startForeground`.** On API 34+
  (targetSdk 35) a `microphone` FGS needs `RECORD_AUDIO` already granted, and `phoneCall` needs the
  dialer role, or `startForeground` throws. It's wrapped in a try/catch that only logs → the service
  runs *not* foregrounded and gets killed, silently disabling the agent. *Fix:* verify permissions/role
  before starting; don't swallow — surface and stop. Start `microphone` only; add `phoneCall` transiently.
- **M2 — Per-frame allocations in the Canvas draw scope** (`AgentFace.kt`): brushes/offsets/strokes/
  `.copy()` allocated every frame → sustained GC on a 24/7 device. *Fix:* `remember`/hoist them.
- **M3 — Service→UI state via BroadcastReceiver is lossy + state lost on config change.**
  (`MainActivity.kt`) No `configChanges`, so locale/font/density changes reset state to IDLE; broadcasts
  sent while stopped are dropped and never replayed. *Fix:* a process-level `StateFlow` repository the
  Service writes and the Activity `collectAsStateWithLifecycle`s.
- **M4 — `MediaPlayer.prepare()` synchronous on `Dispatchers.Main`** (`VoicePipeline.kt`): can block/ANR,
  and a throw outside the listeners can leave the continuation unresumed (hang). *Fix:* `prepareAsync()`
  off-Main; resume in a catch.
- **M5 — Service `CoroutineScope` defaults to `Dispatchers.Main`** (`HarnessService.kt:35`): fragile;
  one future blocking call = ANR. *Fix:* `Dispatchers.Default`/`IO`, switch to Main only for UI/broadcast.
- **M6 — Fixed temp filenames** `command.wav`/`tts_output.mp3`: race under concurrency (moot after C6).
  *Fix:* unique temp files; delete after use.
- **M7 — Single-turn agent; no conversation history; tool result never returned to the model.**
  (`BrainClient.processText(String)`) Follow-ups ("call her back") can't work; the model can't
  confirm/repair after a tool call. *Fix:* pass a bounded message list; feed `role:"tool"` results back.
- **M8 — No Room migration strategy** (`TeyaDatabase`): a schema bump crashes. *Fix:* add migrations
  or `fallbackToDestructiveMigration` for now. (DAOs are `suspend`, so no current main-thread ANR.)

## LOW

- **L1 — `startAgentLoop()` re-runs on every non-trigger `onStartCommand`** — only saved by the
  `isRunning` guard in `WakeWordEngine`. Add a `loopStarted` guard in the service.
- **L2 — `TeyaTheme` is a *light* Material scheme over a hard-dark UI** — theme is dead code; colors
  are scattered literals. Define a dark scheme + named state colors; consume `MaterialTheme`.
- **L3 — Accessibility + shipped debug UI**: orb has no `contentDescription`/role; the dev overlay is
  low-contrast and unconditionally shown. Add semantics; gate the overlay behind `BuildConfig.DEBUG`.
- **L4 — Orb clickable during an active turn** (no debounce/disable): stray taps spam the service.
  Ignore clicks unless `state == IDLE`.
- **L5 — API-key scrubbing regex** (`MistralClient.kt:26`) silently rewrites the secret (triple-trim);
  validate at entry instead.
- **L6 — Denial TTS conflates all failures** ("not on the allowlist") and blank `name` hits the DB.
  Use a typed result; guard blank name.
- **L7 — `synthesizeSpeech` swallows non-OK responses** — no signal to the loop. Propagate a result.
- **L8 — Hardcoded model IDs / base URL / user-facing strings** — centralize in config/resources.
- **L9 — No `networkSecurityConfig`** — optional HTTPS-only/cert-pin hardening for a device that only
  talks to `api.mistral.ai`.

---

## Suggested fix order

1. **Make the loop robust (quick, high-leverage):** C6 re-entrancy mutex, C5 HTTP timeouts,
   H3/H4 resource release, H10 tool-arg parsing. Small edits; stop the 24/7 device from wedging/leaking.
2. **Make the call feature actually work:** C1 contact UI/seed, C2 dialer role or `ACTION_CALL`,
   C3 exact-match single-lookup + number validation, H11 permission recheck. This is the product.
3. **Security pass before any real use:** C4 no-plaintext-fallback, H1 `allowBackup=false`, H2 gate PII logs.
4. **UI correctness/efficiency:** H8 fix wave animation, H9 idle-gate animations, M3 StateFlow, L3 gate overlay.
5. **Release readiness (later):** H6 ProGuard+signing+minify, H7 LiteRT catalog, M1 FGS preconditions.

Note: several fixes (C3, H11, L6) touch the same telephony path — do them together. The wake-word
classifier is still `hey_jarvis` (CC BY-NC-SA, dev-only) — see `THIRD_PARTY_MODELS.md`.
