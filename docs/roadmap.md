# Roadmap

What's next. What's already built is in [README.md](../README.md). Experiment trails stay in
[docs/experiments.md](experiments.md).

## Next

1. **Host the call inside Teya.** Today `place_call` fires `ACTION_CALL` and the platform dialer
   takes the screen. The only supported way to keep the call in her UI is `InCallService` while
   holding `ROLE_DIALER` (Teya becomes the default phone app). That means owning inbound ringing
   too — the SIM has a number, people call back. Needs a design doc in `thoughts/shared/plans/`
   before code: who answers, hang-up by voice, emergency calls handed back to the system.
   Overlay-over-the-dialer is a dead end (`TelecomManager.endCall()` is default-dialer-only since
   Android 9).
2. **`get_weather`** — Open-Meteo (no key) + device location, spoken.
3. **Calendar follow-ons** — (a) a spoken nudge ahead of an event ("football in 30 min", reuse the
   timer announce path); (c) leave-time from event location + ambient location.
4. **Security** — no plaintext key fallback, `allowBackup=false`, PII logs behind `BuildConfig.DEBUG`.
5. **Leaks** — close TFLite interpreters and `HttpClient` in `onDestroy`.

## Later

- Conversational first-run (v1 is a form because STT isn't reliable before language is set).
- KNOWN people + memory tools (`remember` / `recall` / `forget` for people outside the household).
- Export/backup of household + memory + config (then a factory-reset button, not before).
- Device state: battery, volume, DND.
- Smart home (BLE/Matter) — only if it needs no household account.
- STT bias toward member names; more wake-word validation / a mic array for whole-room.
- Language-learning mode; cloned voices.
- Onboarding "add person" last-name field jumps when closing the form.
- 16 KB page-size warning on vendored `libonnxruntime.so` / `libtensorflowlite_jni.so` (harmless on
  this 4 KB device, sideloaded).
- Voice picker persisted from `/audio/voices`.
- Re-open chime when the mic re-opens mid-conversation.
- UI: StateFlow instead of broadcasts.

## Constraint (still live)

Zero household setup. A keyless API or a native SDK with no account is fine. Banned: anything the
family has to configure (Twilio, hubs, per-service logins). Create tools ship with their cancel in
the same slice.
