// Teya build shim — NOT the vendored upstream api/environment/environment.h.
//
// Upstream Environment is a dependency-injection container aggregating a
// Clock, TaskQueueFactory, RtcEventLog and FieldTrialsView (pulling in
// api/ref_counted_base.h, api/rtc_event_log/rtc_event_log.h,
// api/scoped_refptr.h, api/task_queue/task_queue_factory.h,
// system_wrappers/include/clock.h) — Chromium-wide plumbing, not AEC3 DSP.
//
// Grepping the entire vendored aec3 tree (see VENDORING.md) confirms AEC3
// core only ever calls env.field_trials() — never .clock()/
// .task_queue_factory()/.event_log(). This shim keeps only that one
// accessor. If a later phase (3b/4) adds code that calls one of the other
// accessors, that's the signal to give Environment a real implementation of
// that specific accessor — not to re-vendor the whole DI container.
#ifndef TEYA_WEBRTC_SHIM_API_ENVIRONMENT_ENVIRONMENT_H_
#define TEYA_WEBRTC_SHIM_API_ENVIRONMENT_ENVIRONMENT_H_

#include "api/field_trials_view.h"

namespace webrtc {

class Environment {
 public:
  explicit Environment(const FieldTrialsView* field_trials)
      : field_trials_(field_trials) {}

  // Environment is stored by value in EchoCanceller3 (`const Environment
  // env_;`), so it must stay cheaply copyable.
  Environment(const Environment&) = default;
  Environment& operator=(const Environment&) = default;

  const FieldTrialsView& field_trials() const { return *field_trials_; }

 private:
  const FieldTrialsView* field_trials_;
};

}  // namespace webrtc

#endif  // TEYA_WEBRTC_SHIM_API_ENVIRONMENT_ENVIRONMENT_H_
