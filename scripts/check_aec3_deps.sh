#!/usr/bin/env bash
# Verifies that every dependency listed in the vendored AEC3 BUILD.gn's top-level "aec3" target
# has corresponding vendored source files under app/src/main/cpp/third_party/webrtc/.
#
# This is Phase 1's Automated QA check for the WebRTC AEC3 vendoring plan
# (thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md). It parses the vendored
# modules/audio_processing/aec3/BUILD.gn's `deps = [...]` list (the primary, unconditional list —
# conditional `deps += [...]` additions like the x86-only `aec3_avx2` target are intentionally
# NOT picked up, since that variant was deliberately stripped from vendoring), resolves each
# dependency to a directory + BUILD.gn target within the vendored tree, and checks that the
# target's own declared `sources` files actually exist on disk.
#
# Known, documented exception: `//third_party/abseil-cpp/...` deps are reported as SKIPPED, not
# FAILED — Abseil vendoring is explicitly deferred to a later phase (see VENDORING.md's
# "Known gaps" section). Any other unresolvable dependency is a real FAIL.
#
# Usage: scripts/check_aec3_deps.sh
# Exit code: 0 if all non-Abseil deps resolve to vendored files, 1 otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEBRTC_ROOT="${REPO_ROOT}/app/src/main/cpp/third_party/webrtc"
AEC3_DIR="${WEBRTC_ROOT}/modules/audio_processing/aec3"
AEC3_BUILD_GN="${AEC3_DIR}/BUILD.gn"

if [ ! -f "${AEC3_BUILD_GN}" ]; then
  echo "FAIL: vendored AEC3 BUILD.gn not found at ${AEC3_BUILD_GN}" >&2
  exit 1
fi

python3 - "$WEBRTC_ROOT" "$AEC3_DIR" "$AEC3_BUILD_GN" <<'PYEOF'
import re
import sys
import os

webrtc_root, aec3_dir, aec3_build_gn = sys.argv[1], sys.argv[2], sys.argv[3]


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def find_target_body(text, target_name):
    """Find the brace-matched body of a rtc_library/rtc_source_set/etc target block."""
    pattern = re.compile(
        r'(?:rtc_\w+|source_set)\(\s*"' + re.escape(target_name) + r'"\s*\)\s*\{'
    )
    m = pattern.search(text)
    if not m:
        return None
    start = m.end()
    depth = 1
    i = start
    while depth > 0 and i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return text[start : i - 1]


def find_unconditional_deps(body):
    """Only `deps = [...]` (not `deps += [...]`) — matches Phase 1's intentional exclusion of
    conditional additions like the stripped x86-only aec3_avx2 target."""
    m = re.search(r"(?<!\+)deps\s*=\s*\[(.*?)\]", body, re.DOTALL)
    if not m:
        return []
    deps = []
    for line in m.group(1).split(","):
        line = line.strip().strip('"')
        if line:
            deps.append(line)
    return deps


def find_all_sources(body):
    """Union of every `sources = [...]` / `sources += [...]` occurrence in the body — needed
    because some targets (e.g. rtc_base:logging) declare sources inside if/else branches."""
    files = []
    for m in re.finditer(r"sources\s*\+?=\s*\[(.*?)\]", body, re.DOTALL):
        for line in m.group(1).split(","):
            line = line.strip().strip('"')
            if line:
                files.append(line)
    return files


def resolve_dep(dep, current_dir):
    """Resolve a GN dep string (e.g. ':foo', '..:bar', '../../../rtc_base:checks',
    '//third_party/x:y', '../../../api/environment') to (resolved_dir, target_name)."""
    if ":" in dep:
        path_part, target = dep.rsplit(":", 1)
    else:
        path_part, target = dep, os.path.basename(dep.rstrip("/"))

    if path_part.startswith("//"):
        resolved_dir = os.path.normpath(os.path.join(webrtc_root, path_part[2:]))
    elif path_part == "":
        resolved_dir = current_dir
    else:
        resolved_dir = os.path.normpath(os.path.join(current_dir, path_part))

    return resolved_dir, target


failures = []
skipped = []
checked = 0

aec3_text = read(aec3_build_gn)
aec3_body = find_target_body(aec3_text, "aec3")
if aec3_body is None:
    print("FAIL: could not locate the top-level \"aec3\" target block in BUILD.gn", file=sys.stderr)
    sys.exit(1)

deps = find_unconditional_deps(aec3_body)
if not deps:
    print("FAIL: no deps found in the \"aec3\" target — parser likely broken", file=sys.stderr)
    sys.exit(1)

for dep in deps:
    if dep.startswith("//third_party/abseil-cpp"):
        skipped.append(dep)
        continue

    resolved_dir, target = resolve_dep(dep, aec3_dir)
    build_gn = os.path.join(resolved_dir, "BUILD.gn")

    if not os.path.isfile(build_gn):
        failures.append(f"{dep}: no vendored BUILD.gn at {os.path.relpath(build_gn, webrtc_root)}")
        continue

    body = find_target_body(read(build_gn), target)
    if body is None:
        failures.append(f"{dep}: target \"{target}\" not found in {os.path.relpath(build_gn, webrtc_root)}")
        continue

    sources = find_all_sources(body)
    if not sources:
        # header-only umbrella targets with no sources of their own are fine (e.g. pure deps
        # aggregators); nothing to check for missing files.
        checked += 1
        continue

    for src in sources:
        if "webrtc_overrides" in src:
            # Chromium-only override branch — intentionally not vendored for a standalone build.
            continue
        src_path = os.path.normpath(os.path.join(resolved_dir, src))
        checked += 1
        if not os.path.isfile(src_path):
            failures.append(
                f"{dep} (target \"{target}\"): missing vendored file "
                f"{os.path.relpath(src_path, webrtc_root)}"
            )

print(f"Checked {checked} source-file references across {len(deps)} deps "
      f"({len(skipped)} skipped, {len(failures)} failed).")

if skipped:
    print("\nSkipped (documented exception — not vendored in this phase):")
    for s in skipped:
        print(f"  - {s}")

if failures:
    print("\nFAILURES:", file=sys.stderr)
    for f in failures:
        print(f"  - {f}", file=sys.stderr)
    sys.exit(1)

print("\nPASS: every non-Abseil dependency in aec3/BUILD.gn resolves to a vendored file.")
sys.exit(0)
PYEOF
