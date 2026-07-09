# Teya native module — AEC3 (Acoustic Echo Canceller v3), vendored from WebRTC.
#
# Phase 2 of thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md stood up a trivial
# passthrough skeleton proving NDK -> CMake -> .so -> JNI -> Kotlin works end-to-end (teya_aec3,
# then just jni_ping.cpp — no AEC3 code).
#
# Phase 3a added teya_aec3_core: a STATIC library built from the vendored AEC3 sources +
# dependency slice (third_party/webrtc/, see VENDORING.md) plus a small set of Teya-authored build
# shims (webrtc_shim/, see the README there) that replace a handful of WebRTC-internal files whose
# real implementations assume the full gn/Chromium build (RTC_LOG sink routing, UMA metrics, the
# full Audio Processing Module's AudioBuffer/Environment). No JNI glue there on purpose — see the
# plan's Phase 3a for why that was split out as its own checkpoint.
#
# Phase 3b replaces jni_ping.cpp's trivial passthrough with jni_aec3.cpp — real JNI glue around a
# real EchoCanceller3 instance, linked against teya_aec3_core below (defined first so teya_aec3
# can link against it).

cmake_minimum_required(VERSION 3.22.1)

project(teya_aec3)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# teya_aec3_core — AEC3 + dependencies, static lib, no JNI symbols.
# ---------------------------------------------------------------------------

set(AEC3_DIR third_party/webrtc/modules/audio_processing/aec3)

add_library(teya_aec3_core STATIC
    # --- AEC3 core sources (Phase 1 vendored tree, minus unit tests/AVX2/mocks) ---
    ${AEC3_DIR}/adaptive_fir_filter_erl.cc
    ${AEC3_DIR}/adaptive_fir_filter.cc
    ${AEC3_DIR}/aec_state.cc
    ${AEC3_DIR}/aec3_common.cc
    ${AEC3_DIR}/aec3_fft.cc
    ${AEC3_DIR}/alignment_mixer.cc
    ${AEC3_DIR}/api_call_jitter_metrics.cc
    ${AEC3_DIR}/block_buffer.cc
    ${AEC3_DIR}/block_delay_buffer.cc
    ${AEC3_DIR}/block_framer.cc
    ${AEC3_DIR}/block_processor_metrics.cc
    ${AEC3_DIR}/block_processor.cc
    ${AEC3_DIR}/clockdrift_detector.cc
    ${AEC3_DIR}/coarse_filter_update_gain.cc
    ${AEC3_DIR}/comfort_noise_generator.cc
    ${AEC3_DIR}/config_selector.cc
    ${AEC3_DIR}/decimator.cc
    ${AEC3_DIR}/dominant_nearend_detector.cc
    ${AEC3_DIR}/downsampled_render_buffer.cc
    ${AEC3_DIR}/echo_audibility.cc
    ${AEC3_DIR}/echo_canceller3.cc
    ${AEC3_DIR}/echo_path_delay_estimator.cc
    ${AEC3_DIR}/echo_path_variability.cc
    ${AEC3_DIR}/echo_remover_metrics.cc
    ${AEC3_DIR}/echo_remover.cc
    ${AEC3_DIR}/erl_estimator.cc
    ${AEC3_DIR}/erle_estimator.cc
    ${AEC3_DIR}/fft_buffer.cc
    ${AEC3_DIR}/filter_analyzer.cc
    ${AEC3_DIR}/frame_blocker.cc
    ${AEC3_DIR}/fullband_erle_estimator.cc
    ${AEC3_DIR}/matched_filter_lag_aggregator.cc
    ${AEC3_DIR}/matched_filter.cc
    ${AEC3_DIR}/moving_average_spectrum.cc
    ${AEC3_DIR}/multi_channel_content_detector.cc
    ${AEC3_DIR}/refined_filter_update_gain.cc
    ${AEC3_DIR}/render_buffer.cc
    ${AEC3_DIR}/render_delay_buffer.cc
    ${AEC3_DIR}/render_delay_controller_metrics.cc
    ${AEC3_DIR}/render_delay_controller.cc
    ${AEC3_DIR}/render_signal_analyzer.cc
    ${AEC3_DIR}/residual_echo_estimator.cc
    ${AEC3_DIR}/reverb_decay_estimator.cc
    ${AEC3_DIR}/reverb_frequency_response.cc
    ${AEC3_DIR}/reverb_model_estimator.cc
    ${AEC3_DIR}/reverb_model.cc
    ${AEC3_DIR}/signal_dependent_erle_estimator.cc
    ${AEC3_DIR}/spectrum_buffer.cc
    ${AEC3_DIR}/stationarity_estimator.cc
    ${AEC3_DIR}/subband_erle_estimator.cc
    ${AEC3_DIR}/subband_nearend_detector.cc
    ${AEC3_DIR}/subtractor_output_analyzer.cc
    ${AEC3_DIR}/subtractor_output.cc
    ${AEC3_DIR}/subtractor.cc
    ${AEC3_DIR}/suppression_filter.cc
    ${AEC3_DIR}/suppression_gain.cc
    ${AEC3_DIR}/transparent_mode.cc

    # --- Dependency slice actually needed to link the above (see VENDORING.md) ---
    third_party/webrtc/rtc_base/cpu_info.cc
    third_party/webrtc/rtc_base/race_checker.cc
    third_party/webrtc/rtc_base/platform_thread_types.cc
    third_party/webrtc/rtc_base/experiments/field_trial_parser.cc
    third_party/webrtc/api/audio/echo_canceller3_config.cc
    third_party/webrtc/modules/audio_processing/logging/apm_data_dumper.cc
    third_party/webrtc/modules/audio_processing/high_pass_filter.cc
    third_party/webrtc/modules/audio_processing/utility/cascaded_biquad_filter.cc
    third_party/webrtc/common_audio/third_party/ooura/fft_size_128/ooura_fft.cc
    third_party/webrtc/common_audio/third_party/ooura/fft_size_128/ooura_fft_neon.cc

    # --- Teya build shims (webrtc_shim/, see README there) ---
    # audio_buffer.h, checks.h, logging.h, metrics.h and environment.h are header-only
    # replacements picked up via include-path precedence below; they add no extra .cc files.
)

