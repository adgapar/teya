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

## Phase 3a resolution — Abseil surface, remaining Phase 1 gaps, and build config

Phase 1's dependency slice was **one level deep** from `aec3/BUILD.gn`'s own `deps` list, by
design — deferring the "does this actually compile" question to Phase 3a, where a compiler (not a
hand-read of the `gn` dependency graph) surfaces exactly what's missing. This section records what
that process found and how each gap was closed.

### Abseil surface — resolved

Grepping every vendored file (`grep -rho '#include "absl/[^"]*"'`) for the literal Abseil headers
the tree references turned up exactly eight:

```
absl/base/attributes.h            (rtc_base/swap_queue.h — ABSL_MUST_USE_RESULT)
absl/base/no_destructor.h         (rtc_base/logging.cc — not compiled, see below)
absl/base/nullability.h           (api/audio/echo_control.h, api/environment/environment.h)
absl/memory/memory.h              (rtc_base/experiments/struct_parameters_parser.h — not compiled, see below)
absl/strings/has_absl_stringify.h (rtc_base/checks.h, rtc_base/logging.h — not compiled, see below)
absl/strings/match.h              (api/field_trials_view.h — absl::StartsWith)
absl/strings/str_cat.h            (rtc_base/checks.h, rtc_base/logging.h, struct_parameters_parser.cc — not compiled, see below)
absl/strings/string_view.h        (widely used directly in aec3/*.cc, api/field_trials_view.h, rtc_base/experiments/field_trial_parser.*, ...)
```

**Decision: these are not vendored from upstream Abseil.** Real Abseil's own transitive header
graph for even one of these (`absl/base/config.h`, `absl/base/policy_checks.h`,
`absl/strings/internal/*`, `absl/numeric/int128.h` for `str_cat.h`'s numeric formatting, ...) is
much larger than this narrow, leaf-utility usage — and per this plan's `--offline` constraint,
there's no `FetchContent`-at-build-time option to fall back on if that subtree turns out to need
network resolution of its own. Instead, `../../webrtc_shim/absl/` contains small, real (not
stubbed) reimplementations of exactly the functions actually called:
- `absl::string_view` → aliased to `std::string_view` (literally what real Abseil resolves to under
  `ABSL_USES_STD_STRING_VIEW`, which any C++17+ build sets — not a simplification).
- `absl::StartsWith`/`EndsWith`/`StrContains` → real substring checks on `std::string_view`.
- `ABSL_MUST_USE_RESULT` → the real `__attribute__((warn_unused_result))` it expands to upstream.
- `absl_nullable`/`absl_nonnull` → empty (these are pure annotations; they already compile to
  nothing on toolchains lacking the corresponding Clang attributes).

`absl/base/no_destructor.h`, `absl/memory/memory.h` and `absl/strings/str_cat.h` needed by
`rtc_base/logging.cc` / `rtc_base/experiments/struct_parameters_parser.{h,cc}` /
`absl/strings/has_absl_stringify.h` needed by the original `rtc_base/checks.h` are **not shimmed at
all**, because none of those three files are compiled into `teya_aec3_core` — see below. See
`../webrtc_shim/README.md` for the full per-shim rationale table.

### Files replaced by Teya build shims (not vendored upstream implementations)

Confirmed via grep across the whole vendored aec3 tree that these files' *real* implementations
pull in Chromium-build-only machinery unrelated to AEC3's DSP (thread-safe log sink routing, UMA
telemetry, a Chromium-wide DI container, full-APM buffer resampling/mixing/multi-band splitting).
`../webrtc_shim/` (included on the compiler's `-I` path *before* this directory, so these override
the real files below of the same name without modifying them) provides narrow, real, behavior-
preserving replacements instead:

| Real vendored file (unchanged on disk, **not compiled**) | Shim | Real API surface actually needed (confirmed by grep) |
|---|---|---|
| `rtc_base/checks.h` (+ `checks.cc`, also not compiled) | `webrtc_shim/rtc_base/checks.h` | `RTC_CHECK`/`RTC_DCHECK` family, same abort-on-failure contract, message via `ostringstream` instead of `absl::StrCat` |
| `rtc_base/logging.h` (+ `logging.cc`, not compiled) | `webrtc_shim/rtc_base/logging.h` | `RTC_LOG(severity)`, `RTC_LOG_V(severity)` — straight to Android log, no `LogSink` registration |
| `system_wrappers/include/metrics.h` (+ `source/metrics.cc`, not compiled) | `webrtc_shim/system_wrappers/include/metrics.h` | `RTC_HISTOGRAM_BOOLEAN`/`_COUNTS_LINEAR`/`_ENUMERATION` — no-ops (UMA telemetry, no backend) |
| `api/environment/environment.h` | `webrtc_shim/api/environment/environment.h` | Only `env.field_trials()` — `.clock()`/`.task_queue_factory()`/`.event_log()` are never called by any vendored aec3 source |
| `modules/audio_processing/audio_buffer.{cc,h}` (`.cc` not compiled; also not compiling `splitting_filter.{cc,h}`, `three_band_filter_bank.{cc,h}`, or the never-vendored `capture_mixer`/`audio_view`/`channel_buffer`/`audio_util`/full `api/audio/audio_processing.h`, all only needed by the *real* `audio_buffer.cc`) | `webrtc_shim/modules/audio_processing/audio_buffer.h` | `num_channels()`, `num_bands()`, `num_frames_per_band()`, `num_frames()`, `split_bands()`, `split_bands_const()`, `channels()`, `channels_const()`, `kSplitBandSize` — under the explicit assumption `num_bands() == 1` (true for this plan's 16kHz-mono-only scope; the real file's QMF sub-band splitting only activates above 32kHz, per this plan's own sourcing research) |

`rtc_base/experiments/field_trial_list.{cc,h}`, `struct_parameters_parser.{cc,h}` and
`field_trial_units.{cc,h}` were vendored in Phase 1 (as part of the `field_trial_parser` dep) but
turned out to be dead weight: nothing in the compiled tree includes them (only
`rtc_base/experiments/field_trial_parser.{cc,h}` is actually referenced, by `echo_canceller3.cc`'s
`RetrieveFieldTrialValue`). They stay vendored-but-uncompiled for documentation purposes; deleting
them is not necessary for correctness.

### Newly vendored (real, unmodified) files — filling genuine Phase 1 gaps

These are real upstream files at the pinned commit that Phase 1's one-level-deep slice missed, and
whose real implementations (not shims — they're small, self-contained, and directly needed by files
already vendored) were fetched to close the gap:

- `rtc_base/numerics/safe_conversions.h` + `safe_conversions_impl.h` (needed by `field_trial_parser.cc`)
- `rtc_base/numerics/safe_compare.h` + `rtc_base/type_traits.h` (needed by the already-vendored `safe_minmax.h`)
- `rtc_base/platform_thread_types.h` + `.cc` (needed by `race_checker.{cc,h}`)
- `rtc_base/system/rtc_export.h` (needed by `api/field_trials_view.h`, `api/environment/environment.h`, `api/audio/echo_canceller3_config.h` — expands to nothing without `WEBRTC_ENABLE_SYMBOL_EXPORT`, which this build doesn't define)
- `rtc_base/system/unused.h` (needed by `rtc_base/cpu_info.cc`)

### Build configuration discoveries (recorded here since they're load-bearing for re-vendoring)

- **C++ standard is 20, not 17.** The plan's original "-std=c++17" assumption was wrong: the pinned
  commit uses `std::span` pervasively (`block.h`, `fft_data.h`, `render_buffer.h`,
  `apm_data_dumper.h`, ...), which is a C++20 library feature. NDK r27's clang supports C++20 fully;
  `CMakeLists.txt` sets `cxx_std_20` on `teya_aec3_core`.
- **`WEBRTC_HAS_NEON`** must be defined explicitly — real `gn` builds set it based on `target_cpu`,
  it isn't derived from compiler predefines the way `rtc_base/system/arch.h`'s
  `WEBRTC_ARCH_ARM_FAMILY` is. Selects the Ooura FFT's NEON kernel (mandatory on AArch64) over the
  x86/MIPS fallback paths.
- **`WEBRTC_ARCH_ARM64`** likewise must be defined explicitly (same reason) — selects the real
  hardware NEON `vsqrtq_f32` path in `aec3/vector_math.h` over the 32-bit-ARM Newton–Raphson
  software approximation, and the `gettid()`-based path in `platform_thread_types.cc`.
- **`WEBRTC_POSIX`** must be defined for `rtc_base/platform_thread_types.h`'s `PlatformThreadId`/
  `PlatformThreadRef` typedefs to exist at all (used by `race_checker.{cc,h}`).
- **`WEBRTC_ANDROID` + `WEBRTC_LINUX`** are defined, but scoped to `platform_thread_types.cc` only
  (via `set_source_files_properties`, not target-wide) — needed for that file's real, correct
  `gettid()`/`prctl()`-based implementation (the generic POSIX fallback doesn't compile: it
  `reinterpret_cast`s a 64-bit `pthread_t` to a 32-bit `pid_t`, which is ill-formed). Deliberately
  **not** applied target-wide: `rtc_base/cpu_info.cc`'s `WEBRTC_ANDROID` branch needs
  `<cpu-features.h>`, which isn't on the NDK's default include path (it's under the legacy
  `sources/android/cpufeatures/` standalone library) — and nothing compiled here actually calls the
  function that branch is in (`cpu_info::Supports(kNeon)`/`DetectNumberOfCores()` are unused by the
  vendored aec3 sources; confirmed by grep).
- **`WEBRTC_APM_DEBUG_DUMP=0`** — a real upstream build-time switch (not Teya-invented) that
  disables `ApmDataDumper`'s WAV-file debug-dump machinery, avoiding a further dependency on
  `common_audio/wav_file.h`/`rtc_base/string_utils.h`/`rtc_base/strings/string_builder.h`.

## Licensing summary

See `THIRD_PARTY_MODELS.md` at the repo root for the full per-dependency license table. Short
version: WebRTC/AEC3 and Abseil are both permissive (BSD-3-Clause, Apache-2.0 respectively); the
Ooura FFT is permissive but under its own distinct attribution-request text, not BSD/Apache.
