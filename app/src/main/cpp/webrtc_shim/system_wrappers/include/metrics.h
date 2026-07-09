// Teya build shim — NOT the vendored upstream system_wrappers/include/metrics.h.
//
// RTC_HISTOGRAM_* macros feed Chrome's UMA telemetry pipeline. Irrelevant to
// a standalone Android app with no UMA backend — no-ops, matching how
// Chromium itself treats metrics when the UMA backend is absent. Arguments
// are still referenced (cast to void) so -Wunused-* doesn't fire on call
// sites, but no code is generated and no state is recorded.
//
// This is purely diagnostic/telemetry plumbing (see the 5 aec3/*.cc files
// that use these macros — all in *_metrics.cc/multi_channel_content_detector.cc)
// and does not affect AEC3's echo-cancellation behavior.
#ifndef TEYA_WEBRTC_SHIM_SYSTEM_WRAPPERS_INCLUDE_METRICS_H_
#define TEYA_WEBRTC_SHIM_SYSTEM_WRAPPERS_INCLUDE_METRICS_H_

#define RTC_HISTOGRAM_BOOLEAN(name, sample) \
  do {                                      \
    (void)(name);                           \
    (void)(sample);                         \
  } while (0)

#define RTC_HISTOGRAM_COUNTS_LINEAR(name, sample, min, max, bucket_count) \
  do {                                                                    \
    (void)(name);                                                        \
    (void)(sample);                                                      \
    (void)(min);                                                         \
    (void)(max);                                                         \
    (void)(bucket_count);                                                \
  } while (0)

#define RTC_HISTOGRAM_COUNTS_SPARSE(name, sample) \
  do {                                             \
    (void)(name);                                  \
    (void)(sample);                                \
  } while (0)

#define RTC_HISTOGRAM_ENUMERATION(name, sample, boundary) \
  do {                                                     \
    (void)(name);                                          \
    (void)(sample);                                         \
    (void)(boundary);                                        \
  } while (0)

#define RTC_HISTOGRAM_PERCENTAGE(name, sample) \
  do {                                          \
    (void)(name);                               \
    (void)(sample);                             \
  } while (0)

#endif  // TEYA_WEBRTC_SHIM_SYSTEM_WRAPPERS_INCLUDE_METRICS_H_