target_include_directories(teya_aec3_core PRIVATE
    # webrtc_shim MUST come before third_party/webrtc so `#include "rtc_base/checks.h"` etc.
    # resolve to the Teya-authored shims, not the real vendored files of the same name.
    webrtc_shim
    third_party/webrtc
)


# The pinned WebRTC commit (99de2d09036e61b72e4e6bba3ab09fedc40d18fe) uses std::span
# (modules/audio_processing/aec3/block.h, fft_data.h, render_buffer.h, apm_data_dumper.h, ...)
# pervasively, which is a C++20 library feature — NOT available under C++17 despite the plan's
# original "-std=c++17" assumption. NDK r27's clang (Chromium's own C++ standard as of this
# WebRTC revision) supports C++20 fully, so this is a corrected build setting, not a workaround.
target_compile_features(teya_aec3_core PUBLIC cxx_std_20)

# PUBLIC (not PRIVATE): teya_aec3_core_smoke_check's own .cpp also transitively includes
# apm_data_dumper.h / race_checker.h (via echo_canceller3.h) and needs these same macros defined
# when *it* compiles, not just when teya_aec3_core's own sources compile. PUBLIC propagates them
# as a usage requirement to anything that links against this target.
target_compile_definitions(teya_aec3_core PUBLIC
    # NEON is mandatory on AArch64 (our only ABI), so the Ooura FFT's NEON kernel is what an
    # arm64-v8a build actually wants — see VENDORING.md's Ooura FFT note.
    WEBRTC_HAS_NEON
    # Disables ApmDataDumper's WAV-file debug-dump machinery (and its common_audio/wav_file.h,
    # rtc_base/string_utils.h, rtc_base/strings/string_builder.h dependencies) at compile time —
    # a real upstream build-time switch, not a Teya-invented one.
    WEBRTC_APM_DEBUG_DUMP=0
    # Real upstream build-config macro (Android is POSIX) — unlocks rtc_base/platform_thread_types.h's
    # PlatformThreadId/PlatformThreadRef typedefs and the pthread_self()-based
    # CurrentThreadRef()/IsThreadRefEqual() that rtc_base/race_checker.cc calls. Deliberately NOT
    # defining WEBRTC_ANDROID/WEBRTC_LINUX target-wide: those would route cpu_info.cc into
    # #include <cpu-features.h>, which isn't on the NDK's default include path (it lives under
    # sources/android/cpufeatures/, the legacy standalone cpufeatures library) — and nothing this
    # target compiles actually needs that Android-specific branch (cpu_info::Supports(kNeon) /
    # DetectNumberOfCores() are never called from the vendored aec3 sources; confirmed by grep).
    # platform_thread_types.cc gets WEBRTC_ANDROID/WEBRTC_LINUX individually below instead, since
    # its generic POSIX fallback (reinterpret_cast<pid_t> of a pthread_t) doesn't compile on a
    # target where pthread_t (64-bit) and pid_t (32-bit) differ in size.
    WEBRTC_POSIX
    # rtc_base/system/arch.h auto-detects WEBRTC_ARCH_ARM_FAMILY/WEBRTC_ARCH_64_BITS from compiler
    # predefines, but WEBRTC_ARCH_ARM64 specifically is a real upstream macro that gn's build passes
    # as an explicit `-D` based on target_cpu (it's not derived by arch.h itself). Setting it
    # ourselves — accurately, since this whole module is arm64-v8a-only — selects the real hardware
    # NEON sqrt path in vector_math.h (vsqrtq_f32) instead of the 32-bit-ARM software
    # Newton-Raphson fallback, and the gettid()-based path (vs. a since-removed 32-bit ARM syscall
    # table) in platform_thread_types.cc.
    WEBRTC_ARCH_ARM64
)

