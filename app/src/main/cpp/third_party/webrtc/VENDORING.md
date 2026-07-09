# Vendored WebRTC AEC3 — Provenance

This directory contains a **hand-picked, AEC3-only slice** of Google WebRTC, vendored from the
public `webrtc.googlesource.com/src` mirror. It is **not** the full WebRTC Audio Processing Module
(APM) — no AGC, no noise suppression, no beamforming, no multi-channel support. Only the Acoustic
Echo Canceller v3 (`modules/audio_processing/aec3/`) and the specific dependency files it needs to
compile.

## Pinned commit

```
Commit:  99de2d09036e61b72e4e6bba3ab09fedc40d18fe
Branch:  refs/heads/main (at the time of vendoring — 2026-07-09)
Repo:    https://webrtc.googlesource.com/src
```

Resolved via:
```
curl -s "https://webrtc.googlesource.com/src/+refs/heads/main?format=JSON"
```

Individual files were pulled via the single-file text endpoint:
```
https://webrtc.googlesource.com/src/+/<commit>/<path>?format=TEXT   (base64-encoded blob)
```
and the AEC3 directory itself was pulled in bulk via the scoped-archive endpoint:
```
https://webrtc.googlesource.com/src/+archive/<commit>/modules/audio_processing/aec3.tar.gz
```
then filtered locally (unit tests / AVX2 / mocks / the optional neural residual echo estimator
stripped out — see below).

This is a **one-time, manual vendoring**. There is no build-time fetch (the project builds
`--offline`) and no auto-update mechanism. Re-vendoring against a newer WebRTC commit is a
deliberate, manual act: re-run the same fetch process against a new commit hash and update this
file.

## What was pulled

### `modules/audio_processing/aec3/` — the AEC3 core (120 files)

The entire `aec3` target's `sources` list from `aec3/BUILD.gn` at the pinned commit, **minus**:
- All `*_unittest.cc` files and the `mock/` subdirectory (test-only, per `aec3_unittests`
  target — not part of the shipped `aec3` target).
- The `aec3_avx2` target's sources (`*_avx2.cc`) — x86/x64-only SIMD variant; this project is
  arm64-v8a only (see plan's "What We're NOT Doing").
- The `neural_residual_echo_estimator/` subdirectory (its own `BUILD.gn`/`DEPS`/`.proto`) — not
  referenced by the core `aec3` target's own `sources` list; only its narrow API header
  (`api/audio/neural_residual_echo_estimator.h`, vendored separately below) is needed so
  `EchoCanceller3`'s constructor signature compiles. The concrete implementation is optional
  (nullable) per AEC3's public API — confirmed at vendoring time by checking that `aec3`'s own
  `sources` list never references anything under `neural_residual_echo_estimator/`.
- `BUILD.gn` itself is kept (not compiled — used only as a reference and as the input to
  `scripts/check_aec3_deps.sh`).

### Minimal dependency slice (one level deep from `aec3/BUILD.gn`'s own `deps` list)

Resolved by fetching each dependency target's own `BUILD.gn` and reading its `sources` (choosing
the non-`build_with_chromium`, Android-relevant branch where the target has conditional sources —
e.g. `rtc_base:logging` ships `../../webrtc_overrides/rtc_base/logging.{cc,h}` only under
`build_with_chromium`; we take the plain `logging.cc`/`logging.h` instead since this is a
standalone, non-Chromium build):

