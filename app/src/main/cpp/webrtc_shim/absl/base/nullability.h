// Teya build shim — NOT vendored Abseil source. absl_nullable / absl_nonnull
// / absl_nullability_unknown are pure static-analysis annotations upstream
// (they already expand to nothing on toolchains that lack the corresponding
// Clang attributes) — defining them empty here is faithful, not a
// simplification.
#ifndef TEYA_WEBRTC_SHIM_ABSL_BASE_NULLABILITY_H_
#define TEYA_WEBRTC_SHIM_ABSL_BASE_NULLABILITY_H_

#define absl_nullable
#define absl_nonnull
#define absl_nullability_unknown

#endif  // TEYA_WEBRTC_SHIM_ABSL_BASE_NULLABILITY_H_
