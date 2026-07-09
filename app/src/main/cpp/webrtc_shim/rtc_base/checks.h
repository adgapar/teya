// Teya build shim — NOT the vendored upstream rtc_base/checks.h.
//
// Upstream checks.h builds pretty "a == b (1 vs. 2)" failure messages using
// absl::StrCat / absl::HasAbslStringify (absl/strings/has_absl_stringify.h,
// absl/strings/str_cat.h) plus api/scoped_refptr.h and
// rtc_base/system/inline.h. None of that is AEC3 signal-processing logic —
// it's diagnostic message formatting. This shim keeps the exact macro names
// and the exact abort-on-failure contract (RTC_CHECK always evaluates its
// condition and aborts the process on failure; RTC_DCHECK does the same only
// when RTC_DCHECK_IS_ON) while building the (optional, best-effort) failure
// message with plain ostringstream `<<` streaming instead of absl.
//
// Unlike the message-formatting bits, rtc_base/numerics/safe_compare.h is
// NOT dropped — it's real, vendored, unmodified (see VENDORING.md), and used
// as-is for RTC_CHECK_OP's comparisons (Phase 3b finding: plain `<`/`>=`
// on a mixed size_t/int pair silently promotes a negative int to a huge
// size_t and false-fires, e.g. `DownsampledRenderBuffer::OffsetIndex`'s
// `RTC_DCHECK_GE(buffer.size(), offset)` with a negative offset — exactly
// the case safe_compare.h exists to handle correctly).
//
// If a real vendored file needs a macro this shim doesn't define, add it
// here (grep the vendored tree for the macro name) rather than silently
// letting the check disappear.
#ifndef TEYA_WEBRTC_SHIM_RTC_BASE_CHECKS_H_
#define TEYA_WEBRTC_SHIM_RTC_BASE_CHECKS_H_

#include <cstdlib>
#include <sstream>
#include <string>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

// Real, vendored (not shimmed) — needed so RTC_CHECK_OP/RTC_DCHECK_OP compare mixed
// signed/unsigned integer operands the same way upstream's checks.h does. Plain `<`/`>=`/etc.
// promote a negative int to a huge size_t when compared against one (e.g. `buffer.size() >=
// -offset`), which false-fires on perfectly valid calls — this is exactly why upstream's real
// checks.h depends on safe_compare.h instead of using the built-in operators directly.
#include "rtc_base/numerics/safe_compare.h"

#if !defined(NDEBUG) || defined(DCHECK_ALWAYS_ON)
#define RTC_DCHECK_IS_ON 1
#else
#define RTC_DCHECK_IS_ON 0
#endif

namespace webrtc {
namespace webrtc_checks_impl {

// Constructed by every RTC_CHECK/RTC_DCHECK invocation (pass or fail), and
// accepts `<<` streamed context (like upstream's LogStreamer). If
// `triggered` is true, logs the accumulated message and aborts the process
// in the destructor. The message stream is only allocated when `triggered`
// is true, so a passing check costs one bool store plus a null-pointer
// init — no ostringstream construction on the (hot, per-10ms-frame) common
// path.
class CheckFailure {
 public:
  CheckFailure(const char* file, int line, const char* expr, bool triggered)
      : triggered_(triggered) {
    if (triggered_) {
      stream_ = new std::ostringstream();
      (*stream_) << file << ":" << line << ": CHECK failed: " << expr;
    }
  }

  CheckFailure(const CheckFailure&) = delete;
  CheckFailure& operator=(const CheckFailure&) = delete;

  ~CheckFailure() {
    if (!triggered_) {
      return;
    }
    const std::string message = stream_->str();
    delete stream_;
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_FATAL, "teya_aec3", "%s", message.c_str());
#endif
    std::abort();
  }

  template <typename T>
  CheckFailure& operator<<(const T& value) {
    if (triggered_) {
      (*stream_) << value;
    }
    return *this;
  }

 private:
  bool triggered_;
  std::ostringstream* stream_ = nullptr;
};

}  // namespace webrtc_checks_impl
}  // namespace webrtc