| `aec3/BUILD.gn` dep | Files vendored |
|---|---|
| `../../../rtc_base:checks` | `rtc_base/checks.cc`, `rtc_base/checks.h` |
| `../../../rtc_base:cpu_info` | `rtc_base/cpu_info.cc`, `rtc_base/cpu_info.h` |
| `../../../rtc_base:gtest_prod` | `rtc_base/gtest_prod_util.h` |
| `../../../rtc_base:logging` | `rtc_base/logging.cc`, `rtc_base/logging.h` |
| `../../../rtc_base:macromagic` | `rtc_base/thread_annotations.h` |
| `../../../rtc_base:race_checker` | `rtc_base/race_checker.cc`, `rtc_base/race_checker.h` |
| `../../../rtc_base:safe_minmax` | `rtc_base/numerics/safe_minmax.h` |
| `../../../rtc_base:swap_queue` | `rtc_base/swap_queue.h` |
| `../../../rtc_base/experiments:field_trial_parser` | `rtc_base/experiments/field_trial_list.{cc,h}`, `field_trial_parser.{cc,h}`, `field_trial_units.{cc,h}`, `struct_parameters_parser.{cc,h}` |
| `../../../rtc_base/system:arch` | `rtc_base/system/arch.h` |
| `../../../api:field_trials_view` | `api/field_trials_view.h` |
| `../../../api/audio:aec3_config` | `api/audio/echo_canceller3_config.cc`, `api/audio/echo_canceller3_config.h` |
| `../../../api/audio:echo_control` | `api/audio/echo_control.h` |
| `../../../api/audio:neural_residual_echo_estimator_api` | `api/audio/neural_residual_echo_estimator.h` (interface header only — see note above) |
| `../../../api/environment` | `api/environment/environment.h` |
| `../../../system_wrappers:metrics` | `system_wrappers/include/metrics.h`, `system_wrappers/source/metrics.cc` |
| `..:apm_logging` | `modules/audio_processing/logging/apm_data_dumper.{cc,h}` |
| `..:audio_buffer` | `modules/audio_processing/audio_buffer.{cc,h}`, `splitting_filter.{cc,h}`, `three_band_filter_bank.{cc,h}` |
| `..:high_pass_filter` | `modules/audio_processing/high_pass_filter.{cc,h}` |
| `../utility:cascaded_biquad_filter` | `modules/audio_processing/utility/cascaded_biquad_filter.{cc,h}` |
| `../aec3` internal `aec3_fft` → `common_audio/third_party/ooura:fft_size_128` | `common_audio/third_party/ooura/fft_size_128/ooura_fft.{cc,h}`, `ooura_fft_tables_common.h`, plus the arm64 NEON variant `ooura_fft_neon.cc` + `ooura_fft_tables_neon_sse2.h` (the x86 SSE2 and MIPS variants were **not** vendored — arm64-v8a only, per this plan's scope; NEON is mandatory on AArch64 so this variant, not the plain C fallback path, is what an arm64 build will actually want) |
| `//third_party/abseil-cpp/absl/strings:string_view` | **Not vendored in this phase.** Abseil lives in a separate git repo (fetched via `DEPS`, not part of `webrtc/src`), and per the plan, scoping + integrating Abseil is explicitly deferred to Phase 3a ("Scope the exact Abseil surface"). Documented here as a known gap, not an oversight. |

Also vendored for licensing/attribution:
- `LICENSE`, `PATENTS` (top-level WebRTC, BSD-3-Clause) at the root of this directory.
- `common_audio/third_party/ooura/LICENSE`, `common_audio/third_party/ooura/README.chromium` — the
  Ooura FFT's own distinct attribution-request license (Copyright Takuya Ooura, 1996–2001) — **not**
  BSD/Apache, kept separate on purpose (see `THIRD_PARTY_MODELS.md`).

BUILD.gn files for the above targets were also copied in (not compiled) so
`scripts/check_aec3_deps.sh` has something to parse and so future re-vendoring/Phase 3a work can
diff against the upstream dependency declarations without re-fetching:
`modules/audio_processing/aec3/BUILD.gn`, `rtc_base/BUILD.gn`, `rtc_base/experiments/BUILD.gn`,
`rtc_base/system/BUILD.gn`, `api/BUILD.gn`, `api/audio/BUILD.gn`, `api/environment/BUILD.gn`,
`system_wrappers/BUILD.gn`, `modules/audio_processing/BUILD.gn`,
`modules/audio_processing/utility/BUILD.gn`, `common_audio/third_party/ooura/BUILD.gn`.

## Known gaps — deliberately deferred to Phase 3a

Phase 1's dependency slice is **one level deep** from `aec3/BUILD.gn`'s own `deps` list — i.e. it
vendors the files each direct dependency target ships, but does not recursively chase *those*
targets' own further dependencies (e.g. `audio_buffer.cc`'s own deps on
`api/audio:audio_frame_api`, `common_audio`, etc.; `environment.h`'s own deps on
`rtc_event_log`/`task_queue`/`system_wrappers`). This is intentional, matching the plan's Failure
Mode / Rollback section: Phase 3a (compiling AEC3 + deps as a standalone static lib) is explicitly
where an incomplete Phase 1 slice is expected to surface as a missing-symbol/missing-header build
error and get fixed, rather than trying to hand-resolve the entire transitive `gn` dependency graph
up front without a compiler to check the work.

The exact Abseil header/target surface (`absl::strings:string_view` at the direct-target level,
plus whatever `rtc_base`/`api` pull in transitively) is **not yet scoped** — that is Phase 3a item 1
("Scope the exact Abseil surface"), to be recorded in this file once determined.

## Licensing summary

See `THIRD_PARTY_MODELS.md` at the repo root for the full per-dependency license table. Short
version: WebRTC/AEC3 and Abseil are both permissive (BSD-3-Clause, Apache-2.0 respectively); the
Ooura FFT is permissive but under its own distinct attribution-request text, not BSD/Apache.