# platform_thread_types.cc's CurrentThreadId() has three code paths under WEBRTC_POSIX: a MAC path,
# an ANDROID path (gettid()), and a generic fallback (reinterpret_cast<PlatformThreadId>(pthread_self())).
# The generic fallback doesn't compile here: pthread_t is 64-bit on arm64 but PlatformThreadId
# (pid_t) is 32-bit, and reinterpret_cast between differently-sized non-pointer types is ill-formed.
# WEBRTC_ANDROID selects the real, correct gettid()-based path instead. WEBRTC_LINUX is needed
# alongside it so the same file's SetCurrentThreadName() pulls in <sys/prctl.h> for PR_SET_NAME
# (upstream gates that #include on WEBRTC_LINUX specifically, even though the call site itself
# checks `WEBRTC_LINUX || WEBRTC_ANDROID`) — real upstream Android builds define both together.
# Scoped to this one file (not the whole target) to avoid cpu_info.cc's cpu-features.h problem.
set_source_files_properties(
    third_party/webrtc/rtc_base/platform_thread_types.cc
    PROPERTIES COMPILE_DEFINITIONS "WEBRTC_ANDROID;WEBRTC_LINUX"
)

find_library(log-lib-core log)

target_link_libraries(teya_aec3_core PUBLIC
    ${log-lib-core}
)

# ---------------------------------------------------------------------------
# teya_aec3 — real JNI glue (Phase 3b), loaded by Kotlin via
# System.loadLibrary("teya_aec3"). Links against teya_aec3_core rather than adding yet another
# separate .so; teya_aec3_core's PUBLIC target_compile_definitions (WEBRTC_HAS_NEON,
# WEBRTC_APM_DEBUG_DUMP, WEBRTC_POSIX, WEBRTC_ARCH_ARM64) propagate automatically via that link,
# same as teya_aec3_core_smoke_check below relies on.
# ---------------------------------------------------------------------------

add_library(teya_aec3 SHARED
    jni_aec3.cpp
)

target_include_directories(teya_aec3 PRIVATE
    webrtc_shim
    third_party/webrtc
)

target_compile_features(teya_aec3 PUBLIC cxx_std_20)

find_library(log-lib log)

target_link_libraries(teya_aec3
    teya_aec3_core
    ${log-lib}
)

# ---------------------------------------------------------------------------
# teya_aec3_core_smoke_check — trivial non-JNI consumer.
#
# Exists only so the linker actually exercises teya_aec3_core (constructs a real
# EchoCanceller3Config and a real EchoCanceller3 against a minimal FieldTrialsView) instead of the
# static lib silently going unused. No JNI symbols; not shipped/used by the app. Kept as a
# standing Phase 3a checkpoint even now that teya_aec3's real JNI glue (above) also links against
# teya_aec3_core.
# ---------------------------------------------------------------------------

add_library(teya_aec3_core_smoke_check SHARED
    aec3_core_smoke_check.cpp
)

target_include_directories(teya_aec3_core_smoke_check PRIVATE
    webrtc_shim
    third_party/webrtc
)

target_compile_features(teya_aec3_core_smoke_check PUBLIC cxx_std_20)

target_link_libraries(teya_aec3_core_smoke_check PRIVATE
    teya_aec3_core
    ${log-lib-core}
)
