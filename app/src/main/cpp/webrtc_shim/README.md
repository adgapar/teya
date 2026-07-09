# webrtc_shim — Teya build shims for the vendored AEC3 tree

**Not vendored WebRTC or Abseil source.** Everything under this directory is authored by Teya to
let the real vendored AEC3 sources (`../third_party/webrtc/`, pinned commit
`99de2d09036e61b72e4e6bba3ab09fedc40d18fe`, see `../third_party/webrtc/VENDORING.md`) compile
outside the full `gn`/Chromium build, without a network-fetched Abseil dependency, and without
pulling in Audio Processing Module machinery this mono/16kHz-only, single-mic build doesn't need.

`CMakeLists.txt` puts this directory **before** `third_party/webrtc` on the include path for the
`teya_aec3_core` target, so `#include "rtc_base/checks.h"` (etc.) resolves to the shim here instead
of the real vendored file of the same name. The real vendored files stay on disk untouched, for
provenance and for diffing against upstream if this module is ever re-vendored.

## What's here and why

| Path | Replaces | Why |
|---|---|---|
| `absl/strings/string_view.h` | Abseil's `absl::string_view` | Aliases to `std::string_view` — literally what upstream Abseil itself resolves to under `ABSL_USES_STD_STRING_VIEW` (any C++17 build). Avoids vendoring Abseil's much larger transitive header graph (`absl/base/config.h`, `absl/base/policy_checks.h`, `absl/strings/internal/*`, ...) for a single leaf type alias. |
| `absl/strings/match.h` | Abseil's `absl::StartsWith`/etc. | `api/field_trials_view.h` calls `absl::StartsWith`. Real, correct implementation on `std::string_view` — not a stand-in. |
| `absl/base/attributes.h` | Abseil's `ABSL_MUST_USE_RESULT` | Only macro the vendored tree references from this header (`rtc_base/swap_queue.h`); expands to the real GCC/Clang attribute. |
| `absl/base/nullability.h` | Abseil's `absl_nullable`/`absl_nonnull` | Pure static-analysis annotations upstream; defining them empty is faithful to what they already resolve to on toolchains without the corresponding Clang attributes. |
| `rtc_base/checks.h` | WebRTC's `RTC_CHECK`/`RTC_DCHECK` family | Upstream builds fancy `"a == b (1 vs. 2)"` failure messages via `absl::StrCat`/`absl::HasAbslStringify`. That's diagnostic formatting, not AEC3 logic — this shim keeps the exact macro names and the exact abort-on-failure contract, just formats the (optional) failure message with plain `ostringstream` streaming. It does **not** drop upstream's `rtc_base/numerics/safe_compare.h` dependency for the actual comparisons: `RTC_CHECK_EQ`/`NE`/`LE`/`LT`/`GE`/`GT` route through the real, unmodified, already-vendored `SafeEq`/`SafeNe`/`SafeLe`/`SafeLt`/`SafeGe`/`SafeGt` — Phase 3b found that comparing with plain `<`/`>=` silently promotes a negative `int` to a huge `size_t` (e.g. `DownsampledRenderBuffer::OffsetIndex`'s `RTC_DCHECK_GE(buffer.size(), offset)` with a negative `offset`, a perfectly normal real AEC3 call), false-firing a `CHECK` that should have passed. |
| `rtc_base/logging.h` | WebRTC's `RTC_LOG` | Upstream pulls in `task_queue_base.h`, `rtc_base/synchronization/mutex.h`, `rtc_base/time_utils.h`, etc. for pluggable/thread-safe global `LogSink` routing. This shim keeps `RTC_LOG(severity)` and writes straight to the Android log — same diagnostic output, none of the sink-registration machinery. |
| `system_wrappers/include/metrics.h` | WebRTC's `RTC_HISTOGRAM_*` | These feed Chrome's UMA telemetry pipeline — irrelevant with no UMA backend. No-ops (arguments still referenced so `-Wunused-*` doesn't fire). Purely telemetry, doesn't affect echo-cancellation behavior. |
| `api/environment/environment.h` | WebRTC's `Environment` DI container | Upstream aggregates a `Clock`, `TaskQueueFactory`, `RtcEventLog` and `FieldTrialsView`. Grepping the entire vendored aec3 tree confirms AEC3 core only ever calls `env.field_trials()` — this shim keeps only that accessor. |
| `modules/audio_processing/audio_buffer.h` | WebRTC's `AudioBuffer` | Upstream is the full APM buffer type: resampling, `StreamConfig` deinterleaving, `CaptureMixer`, QMF sub-band splitting for >16kHz. AEC3 core + `high_pass_filter.cc` only ever call `num_channels()`/`num_bands()`/`num_frames_per_band()`/`num_frames()`/`split_bands()`/`split_bands_const()`/`channels()`/`channels_const()`/`kSplitBandSize`. This shim is a real, correctly-behaving buffer for exactly that surface, under the explicit assumption `num_bands() == 1` (true for this plan's 16kHz-mono-only scope — see its own research note that AEC3 doesn't multi-band-split below 32kHz). |

## When this shim is NOT enough

If a later phase (3b, 4, or a future multi-band/stereo plan) needs one of the accessors/behaviors
these shims deliberately dropped (e.g. `Environment::clock()`, true multi-band `AudioBuffer`
splitting), that's the signal to extend the specific shim (or vendor the real file + its full
dependency slice) — not to silently make the shim "more real" without re-checking why it was
narrowed in the first place.
