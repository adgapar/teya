# Builds the microWakeWord audio feature extractor (TFLite Micro's
# "microfrontend") into a single JNI shared library. See
# app/src/main/cpp/microfrontend/VENDORING.md for provenance of the vendored
# sources this compiles.

cmake_minimum_required(VERSION 3.22.1)
project(teya_microfrontend)

set(MICROFRONTEND_DIR ${CMAKE_CURRENT_SOURCE_DIR}/microfrontend)
set(TFLITE_MICRO_LIB_DIR ${MICROFRONTEND_DIR}/tflite-micro/tensorflow/lite/experimental/microfrontend/lib)
set(KISSFFT_DIR ${MICROFRONTEND_DIR}/kissfft)

# tflite-micro's kiss_fft_int16.{h,cc} source-include kissfft's real/complex
# FFT as "tools/kiss_fftr.{h,c}" (the path kissfft's own CMake FetchContent
# target places them at upstream). Our vendored kissfft/ mirrors kissfft's
# actual repo layout instead (kiss_fftr.* at the root, per VENDORING.md), so
# mirror HA's fix here: copy those two files into a "tools/" shim directory
# at configure time and add it as an include root.
set(KISSFFT_TOOLS_SHIM_DIR ${CMAKE_CURRENT_BINARY_DIR}/kissfft_tools_shim)
file(MAKE_DIRECTORY ${KISSFFT_TOOLS_SHIM_DIR}/tools)
file(COPY_FILE ${KISSFFT_DIR}/kiss_fftr.h ${KISSFFT_TOOLS_SHIM_DIR}/tools/kiss_fftr.h ONLY_IF_DIFFERENT)
file(COPY_FILE ${KISSFFT_DIR}/kiss_fftr.c ${KISSFFT_TOOLS_SHIM_DIR}/tools/kiss_fftr.c ONLY_IF_DIFFERENT)

add_library(microfrontend SHARED
    ${MICROFRONTEND_DIR}/MicroFrontend_jni.cpp
    ${MICROFRONTEND_DIR}/MicroFrontendWrapper.cpp
    ${TFLITE_MICRO_LIB_DIR}/fft.cc
    ${TFLITE_MICRO_LIB_DIR}/fft_util.cc
    ${TFLITE_MICRO_LIB_DIR}/kiss_fft_int16.cc
    ${TFLITE_MICRO_LIB_DIR}/filterbank.c
    ${TFLITE_MICRO_LIB_DIR}/filterbank_util.c
    ${TFLITE_MICRO_LIB_DIR}/frontend.c
    ${TFLITE_MICRO_LIB_DIR}/frontend_util.c
    ${TFLITE_MICRO_LIB_DIR}/log_lut.c
    ${TFLITE_MICRO_LIB_DIR}/log_scale.c
    ${TFLITE_MICRO_LIB_DIR}/log_scale_util.c
    ${TFLITE_MICRO_LIB_DIR}/noise_reduction.c
    ${TFLITE_MICRO_LIB_DIR}/noise_reduction_util.c
    ${TFLITE_MICRO_LIB_DIR}/pcan_gain_control.c
    ${TFLITE_MICRO_LIB_DIR}/pcan_gain_control_util.c
    ${TFLITE_MICRO_LIB_DIR}/window.c
    ${TFLITE_MICRO_LIB_DIR}/window_util.c
)

target_include_directories(microfrontend PRIVATE
    ${MICROFRONTEND_DIR}/tflite-micro
    ${KISSFFT_DIR}
    ${KISSFFT_TOOLS_SHIM_DIR}
)

# Fixes the fixed-point (vs. float/double) kiss_fft resolution — must match
# what hey_teya.tflite was trained/calibrated against. See VENDORING.md.
target_compile_definitions(microfrontend PRIVATE FIXED_POINT=16)

# Vendored, unmodified upstream C — its warnings are not this project's to
# fix. -ffast-math is safe only because FIXED_POINT=16 means no float FFT
# path is actually taken (same rationale HA's CMakeLists.txt documents).
target_compile_options(microfrontend PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -ffast-math
)

find_library(log-lib log)
target_link_libraries(microfrontend ${log-lib})
