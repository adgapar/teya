// Teya build shim — NOT vendored Abseil source. See string_view.h for the
// general rationale (avoid pulling in real Abseil's transitive header graph
// for a couple of leaf utility functions).
//
// api/field_trials_view.h calls absl::StartsWith. EndsWith/StrContains are
// added alongside for completeness in case a later vendoring pass needs
// them, but aren't currently exercised. Real, correct implementations on
// std::string_view — not stand-ins.
#ifndef TEYA_WEBRTC_SHIM_ABSL_STRINGS_MATCH_H_
#define TEYA_WEBRTC_SHIM_ABSL_STRINGS_MATCH_H_

#include "absl/strings/string_view.h"

namespace absl {

inline bool StartsWith(string_view text, string_view prefix) {
  return text.size() >= prefix.size() &&
         text.compare(0, prefix.size(), prefix) == 0;
}

inline bool EndsWith(string_view text, string_view suffix) {
  return text.size() >= suffix.size() &&
         text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

inline bool StrContains(string_view haystack, string_view needle) {
  return haystack.find(needle) != string_view::npos;
}

}  // namespace absl

#endif  // TEYA_WEBRTC_SHIM_ABSL_STRINGS_MATCH_H_
