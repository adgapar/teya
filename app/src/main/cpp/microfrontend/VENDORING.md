# Vendored TFLite Micro "microfrontend" — Provenance

This directory contains the native audio feature extractor our custom **"hey teya"** wake-word
model (`hey_teya.tflite`, trained via Google's microWakeWord pipeline, int8, input shape
`[1, 2, 40]`) requires at inference time: a fixed-point mel-filterbank feature extractor
(`FrontendProcessSamples`/`frontend.c` et al.) from TensorFlow Lite Micro's
`tensorflow/lite/experimental/microfrontend/lib/`. It is **not** expressible as a portable TFLite
graph — no custom-op TFLite interpreter path exists for it on Android/Kotlin — so it has to be
compiled as native C/C++ and called via JNI. This is the first native (`app/src/main/cpp/`) module
in this app.

Two upstream projects are vendored here:
1. **`tflite-micro/`** — the microfrontend library itself, from `tensorflow/tflite-micro`.
2. **`kissfft/`** — `mborgerding/kissfft`, the FFT implementation the microfrontend library's own
   `kiss_fft_int16.cc` compiles internally (see "Why kissfft is vendored" below).

## Provenance / how this was assembled

Home Assistant's official Android Companion app solved exactly this problem, shipped and working:
[`home-assistant/android#6312`](https://github.com/home-assistant/android/pull/6312)
("Implement microwakeword detection", merged 2026-02-09) added a Gradle module
`microfrontend/` that JNI-wraps the same unmodified upstream C files vendored here. Their module is
the direct model for this one — file list, `FrontendConfig` parameters, the JNI wrapper shape
(`MicroFrontendWrapper.cpp`/`.h` + `MicroFrontend_jni.cpp`), and the `0.0390625f` output scale
factor are all taken from their code (read directly from their repo at commit
`3f045c4c9caddaf08dbb39205c70bcbad7a2ca06`, the PR's merge commit). They in turn credit
[`brownard/Ava`](https://github.com/brownard/Ava) as independent prior art for this same
JNI-wrapping approach — credited here too.

**One deliberate deviation from HA's approach**: HA's `CMakeLists.txt` pulls both `tflite-micro`
and `kissfft` via CMake `FetchContent` at *build time* (a live `git clone` against GitHub). This
repo builds `--offline` (see root `CLAUDE.md`) and follows a stricter vendoring convention
established by the (since-removed) WebRTC AEC3 module
(`app/src/main/cpp/third_party/webrtc/`, see `THIRD_PARTY_MODELS.md`): **pull the actual upstream
source once, at a pinned commit, into the repo tree**, not a build-time fetch. So the files below
were fetched directly from each upstream repo's GitHub API at the commits pinned below, rather than
relying on `FetchContent`. The NDK/CMake build step that will consume these files (a later,
separate step) should reference this local tree instead of adding `FetchContent_Declare` calls.

The exact frontend parameters (window 30ms/step 10ms, 40 channels, 125–7500Hz band,
noise-reduction `smoothing_bits=10/even=0.025/odd=0.06/min_signal_remaining=0.05`, PCAN
`strength=0.95/offset=80.0/gain_bits=21`, log-scale `scale_shift=6`) are documented in ESPHome's
[`esphome/components/micro_wake_word/preprocessor_settings.h`](https://github.com/esphome/esphome/blob/dev/esphome/components/micro_wake_word/preprocessor_settings.h)
and match our own `hey_teya.json` training metadata; they're hardcoded into
`MicroFrontendWrapper.cpp`'s `FrontendConfig` setup (same values HA's wrapper uses), not
re-derived here.

## Pinned commits

```
tflite-micro:  2747abd5c82a95fb1624106a946fc671c31f16e8  (tensorflow/tflite-micro, main, 2026-01-22)
kissfft:       7bce4153c6bc8aba2db0e889e576f9d00505cbe1  (mborgerding/kissfft, tag 131.2.0)
```

Both pins **exactly match** the commits HA's own `microfrontend/src/main/cpp/CMakeLists.txt` pins
(`GIT_TAG 2747abd5c82a95fb1624106a946fc671c31f16e8 # January 22nd 2026` for tflite-micro,
`GIT_TAG 131.2.0` for kissfft) — both were live/fetchable at vendoring time
(2026-07-12), so there was no need to deviate to a newer/different commit.

Resolved and fetched via the GitHub REST contents API:
```
gh api "repos/tensorflow/tflite-micro/contents/<path>?ref=2747abd5c82a95fb1624106a946fc671c31f16e8" -q '.content' | base64 -d
gh api "repos/mborgerding/kissfft/contents/<path>?ref=7bce4153c6bc8aba2db0e889e576f9d00505cbe1" -q '.content' | base64 -d
```

This is a **one-time, manual vendoring**, matching the AEC3 precedent — no build-time fetch, no
auto-update mechanism. Re-vendoring against a newer commit is a deliberate, manual act: re-run the
same fetch process against a new commit hash and update this file.

## What was pulled

### `tflite-micro/` — mirrors `tensorflow/lite/experimental/microfrontend/lib/` at the pinned commit

All 16 implementation files (`.c`/`.cc`) HA's `CMakeLists.txt` compiles into its
`tflite_micro_frontend` static library target, plus every header those files (transitively)
include — confirmed by `grep`-ing all vendored files' own `#include` lines and checking each one
resolves either to another file vendored here or to a plain libc header (`<stdint.h>`,
`<string.h>`, `<math.h>`, `<limits.h>`, `<stdio.h>`, `<stdlib.h>`, `<assert.h>`, `<cstdint>`,
`<sys/types.h>`) — no other tflite-micro/TensorFlow-core dependency exists for this library:

| File | Role |
|---|---|
| `fft.cc`, `fft.h` | FFT entry point (`FftCompute`/`FftInit`/`FftReset`), delegates to kissfft |
| `fft_util.cc`, `fft_util.h` | FFT state population/free |
| `kiss_fft_int16.cc`, `kiss_fft_int16.h` | Wraps kissfft in a `kissfft_fixed16` namespace with `FIXED_POINT=16` (see below) |
| `kiss_fft_common.h` | Shared libc includes, hoisted outside the `kissfft_fixed16` namespace to dodge redefinition |
| `bits.h` | `CountLeadingZeros32/64`/`MostSignificantBit32/64` bit-twiddling helpers |
| `filterbank.c`, `filterbank.h` | Mel filterbank application |
| `filterbank_util.c`, `filterbank_util.h` | Filterbank state population (channel weights, freq bins) |
| `frontend.c`, `frontend.h` | Top-level `FrontendProcessSamples`/`FrontendReset` — what `MicroFrontendWrapper` calls |
| `frontend_util.c`, `frontend_util.h` | `FrontendFillConfigWithDefaults`/`FrontendPopulateState`/`FrontendFreeStateContents` — what `MicroFrontendWrapper`'s constructor/destructor call |
| `log_lut.c`, `log_lut.h` | Log lookup table for `log_scale.c` |
| `log_scale.c`, `log_scale.h` | Log-scale output stage |
| `log_scale_util.c`, `log_scale_util.h` | Log-scale state population |
| `noise_reduction.c`, `noise_reduction.h` | Spectral noise reduction |
| `noise_reduction_util.c`, `noise_reduction_util.h` | Noise-reduction state population |
| `pcan_gain_control.c`, `pcan_gain_control.h` | PCAN (per-channel amplitude normalization) gain stage |
| `pcan_gain_control_util.c`, `pcan_gain_control_util.h` | PCAN state population (gain LUT) |
| `window.c`, `window.h` | Windowing stage (applies the analysis window before FFT) |
| `window_util.c`, `window_util.h` | Window state population |

Also vendored: `LICENSE` (top-level `tflite-micro` repo license, Apache-2.0) at
`tflite-micro/LICENSE`.

**Deliberately not vendored** (present upstream in the same directory, not needed for an on-device
build): `*_io.c`/`*_io.h` files (host-tool serialization helpers for the offline
`frontend_main`/memmap-generator tools, not linked into the on-device library — confirmed absent
from HA's `CMakeLists.txt` source list too), `*_test.cc` files (GoogleTest unit tests),
`frontend_main.c`, `frontend_memmap_generator.c`, `frontend_memmap_main.c` (host-side CLI tools),
and `BUILD` (Bazel build file, not used by our CMake-based Android build).

### `kissfft/` — mirrors `mborgerding/kissfft`'s repo root at the pinned tag

`kiss_fft_int16.cc` (above) compiles kissfft's actual FFT implementation into itself directly, via
source-level `#include`, inside a `kissfft_fixed16` namespace with `#define FIXED_POINT 16`:
```cpp
#define FIXED_POINT 16
namespace kissfft_fixed16 {
#include "kiss_fft.c"
#include "tools/kiss_fftr.c"
}  // namespace kissfft_fixed16
#undef FIXED_POINT
```
So kissfft is a real second upstream dependency, not inlined/adapted — it needed its own pin and
its own license check.

| File | Role |
|---|---|
| `kiss_fft.h`, `kiss_fft.c` | Core (complex) FFT |
| `kiss_fftr.h`, `kiss_fftr.c` | Real-input FFT wrapper around `kiss_fft.c` (what the microfrontend actually calls, via `kissfft_fixed16::kiss_fftr`) |
| `_kiss_fft_guts.h` | Private internals shared by `kiss_fft.c`/`kiss_fftr.c` |
| `kiss_fft_log.h` | `KISS_FFT_ERROR`/`_WARNING`/`_INFO`/`_DEBUG` logging macros used by `_kiss_fft_guts.h` |
| `COPYING`, `LICENSES/BSD-3-Clause` | License (see below) |

**Build-time path note for whoever writes the CMakeLists.txt** (see "Notes for the follow-up
build-plumbing step" below): `tensorflow/.../kiss_fft_int16.h` and `.cc` `#include`
`"tools/kiss_fftr.h"` / `"tools/kiss_fftr.c"` (a `tools/` subpath), but upstream kissfft ships
`kiss_fftr.{c,h}` at its repo **root** — this vendored tree mirrors kissfft's actual upstream
layout (root-level, not duplicated into a `tools/` subdirectory) rather than pre-baking in a
path workaround. HA's own `CMakeLists.txt` resolves this same mismatch at CMake-configure time with
a `file(COPY_FILE ... tools/kiss_fftr.c ...)` step; the follow-up CMakeLists.txt here will need the
equivalent (a `file(COPY_FILE)`/`configure_file`/extra include-path trick), since these vendored
files are static source, not a `FetchContent` target CMake can post-process automatically.

**Deliberately not vendored** (present upstream, not on the include path any vendored file reaches
per the `grep` check above): `kfc.c`/`kfc.h` (a higher-level "KFC" convenience API, unused),
`kiss_fftnd.*`/`kiss_fftndr.*` (N-dimensional FFT, unused — the microfrontend only needs 1-D real
FFT), `kissfft.hh`/`kissfft_i32.hh` (C++ template header-only API, unused — the microfrontend uses
the plain C API), `CMakeLists.txt`/`cmake/`/`kissfft-config.cmake.in`/`kissfft.pc.in` (kissfft's own
build/packaging files, not used — we do not build kissfft as its own CMake subproject), `test/`
(kissfft's own test suite), `README*`, `CHANGELOG`, `TIPS`, `.travis.yml`, `.gitignore`.

## Licensing summary

| Component | License | Commercial use |
|---|---|---|
| `tflite-micro/` (TensorFlow Lite Micro microfrontend) | **Apache-2.0** — confirmed against `tflite-micro/LICENSE` (the actual top-level `tensorflow/tflite-micro` repo LICENSE file at the pinned commit, fetched and checked, not assumed) | ✅ Yes |
| `kissfft/` (`mborgerding/kissfft`) | **BSD-3-Clause** — confirmed against `kissfft/COPYING` (`SPDX-License-Identifier: BSD-3-Clause`) and the full license text in `kissfft/LICENSES/BSD-3-Clause`, both fetched from the pinned tag, not assumed | ✅ Yes |
| `MicroFrontendWrapper.cpp`/`.h`, `MicroFrontend_jni.cpp` (this repo's JNI glue) | Apache-2.0 (same as the library they wrap; adapted from HA's Apache-2.0-licensed equivalents, `// SPDX-License-Identifier: Apache-2.0` header on HA's originals) | ✅ Yes |

Both licenses are permissive with no non-commercial or share-alike restriction — unlike the
CC-BY-NC-SA `hey_jarvis_v0.1.tflite` classifier this module exists to replace (see
`THIRD_PARTY_MODELS.md`'s "Commercial-use blocker" section).

## Not yet done (explicitly out of scope for this vendoring step)

- **No CMakeLists.txt** in this directory yet — a follow-up step owns writing the CMake target(s)
  that compile `tflite-micro/tensorflow/.../lib/*.{c,cc}` + `kissfft/kiss_fft.c` +
  `kissfft/kiss_fftr.c` (via `kiss_fft_int16.cc`'s source-inclusion trick above) into a static lib,
  and `MicroFrontend_jni.cpp` + `MicroFrontendWrapper.cpp` into the JNI `.so`.
- **No `app/build.gradle.kts` NDK/`externalNativeBuild` plumbing** yet — this app currently has zero
  native build config; that's new plumbing for a later step, not an extension of anything existing.
- **No Kotlin wrapper** (`MicroFrontend.kt`) yet — the JNI method names below are chosen to match
  where that class is expected to land (`com.teya.agent.voice.MicroFrontend`, alongside
  `WakeWordEngine.kt`), but the class itself is a separate step.

## Notes for the follow-up build-plumbing step

- **`FIXED_POINT=16` is load-bearing**, not a stylistic choice — it must be defined when compiling
  `tflite-micro/tensorflow/.../lib/kiss_fft_int16.cc` (HA does this via
  `target_compile_definitions(tflite_micro_frontend PUBLIC FIXED_POINT=16)` on the whole static-lib
  target, simplest to just mirror that) to match the fixed-point arithmetic the `hey_teya` model was
  trained/calibrated against. Getting this wrong won't fail to compile — it'll silently produce
  wrong features.
- **Include paths needed**: the repo root of `tflite-micro/` (so
  `#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"`-style absolute-from-repo-root
  includes resolve) and the repo root of `kissfft/` (so `#include "kiss_fft.h"` and
  `#include "tools/kiss_fftr.h"` resolve — the latter needs the `tools/` path workaround noted
  above).
- **The `tools/kiss_fftr.{c,h}` path mismatch** (kissfft ships them at root; tflite-micro's
  `kiss_fft_int16.h`/`.cc` expect `tools/kiss_fftr.{h,c}`) needs handling in the CMakeLists.txt —
  either a build-time file copy (HA's approach) or an extra `tools`-suffixed include directory
  pointing back at the kissfft root plus a symlink/generated header. Not resolved by this vendoring
  step on purpose (no CMake in scope here) — flagged so it isn't rediscovered the hard way.
- **JNI method names** in `MicroFrontend_jni.cpp` target
  `Java_com_teya_agent_voice_MicroFrontend_{nativeCreate,nativeDestroy,nativeProcessSamples,nativeReset}`
  — the Kotlin wrapper's `external fun` declarations must match this exactly (package
  `com.teya.agent.voice`, class `MicroFrontend`) or `System.loadLibrary`/JNI method resolution will
  fail at runtime (not compile time).
- **Suppress third-party warnings** the same way HA does when compiling the vendored static lib
  (`-Wno-unused-parameter -Wno-sign-compare`, plus `-ffast-math` which HA notes is safe here only
  because `FIXED_POINT=16` means this code path doesn't actually do float FFT math) — this is
  vendored, unmodified upstream C; fixing its warnings is not this project's job.
