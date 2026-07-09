# Teya native module — AEC3 (Acoustic Echo Canceller v3), vendored from WebRTC.
#
# Phase 2 of thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md: this is a trivial
# passthrough skeleton proving NDK -> CMake -> .so -> JNI -> Kotlin works end-to-end. No AEC3 code
# (vendored under third_party/webrtc/, see VENDORING.md) is compiled yet — that's Phase 3a/3b.

cmake_minimum_required(VERSION 3.22.1)

project(teya_aec3)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(teya_aec3 SHARED
    jni_ping.cpp
)

find_library(log-lib log)

target_link_libraries(teya_aec3
    ${log-lib}
)