#define RTC_CHECK(condition)                              \
  ::webrtc::webrtc_checks_impl::CheckFailure(__FILE__, __LINE__, #condition, \
                                             !(condition))

// safe_fn (e.g. ::webrtc::SafeGe) does the actual mixed-signedness-correct comparison; op (e.g.
// >=) is kept in the failure message for a human-readable "a >= b" rendering.
#define RTC_CHECK_SAFE_OP(safe_fn, op, val1, val2)     \
  ::webrtc::webrtc_checks_impl::CheckFailure(          \
      __FILE__, __LINE__, #val1 " " #op " " #val2,     \
      !(safe_fn((val1), (val2))))

#define RTC_CHECK_EQ(v1, v2) RTC_CHECK_SAFE_OP(::webrtc::SafeEq, ==, v1, v2)
#define RTC_CHECK_NE(v1, v2) RTC_CHECK_SAFE_OP(::webrtc::SafeNe, !=, v1, v2)
#define RTC_CHECK_LE(v1, v2) RTC_CHECK_SAFE_OP(::webrtc::SafeLe, <=, v1, v2)
#define RTC_CHECK_LT(v1, v2) RTC_CHECK_SAFE_OP(::webrtc::SafeLt, <, v1, v2)
#define RTC_CHECK_GE(v1, v2) RTC_CHECK_SAFE_OP(::webrtc::SafeGe, >=, v1, v2)
#define RTC_CHECK_GT(v1, v2) RTC_CHECK_SAFE_OP(::webrtc::SafeGt, >, v1, v2)

#define RTC_CHECK_NOTREACHED() \
  ::webrtc::webrtc_checks_impl::CheckFailure(__FILE__, __LINE__, "NOTREACHED", true)

#define RTC_FATAL() \
  ::webrtc::webrtc_checks_impl::CheckFailure(__FILE__, __LINE__, "FATAL()", true)

#if RTC_DCHECK_IS_ON
#define RTC_DCHECK(condition) RTC_CHECK(condition)
#define RTC_DCHECK_EQ(v1, v2) RTC_CHECK_EQ(v1, v2)
#define RTC_DCHECK_NE(v1, v2) RTC_CHECK_NE(v1, v2)
#define RTC_DCHECK_LE(v1, v2) RTC_CHECK_LE(v1, v2)
#define RTC_DCHECK_LT(v1, v2) RTC_CHECK_LT(v1, v2)
#define RTC_DCHECK_GE(v1, v2) RTC_CHECK_GE(v1, v2)
#define RTC_DCHECK_GT(v1, v2) RTC_CHECK_GT(v1, v2)
#define RTC_DCHECK_NOTREACHED() RTC_CHECK_NOTREACHED()
#else
// Matches upstream's contract: RTC_DCHECK is a no-op that never aborts in
// release builds. `triggered` is hardcoded false so `condition` is still
// syntactically referenced (avoids -Wunused-*) without ever being evaluated
// for its truth value's effect on control flow, and a trailing `<<` chain
// stays valid without doing any work.
#define RTC_DCHECK(condition) \
  ::webrtc::webrtc_checks_impl::CheckFailure(__FILE__, __LINE__, "", false)
#define RTC_DCHECK_EQ(v1, v2) RTC_DCHECK(true)
#define RTC_DCHECK_NE(v1, v2) RTC_DCHECK(true)
#define RTC_DCHECK_LE(v1, v2) RTC_DCHECK(true)
#define RTC_DCHECK_LT(v1, v2) RTC_DCHECK(true)
#define RTC_DCHECK_GE(v1, v2) RTC_DCHECK(true)
#define RTC_DCHECK_GT(v1, v2) RTC_DCHECK(true)
#define RTC_DCHECK_NOTREACHED() \
  ::webrtc::webrtc_checks_impl::CheckFailure(__FILE__, __LINE__, "", false)
#endif

#endif  // TEYA_WEBRTC_SHIM_RTC_BASE_CHECKS_H_
