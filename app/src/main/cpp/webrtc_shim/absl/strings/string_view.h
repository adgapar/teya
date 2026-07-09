// Teya build shim — NOT vendored Abseil source.
//
// AEC3's vendored WebRTC sources use `absl::string_view` in a handful of
// signatures (see ../../../third_party/webrtc/VENDORING.md's "Abseil
// surface" section for the exact file list, and the Phase 3a implementation
// note for why this shim exists instead of vendoring upstream Abseil).
// Vendoring real Abseil for this would mean pulling in absl/base/config.h,
// absl/base/policy_checks.h, absl/base/internal/*, absl/numeric/int128.h,
// absl/strings/internal/*, etc. — a much larger transitive subtree than
// what this narrow, leaf-utility usage actually needs, and one that would
// need its own offline-build story.
//
// This header just aliases absl::string_view to std::string_view — which is
// literally what upstream Abseil itself resolves to on toolchains where
// ABSL_USES_STD_STRING_VIEW is set (any C++17 build, which this project
// already requires). Not a behavioral simplification.
#ifndef TEYA_WEBRTC_SHIM_ABSL_STRINGS_STRING_VIEW_H_
#define TEYA_WEBRTC_SHIM_ABSL_STRINGS_STRING_VIEW_H_

#include <string_view>

namespace absl {
using string_view = std::string_view;
}  // namespace absl

#endif  // TEYA_WEBRTC_SHIM_ABSL_STRINGS_STRING_VIEW_H_
