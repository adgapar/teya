// Phase 3a smoke consumer for teya_aec3_core (see thoughts/shared/plans/
// 2026-07-09-webrtc-aec3-native-port.md). Not JNI, not shipped in the real
// pipeline — its only job is to exercise a handful of real AEC3 symbols so
// the linker actually pulls in teya_aec3_core instead of silently dropping
// an unused static library. Real JNI glue + Kotlin wrapper is Phase 3b.

#include <optional>
#include <string>

#include "absl/strings/string_view.h"
#include "api/audio/echo_canceller3_config.h"
#include "api/environment/environment.h"
#include "api/field_trials_view.h"
#include "modules/audio_processing/aec3/echo_canceller3.h"

namespace {

// Minimal FieldTrialsView: no field trials configured, matching AEC3's own
// documented default behavior when nothing overrides it.
class NoOpFieldTrials : public webrtc::FieldTrialsView {
 public:
  std::string Lookup(absl::string_view /*key*/) const override { return ""; }
};

}  // namespace

extern "C" int teya_aec3_core_smoke_check() {
  // Real AEC3 symbol #1: construct a default EchoCanceller3Config.
  webrtc::EchoCanceller3Config config;

  // Real AEC3 symbol #2: construct a real EchoCanceller3 (16kHz mono, one
  // render channel, one capture channel — matches this device's single-mic,
  // single-speaker setup and this plan's "no multi-band splitting" scope).
  NoOpFieldTrials field_trials;
  webrtc::Environment env(&field_trials);
  webrtc::EchoCanceller3 echo_canceller(
      env, config, /*multichannel_config=*/std::nullopt,
      /*neural_residual_echo_estimator=*/nullptr,
      /*sample_rate_hz=*/16000,
      /*num_render_channels=*/1,
      /*num_capture_channels=*/1);

  // Default delay.default_delay is 5 per echo_canceller3_config.h; returning
  // it (rather than a hardcoded constant) proves the real config defaults
  // were actually read, not just default-initialized to zero by accident.
  return static_cast<int>(config.delay.default_delay);
}
