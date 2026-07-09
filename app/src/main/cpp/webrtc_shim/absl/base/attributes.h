// Teya build shim — NOT vendored Abseil source. Only ABSL_MUST_USE_RESULT is
// referenced by the vendored tree (rtc_base/swap_queue.h). Defined as the
// real GCC/Clang attribute it expands to upstream (not a no-op) — the
// warning it enables is genuinely useful (catches ignoring a queue
// insert/remove failure).
#ifndef TEYA_WEBRTC_SHIM_ABSL_BASE_ATTRIBUTES_H_
#define TEYA_WEBRTC_SHIM_ABSL_BASE_ATTRIBUTES_H_

#if defined(__GNUC__) || defined(__clang__)
#define ABSL_MUST_USE_RESULT __attribute__((warn_unused_result))
#else
#define ABSL_MUST_USE_RESULT
#endif

#endif  // TEYA_WEBRTC_SHIM_ABSL_BASE_ATTRIBUTES_H_
