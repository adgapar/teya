---
date: 2026-07-09T00:00:00Z
topic: "WebRTC AEC3 barge-in session handoff — Plan A implemented, Plan B drafted+reviewed"
status: handoff
---

# Session Handoff: WebRTC AEC3 Barge-In

## Where things stand

**Plan A — native AEC3 module: fully implemented, verified on-device, committed to `main`.**
Plan doc: `thoughts/shared/plans/2026-07-09-webrtc-aec3-native-port.md` (status: complete).

Commits (in order, all on `main`):
- `f0bda45` — Phase 1: vendor AEC3 source + provenance/licensing
- `017abd8` — Phase 2: NDK/CMake toolchain skeleton (trivial passthrough)
- `33f8945` — Phase 3a: compile AEC3 + deps as standalone static lib
- `cd7294f` — Phase 3b: JNI glue + Kotlin wrapper (real render/capture API)
- `7f6d8f9` — Phase 4: on-device validation (real measured suppression numbers)

Result: `NativeAec3` (`app/src/main/kotlin/com/teya/agent/voice/aec/NativeAec3.kt`) is a real,
working, on-device-validated echo canceller — construct → `analyzeRender(frame)` /
`processCapture(frame): frame` → `close()`, exactly 160-sample (10ms @ 16kHz) frames. Measured on
SM-A346E: ~72dB pure-echo suppression, ~51dB echo suppression vs ~0.01dB loss on an independent
signal in the speech+echo case. **Not yet wired into the real voice pipeline** — that's Plan B.

**One real finding to remember**: AEC3 will not converge from double-talk starting at frame zero
(~0dB suppression measured). It needs a genuine echo-only lead-in before trusting its output. This
drove Plan B's central design decision (below).

**Plan B — VoicePipeline/HarnessService integration: drafted and adversarially reviewed, errata
resolved in-document. Not yet implemented.**
Plan doc: `thoughts/shared/plans/2026-07-09-webrtc-aec3-voicepipeline-integration.md` (status: draft).

Key design decision baked into the plan (this is the thing most worth re-reading before
implementing): **`NativeAec3`'s lifecycle spans the whole conversation session, not each
armed-window/turn** — deliberately *not* mirroring `SileroVad`'s per-turn construct/close pattern.
Confirmed via code trace that `setBargeInArmed(true)`/`(false)` brackets exactly one `respond()`
call (`HarnessService.kt:305,309,325,332`), so a per-turn `NativeAec3` lifecycle would force a
fresh ~1-2s convergence lead-in on *every* turn, not just after a barge-in. Fix: `HarnessService.
runConversation()` constructs/closes one `NativeAec3` for the whole session; `SileroVad` keeps its
existing per-turn lifecycle unchanged.

Other load-bearing facts the plan is built on (all confirmed via code, cited file:line in the
plan's Current State Analysis):
- `playMp3` (MediaPlayer fallback) has **no PCM access at all** — AEC3 can only cover the
  `streamToSpeaker` path. This is a hard constraint, not a gap.
- The streaming-vs-`playMp3` choice is made **independently per sentence**
  (`VoicePipeline.textToSpeech`, `VoicePipeline.kt:373-385`), not once per turn — a single
  multi-sentence response could mix both paths.
- No resampler exists in the codebase — Plan B's Phase 1 writes one from scratch (24kHz→16kHz,
  committed to linear interpolation at the exact 2/3 resampling positions).

Plan B has a Failure Mode/Rollback section + an explicit kill-switch (single boolean/feature
constant) since this changes live daily-use voice UX with no PR/branch safety net (this project
commits direct to `main`).

## What's next

Implement Plan B, phase by phase, the same way Plan A was implemented: via `desplega:implementing`
→ `desplega:phase-running` background sub-agents, one phase at a time, reviewing each phase's diff
before committing. The plan's 5 phases:

1. 24kHz→16kHz resampler (pure Kotlin, JVM-testable, no device needed)
2. Feed real TTS audio into `analyzeRender` (session-scoped `NativeAec3` lifecycle)
3. Feed cleaned capture audio into `SileroVad` (capture-side wiring, still gap-gated for now)
4. Remove the gate/gap for streamed sentences + add the kill-switch (the actual behavior change)
5. Real-device tuning with logged diagnostics (no synthetic test can prove this one — needs a human)

To resume: open a new session, read `thoughts/shared/plans/2026-07-09-webrtc-aec3-voicepipeline-integration.md`
in full (it's self-contained — Current State Analysis has all the file:line citations needed), then
invoke `desplega:implementing` (or `/implement-plan`) against it, same flow as before.

## Working notes for whoever (or whatever) picks this up

- Repo convention (from CLAUDE.md + established this session): solo project, commit direct to
  `main`, no branches/PRs, but still split into revertible commits — one per phase.
- Background phase-running agents update the plan's own checkboxes on completion; the orchestrator
  (main session) should wait for `TaskOutput(block=true)` rather than trying to check boxes early,
  and should review the actual diff (not just trust the agent's self-report) before committing,
  especially for anything touching native/threading/lifecycle code.
- Every review pass this session (Plan A's errata, Plan B's errata) surfaced real, substantive
  issues — worth running `desplega:reviewing` again after Plan B's implementation too, not just at
  the planning stage.
