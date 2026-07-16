---
name: add-visualization
description: Scaffolds a new AgentVisualization (a pluggable design for Teya's wall face, Admin background, and onboarding background) and registers it so it shows up in Admin's picker automatically. Use when the user wants to add a new face style, a new visualization, a new formation, or asks to make a third/fourth option alongside "particles"/"face".
---

# Add a visualization

Teya's presence — the conversational face, Admin's background, onboarding's background — is
pluggable via `AgentVisualization` (`app/src/main/kotlin/com/teya/agent/ui/face/AgentVisualization.kt`).
One setting (`ConfigManager.faceStyle`) drives all three surfaces through one registry
(`AgentVisualizations.all`). Two implementations exist today: `ParticleVisualization` (the original
particle field) and `FormedFaceVisualization` (`FormedFace.kt` — isolated vector eyes+mouth). Adding
a third does **not** require touching either of those, `MainActivity`, `SettingsActivity`,
`SetupActivity`, or the Admin picker — they all read the registry generically.

## Steps

1. **New file** for the visualization's own renderer, e.g. `ui/face/MyVisualization.kt`. Write two
   `@Composable` functions:
   - `fun MyFace(state: AgentState, modifier: Modifier = Modifier)` — the conversational face.
   - `fun MyAmbient(category: OnboardingCategory, memberCount: Int, modifier: Modifier = Modifier, activeSlot: Int = -1)` — Admin/onboarding's background.

   Keep it **isolated**: don't import from `AgentFace.kt`/`FormedFace.kt`, don't share internal
   classes. `FormedFace.kt` is the template to copy the shape of (own engine class, own pose
   functions, own color constants) — not to extend.

2. **`AgentState` cases** (5): `IDLE, LISTENING, THINKING, SPEAKING, BRAIN_OFF`. Match the emotional
   intent other visualizations already give each one (see `stateColor()` in `AgentFace.kt` for the
   canonical accent hue per state) rather than inventing new meanings.

3. **`OnboardingCategory` cases** (9): `INTRO, HOUSEHOLD, LANGUAGES, HOME, DONE` (the 5 narrative
   onboarding steps — ride `category.progress: Float` for a cool→warm ramp, like
   `OnboardingParticles.kt`'s `warmthRgb()` does) and `MEMORY, VOICE, API, TRAINER` (4 Admin-only
   sections — give each its own fixed tint, ignore `progress`). `HOUSEHOLD` also gets `memberCount`
   and `activeSlot` (which member is currently in focus in Admin's person-pager, -1 = none).

   **Keep `Ambient()` quiet.** It renders behind Admin's real text fields and buttons — a fully
   expressive face there reads as broken layout, not presence (see `FormedFaceAmbient`'s
   `drawGlowOnly` vs. `FormedFace`'s full render — same eased/tinted state, different amount of
   visual weight). A soft tinted glow is enough; save expressiveness for the conversational `Face()`.

4. **Register it** in `AgentVisualization.kt`:
   ```kotlin
   private object MyVisualization : AgentVisualization {
       override val id = "my-id"              // persisted string — never rename once shipped
       override val displayName = "My Style"  // shown in Admin's picker
       // override val prefersPortrait = true      // only if the screen itself should rotate
       // override val transcriptAlignment = Alignment.BottomCenter  // only if not center-gap
       @Composable override fun Face(state: AgentState, modifier: Modifier) = MyFace(state, modifier)
       @Composable override fun Ambient(category: OnboardingCategory, memberCount: Int, modifier: Modifier, activeSlot: Int) =
           MyAmbient(category, memberCount, modifier, activeSlot)
   }
   ```
   Then add `MyVisualization` to `AgentVisualizations.all`. That's the whole integration — Admin's
   picker (`FaceStylePicker` in `ui/admin/AdminComposables.kt`) iterates this list and renders a live
   preview per option automatically.

5. **Build + verify on-device** (see CLAUDE.md's Build/install section) — `./gradlew installDebug --offline`,
   launch, long-press the orb → Admin → Settings, pick the new style, check the main face, then
   check Admin's own background (any section) and onboarding (fresh install or `SetupActivity`
   directly) for the glow-only treatment.
