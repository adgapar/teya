// Real JNI glue for Teya's vendored WebRTC AEC3 (Phase 3b of thoughts/shared/plans/
// 2026-07-09-webrtc-aec3-native-port.md). Replaces the Phase 2 jni_ping.cpp placeholder now that
// Phase 3a has proven teya_aec3_core compiles and links (see aec3_core_smoke_check.cpp for the
// construction pattern this file reuses: Environment/NoOpFieldTrials, sample rate, channel
// counts, neural_residual_echo_estimator = nullptr).
//
// Owns one EchoCanceller3 instance plus two reusable AudioBuffers (render, capture) per handle —
// single full-band 10ms frames, no band-splitting (num_bands() == 1, matching the webrtc_shim
// AudioBuffer's documented assumption). Kotlin side (NativeAec3.kt) is responsible for the
// single-writer locking discipline; this file assumes serialized access per handle and does no
// locking of its own (mirrors AEC3's own documented threading contract: AnalyzeRender may be
// called concurrently with the other methods, but capture-side calls must be serialized among
// themselves — Kotlin's single lock is a coarser, safer superset of that contract).

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "absl/strings/string_view.h"
#include "api/audio/echo_canceller3_config.h"
#include "api/environment/environment.h"
#include "api/field_trials_view.h"
#include "modules/audio_processing/aec3/echo_canceller3.h"

namespace {

// Same minimal FieldTrialsView as the Phase 3a smoke check: no field trials configured, matching
// AEC3's own documented default behavior when nothing overrides it.
class NoOpFieldTrials : public webrtc::FieldTrialsView {
 public:
  std::string Lookup(absl::string_view /*key*/) const override { return ""; }
};

// Everything one nativeCreate() handle owns. field_trials_/env_ must outlive echo_canceller_
// (Environment stores a raw pointer to the FieldTrialsView, per the webrtc_shim), so they're
// declared first and destroyed last.
struct AecHandle {
  NoOpFieldTrials field_trials;
  webrtc::Environment env;
  webrtc::EchoCanceller3 echo_canceller;
  webrtc::AudioBuffer render_buffer;
  webrtc::AudioBuffer capture_buffer;

  AecHandle(int sample_rate_hz, size_t frame_size)
      : env(&field_trials),
        echo_canceller(env,
                        webrtc::EchoCanceller3Config(),
                        /*multichannel_config=*/std::nullopt,
                        /*neural_residual_echo_estimator=*/nullptr,
                        sample_rate_hz,
                        /*num_render_channels=*/1,
                        /*num_capture_channels=*/1),
        render_buffer(/*num_channels=*/1, /*num_bands=*/1, frame_size),
        capture_buffer(/*num_channels=*/1, /*num_bands=*/1, frame_size) {}
};

// PCM16 -> float, into an AudioBuffer's single (num_bands()==1) channel.
void FillFromPcm16(JNIEnv* jni_env, jshortArray frame, webrtc::AudioBuffer* buffer) {
  jshort* elems = jni_env->GetShortArrayElements(frame, nullptr);
  float* channel = buffer->channels()[0];
  const size_t n = buffer->num_frames();
  for (size_t i = 0; i < n; ++i) {
    channel[i] = static_cast<float>(elems[i]);
  }
  jni_env->ReleaseShortArrayElements(frame, elems, JNI_ABORT);
}

// float -> PCM16 (clamped to int16 range), returned as a freshly-allocated jshortArray.
jshortArray ToPcm16(JNIEnv* jni_env, const webrtc::AudioBuffer& buffer) {
  const size_t n = buffer.num_frames();
  const float* channel = buffer.channels_const()[0];
  jshortArray out = jni_env->NewShortArray(static_cast<jsize>(n));
  std::vector<jshort> converted(n);
  for (size_t i = 0; i < n; ++i) {
    float clamped = std::clamp(channel[i], -32768.f, 32767.f);
    converted[i] = static_cast<jshort>(clamped);
  }
  jni_env->SetShortArrayRegion(out, 0, static_cast<jsize>(n), converted.data());
  return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jint sample_rate_hz) {
  // 10ms full-band frame size at the given sample rate — this build has no band-splitting
  // support (see webrtc_shim's AudioBuffer note), so this is only valid at 16kHz in practice;
  // the Kotlin wrapper's FRAME_SIZE=160 requirement enforces that at the call sites.
  const size_t frame_size = static_cast<size_t>(sample_rate_hz) / 100;
  auto* handle = new AecHandle(sample_rate_hz, frame_size);
  return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_nativeAnalyzeRender(JNIEnv* env, jobject /*thiz*/,
                                                              jlong handle, jshortArray frame) {
  auto* h = reinterpret_cast<AecHandle*>(handle);
  FillFromPcm16(env, frame, &h->render_buffer);
  h->echo_canceller.AnalyzeRender(&h->render_buffer);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_nativeProcessCapture(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle, jshortArray frame) {
  auto* h = reinterpret_cast<AecHandle*>(handle);
  FillFromPcm16(env, frame, &h->capture_buffer);
  h->echo_canceller.AnalyzeCapture(&h->capture_buffer);
  h->echo_canceller.ProcessCapture(&h->capture_buffer, /*level_change=*/false);
  return ToPcm16(env, h->capture_buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                        jlong handle) {
  delete reinterpret_cast<AecHandle*>(handle);
}

// Diagnostic only, no behavior change: [echo_return_loss, echo_return_loss_enhancement, delay_ms]
// straight from EchoCanceller3::GetMetrics(), to see what AEC3 itself believes about the
// render/capture delay it's tracking, before deciding whether SetAudioBufferDelay is worth adding.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_teya_agent_voice_aec_NativeAec3_nativeGetMetrics(JNIEnv* env, jobject /*thiz*/,
                                                           jlong handle) {
  auto* h = reinterpret_cast<AecHandle*>(handle);
  webrtc::EchoControl::Metrics m = h->echo_canceller.GetMetrics();
  jdoubleArray out = env->NewDoubleArray(3);
  jdouble values[3] = {m.echo_return_loss, m.echo_return_loss_enhancement,
                       static_cast<jdouble>(m.delay_ms)};
  env->SetDoubleArrayRegion(out, 0, 3, values);
  return out;
}
