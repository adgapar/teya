package com.teya.agent.ui.face

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A pluggable presence design: how the wall face looks during conversation ([Face], driven by
 * [AgentState]) and how the same identity shows up as Admin/onboarding's ambient background
 * ([Ambient], driven by [OnboardingCategory]). Adding a new visualization means implementing this
 * interface and adding it to [AgentVisualizations.all] — no other file needs to change.
 */
interface AgentVisualization {
    /** Persisted via ConfigManager.faceStyle — stable once shipped, never renamed. */
    val id: String

    /** Shown in Admin's picker. */
    val displayName: String

    /** Whether the wall screen should physically be portrait for this style — not just a
     *  vertical-proportioned drawing inside a landscape canvas. Most stay landscape (the device's
     *  own default); [FormedFaceVisualization] is the one that actually needs the screen itself
     *  vertical, since a small round vertical face is the whole point of that design. */
    val prefersPortrait: Boolean get() = false

    /** Where MainScreen's transcript ("Say Hey Teya" / the live reply) sits relative to the face.
     *  The particle field's own vignette is centered on the canvas, so text there stays centered
     *  too; [FormedFaceVisualization] deliberately leaves a gap between eyes and mouth empty and
     *  puts the transcript below instead, as a caption under the face rather than inside it. */
    val transcriptAlignment: Alignment get() = Alignment.Center

    @Composable
    fun Face(state: AgentState, modifier: Modifier = Modifier)

    @Composable
    fun Ambient(category: OnboardingCategory, memberCount: Int, modifier: Modifier = Modifier, activeSlot: Int = -1)
}

private object ParticleVisualization : AgentVisualization {
    override val id = "particles"
    override val displayName = "Particles"

    @Composable
    override fun Face(state: AgentState, modifier: Modifier) {
        AgentFace(state = state, modifier = modifier)
    }

    @Composable
    override fun Ambient(category: OnboardingCategory, memberCount: Int, modifier: Modifier, activeSlot: Int) {
        OnboardingParticles(category = category, memberCount = memberCount, modifier = modifier, activeSlot = activeSlot)
    }
}

private object FormedFaceVisualization : AgentVisualization {
    override val id = "face"
    override val displayName = "Face"
    override val prefersPortrait = true
    override val transcriptAlignment = Alignment.BottomCenter

    @Composable
    override fun Face(state: AgentState, modifier: Modifier) {
        FormedFace(state = state, modifier = modifier)
    }

    @Composable
    override fun Ambient(category: OnboardingCategory, memberCount: Int, modifier: Modifier, activeSlot: Int) {
        FormedFaceAmbient(category = category, memberCount = memberCount, modifier = modifier, activeSlot = activeSlot)
    }
}

/** The registry — every selectable visualization lives here. */
object AgentVisualizations {
    val all: List<AgentVisualization> = listOf(ParticleVisualization, FormedFaceVisualization)
    val default: AgentVisualization = ParticleVisualization

    /** Resolves a persisted id, falling back to [default] for anything unrecognized — including
     *  this session's now-retired "ABSTRACT"/"FACE" enum-name values from the particles-form-a-face
     *  experiment, mapped across so a device that tried that doesn't silently reset. */
    fun byId(id: String?): AgentVisualization {
        val normalized = when (id) {
            "ABSTRACT" -> "particles"
            "FACE" -> "face"
            else -> id
        }
        return all.firstOrNull { it.id == normalized } ?: default
    }
}
