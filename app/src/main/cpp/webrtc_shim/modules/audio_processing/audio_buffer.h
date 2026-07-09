// Teya build shim — NOT the vendored upstream modules/audio_processing/audio_buffer.h.
//
// Upstream AudioBuffer is the full Audio Processing Module's buffer type:
// it resamples between arbitrary sample rates, deinterleaves from a
// StreamConfig, mixes multiple capture channels via CaptureMixer, and
// splits/merges QMF sub-bands via SplittingFilter/ThreeBandFilterBank for
// super-wideband (>16kHz) processing. Pulling in the real audio_buffer.h
// means pulling in api/audio/audio_processing.h (the full APM Config, out
// of scope per this plan's "What We're NOT Doing"), api/audio/audio_view.h,
// common_audio/channel_buffer.h, common_audio/include/audio_util.h and
// modules/audio_processing/capture_mixer/capture_mixer.h — none of which
// Teya's mono/16kHz-only, single-mic AEC3 build needs.
//
// Grepping the vendored aec3 tree + high_pass_filter.cc (the only two
// places AudioBuffer is actually used, see VENDORING.md) shows the real API
// surface needed is exactly: num_channels(), num_bands(),
// num_frames_per_band(), num_frames(), split_bands(channel),
// split_bands_const(channel), channels(), channels_const() and the static
// kSplitBandSize constant. This shim implements a real, correctly-behaving
// buffer for exactly that surface — not a stub that returns zeros — under
// the explicit assumption (true for Teya's 16kHz-mono use case per this
// plan's own research: "no multi-band splitting" below the 32kHz threshold)
// that num_bands() == 1, so split_bands(ch)/channels(ch) both address the
// same single band of samples. If a future phase needs true multi-band
// (>16kHz) operation, that's the signal to vendor the real audio_buffer.cc
// + its full dependency slice instead of extending this shim.
#ifndef TEYA_WEBRTC_SHIM_MODULES_AUDIO_PROCESSING_AUDIO_BUFFER_H_
#define TEYA_WEBRTC_SHIM_MODULES_AUDIO_PROCESSING_AUDIO_BUFFER_H_

#include <cstddef>
#include <vector>

namespace webrtc {

class AudioBuffer {
 public:
  // The fixed per-band frame size (10ms) used throughout WebRTC's
  // split-band representation, independent of the overall sample rate.
  static constexpr size_t kSplitBandSize = 160;

  AudioBuffer(size_t num_channels, size_t num_bands, size_t num_frames_per_band)
      : num_channels_(num_channels),
        num_bands_(num_bands),
        num_frames_per_band_(num_frames_per_band),
        data_(num_channels,
              std::vector<std::vector<float>>(
                  num_bands,
                  std::vector<float>(num_frames_per_band, 0.f))),
        band_ptrs_(num_channels, std::vector<float*>(num_bands, nullptr)),
        channel_ptrs_(num_channels, nullptr) {
    for (size_t ch = 0; ch < num_channels_; ++ch) {
      for (size_t band = 0; band < num_bands_; ++band) {
        band_ptrs_[ch][band] = data_[ch][band].data();
      }
      // channels()/channels_const() is only meaningful (matches real
      // upstream semantics) when num_bands() == 1 — see class comment.
      channel_ptrs_[ch] = data_[ch][0].data();
    }
  }

  size_t num_channels() const { return num_channels_; }
  size_t num_bands() const { return num_bands_; }
  size_t num_frames_per_band() const { return num_frames_per_band_; }
  size_t num_frames() const { return num_frames_per_band_; }

  float* const* split_bands(size_t channel) { return band_ptrs_[channel].data(); }
  const float* const* split_bands_const(size_t channel) const {
    return const_cast<std::vector<float*>&>(band_ptrs_[channel]).data();
  }

  float* const* channels() { return channel_ptrs_.data(); }
  const float* const* channels_const() const {
    return const_cast<std::vector<float*>&>(channel_ptrs_).data();
  }

 private:
  size_t num_channels_;
  size_t num_bands_;
  size_t num_frames_per_band_;
  std::vector<std::vector<std::vector<float>>> data_;  // [channel][band][sample]
  std::vector<std::vector<float*>> band_ptrs_;          // [channel][band] -> float*
  std::vector<float*> channel_ptrs_;                    // [channel] -> float* (band 0 alias)
};

}  // namespace webrtc

#endif  // TEYA_WEBRTC_SHIM_MODULES_AUDIO_PROCESSING_AUDIO_BUFFER_H_
