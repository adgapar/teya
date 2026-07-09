// Teya build shim — NOT the vendored upstream rtc_base/logging.h.
//
// Upstream logging.h pulls in api/task_queue/task_queue_base.h,
// rtc_base/string_encode.h, rtc_base/strings/string_builder.h,
// rtc_base/synchronization/mutex.h and rtc_base/time_utils.h to support
// pluggable, thread-safe global LogSink routing. None of that affects
// AEC3's actual DSP — it's purely where diagnostic text goes. This shim
// keeps the RTC_LOG(severity) macro AEC3 actually uses (see VENDORING.md)
// and writes straight to the Android log, skipping the sink-registration
// machinery entirely.
#ifndef TEYA_WEBRTC_SHIM_RTC_BASE_LOGGING_H_
#define TEYA_WEBRTC_SHIM_RTC_BASE_LOGGING_H_

#include <sstream>
#include <string>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace webrtc {

enum LoggingSeverity { LS_VERBOSE, LS_INFO, LS_WARNING, LS_ERROR, LS_NONE };

namespace webrtc_logging_impl {

class LogMessage {
 public:
  explicit LogMessage(LoggingSeverity severity) : severity_(severity) {}

  ~LogMessage() {
#if defined(__ANDROID__)
    int prio = ANDROID_LOG_INFO;
    switch (severity_) {
      case LS_VERBOSE:
        prio = ANDROID_LOG_VERBOSE;
        break;
      case LS_INFO:
        prio = ANDROID_LOG_INFO;
        break;
      case LS_WARNING:
        prio = ANDROID_LOG_WARN;
        break;
      case LS_ERROR:
        prio = ANDROID_LOG_ERROR;
        break;
      case LS_NONE:
        return;
    }
    __android_log_print(prio, "teya_aec3", "%s", stream_.str().c_str());
#endif
  }

  template <typename T>
  LogMessage& operator<<(const T& value) {
    stream_ << value;
    return *this;
  }

 private:
  LoggingSeverity severity_;
  std::ostringstream stream_;
};

}  // namespace webrtc_logging_impl
}  // namespace webrtc

#define RTC_LOG(severity) \
  ::webrtc::webrtc_logging_impl::LogMessage(::webrtc::severity)

// Like RTC_LOG(), but `severity` is a run-time LoggingSeverity value (e.g. a
// local variable) rather than a literal token, so it isn't prefixed with
// `::webrtc::`. Used by block_processor.cc/render_delay_buffer.cc/
// echo_remover.cc to pick a severity based on config.
#define RTC_LOG_V(severity) ::webrtc::webrtc_logging_impl::LogMessage(severity)

#endif  // TEYA_WEBRTC_SHIM_RTC_BASE_LOGGING_H_
