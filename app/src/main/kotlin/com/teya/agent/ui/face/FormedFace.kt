package com.teya.agent.ui.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A second, fully isolated visualization: two eyes + a mouth, drawn as plain vector shapes (solid
 * glow-cored circles, a filled curve) rather than a particle field — its own module, sharing no
 * internals with [AgentFace]/[OnboardingParticles], selectable in place of either via
 * [AgentVisualization]. [FormedFace] is the conversational face (driven by [AgentState]);
 * [FormedFaceAmbient] is its counterpart for Admin/onboarding (driven by [OnboardingCategory]) —
 * picking this style is meant to feel the same everywhere the app shows a presence, not just here.
 */
private val TAU = PI * 2
private val FaceInk = Color(0xFFF7F1EE)

private val IDLE_RGB = floatArrayOf(74f, 134f, 200f)
private val LISTEN_RGB = floatArrayOf(69f, 208f, 224f)
private val THINK_RGB = floatArrayOf(169f, 139f, 255f)
private val SPEAK_RGB = floatArrayOf(255f, 190f, 75f)
private val BRAIN_OFF_RGB = floatArrayOf(156f, 86f, 80f)
private val GUARDED_RGB = floatArrayOf(120f, 140f, 150f) // Admin-only API/TRAINER — a small guarded value

@Composable
fun FormedFace(state: AgentState, modifier: Modifier = Modifier) {
    val engine = remember { FaceEngine() }
    val current by rememberUpdatedState(state)
    var frameNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) { withFrameNanos { frameNanos = it } }
    }

    Canvas(modifier.fillMaxSize().background(FaceBackground)) {
        val dt = engine.consumeDt(frameNanos)
        val r = (min(size.width, size.height) / 2.3f).toDouble()
        engine.step(dt, agentStateTarget(current, engine.timeSec.toDouble(), r))
        engine.draw(this, size.width / 2f, size.height / 2f)
    }
}

/** Admin/onboarding's ambient background in this style — same call shape as [OnboardingParticles]
 *  so it drops into the same call sites. Deliberately NOT the expressive face: eyes/mouth sitting
 *  over Admin's own text fields and buttons read as broken layout rather than presence, so this is
 *  just the same category-tinted glow, breathing gently — ambient, not a face staring out from
 *  behind the content. */
@Composable
fun FormedFaceAmbient(
    category: OnboardingCategory,
    memberCount: Int,
    modifier: Modifier = Modifier,
    activeSlot: Int = -1,
) {
    val engine = remember { FaceEngine() }
    val currentCategory by rememberUpdatedState(category)
    val currentMemberCount by rememberUpdatedState(memberCount)
    val currentActiveSlot by rememberUpdatedState(activeSlot)
    var frameNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) { withFrameNanos { frameNanos = it } }
    }

    Canvas(modifier) {
        val dt = engine.consumeDt(frameNanos)
        val r = (min(size.width, size.height) / 2.3f).toDouble()
        engine.step(dt, categoryTarget(currentCategory, engine.timeSec.toDouble(), r, currentMemberCount, currentActiveSlot))
        engine.drawGlowOnly(this, size.width / 2f, size.height / 2f)
    }
}

/** One frame's target pose — eased toward by [FaceEngine], never drawn directly. Vertical
 *  (taller-than-wide) by construction: [gazeY]'s baseline sits well above center, [mouthCy] well
 *  below, so the face reads as portrait regardless of the surrounding screen's own aspect, and so
 *  a caller's own centered UI (e.g. MainScreen's transcript) lands in the gap between the two. */
private class FaceTarget(
    val eyeR: Float, val spacing: Float,
    val gazeX: Float, val gazeY: Float,
    val openL: Float, val openR: Float,
    val headX: Float, val headY: Float,
    val mouthCy: Float, val mouthAmp: Float, val mouthWidth: Float, val mouthWobble: Float,
    val rgb: FloatArray,
    val forceShut: Boolean = false,
)

/** Eases toward each frame's [FaceTarget] (position/size/color) and runs an independent blink
 *  timer, then draws two eyes and a mouth. No particles — a handful of eased scalars and three
 *  drawn shapes. */
private class FaceEngine {
    var timeSec = 0f; private set
    private var lastNanos = 0L
    private var seeded = false

    private val col = floatArrayOf(90f, 130f, 170f)
    private var eyeR = 0f; private var spacing = 0f
    private var gazeX = 0f; private var gazeY = 0f
    private var openL = 1f; private var openR = 1f
    private var headX = 0f; private var headY = 0f
    private var mouthCy = 0f; private var mouthAmp = 0f; private var mouthWidth = 0f; private var mouthWobble = 0f

    private var inBlink = false
    private var blinkT = 0f
    private var sinceLastBlink = 0f
    private var blinkThresholdMs = 1800f
    private var blinkOpen = 1f

    fun consumeDt(now: Long): Float {
        val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1_000_000f).coerceIn(0f, 100f)
        lastNanos = now
        timeSec += dt / 1000f
        return dt
    }

    fun step(dtMs: Float, target: FaceTarget) {
        blinkOpen = updateBlink(dtMs, target.forceShut)

        if (!seeded) {
            eyeR = target.eyeR; spacing = target.spacing
            gazeX = target.gazeX; gazeY = target.gazeY
            openL = target.openL; openR = target.openR
            headX = target.headX; headY = target.headY
            mouthCy = target.mouthCy; mouthAmp = target.mouthAmp
            mouthWidth = target.mouthWidth; mouthWobble = target.mouthWobble
            col[0] = target.rgb[0]; col[1] = target.rgb[1]; col[2] = target.rgb[2]
            seeded = true
        }

        val ck = (dtMs / 340f).coerceAtMost(1f)
        for (c in 0..2) col[c] += (target.rgb[c] - col[c]) * ck

        val k = (dtMs / 200f).coerceAtMost(1f)
        eyeR += (target.eyeR - eyeR) * k
        spacing += (target.spacing - spacing) * k
        gazeX += (target.gazeX - gazeX) * k
        gazeY += (target.gazeY - gazeY) * k
        openL += (target.openL - openL) * k
        openR += (target.openR - openR) * k
        headX += (target.headX - headX) * k
        headY += (target.headY - headY) * k
        mouthCy += (target.mouthCy - mouthCy) * k
        mouthAmp += (target.mouthAmp - mouthAmp) * k
        mouthWidth += (target.mouthWidth - mouthWidth) * k
        mouthWobble += (target.mouthWobble - mouthWobble) * k
    }

    /** Same open→closing→open cadence for every mood; [forceShut] (BRAIN_OFF only) overrides it to
     *  stay nearly closed instead. */
    private fun updateBlink(dtMs: Float, forceShut: Boolean): Float {
        if (forceShut) { inBlink = false; return 0.05f }
        if (!inBlink) {
            sinceLastBlink += dtMs
            if (sinceLastBlink >= blinkThresholdMs) { inBlink = true; blinkT = 0f }
            return 1f
        }
        blinkT += dtMs
        val blinkDurMs = 140f
        if (blinkT >= blinkDurMs) {
            inBlink = false
            sinceLastBlink = 0f
            blinkThresholdMs = 2600f + Random.nextFloat() * 2200f
            return 1f
        }
        return abs(cos((blinkT / blinkDurMs) * PI)).toFloat()
    }

    fun draw(scope: DrawScope, cx: Float, cy: Float) = with(scope) {
        val r = min(size.width, size.height) / 2.3f
        val base = Color(col[0] / 255f, col[1] / 255f, col[2] / 255f)

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(base.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(cx, cy), radius = r * 2.1f,
            ),
            size = size,
        )

        val leftX = cx + headX - spacing / 2 + gazeX
        val rightX = cx + headX + spacing / 2 + gazeX
        val eyeY = cy + headY + gazeY
        drawEyeShape(leftX, eyeY, eyeR, openL * blinkOpen, base)
        drawEyeShape(rightX, eyeY, eyeR, openR * blinkOpen, base)
        drawMouthShape(cx + headX, cy + headY + mouthCy, mouthAmp, mouthWidth, mouthWobble)

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    FaceBackground.copy(alpha = 0.5f),
                    FaceBackground.copy(alpha = 0.16f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy), radius = r * 1.1f,
            ),
            size = size,
        )
    }

    /** Admin/onboarding's version — the same eased, category-tinted glow as [draw], just without
     *  the eyes/mouth: a breathing wash of color behind the content instead of a face sitting on
     *  top of it. */
    fun drawGlowOnly(scope: DrawScope, cx: Float, cy: Float) = with(scope) {
        val r = min(size.width, size.height) / 2.3f
        val base = Color(col[0] / 255f, col[1] / 255f, col[2] / 255f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(base.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(cx, cy), radius = r * 2.6f,
            ),
            size = size,
        )
    }
}

/** A solid glow-cored dot: soft radial halo, crisp ink circle on top, squashed vertically by
 *  [openY] for blink/squint. */
private fun DrawScope.drawEyeShape(x: Float, y: Float, r: Float, openY: Float, glow: Color) {
    val h = openY.coerceAtLeast(0.06f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glow.copy(alpha = 0.5f), glow.copy(alpha = 0f)),
            center = Offset(x, y), radius = r * 2.4f,
        ),
        radius = r * 2.4f,
        center = Offset(x, y),
    )
    scale(scaleX = 1f, scaleY = h, pivot = Offset(x, y)) {
        drawCircle(color = FaceInk, radius = r, center = Offset(x, y))
    }
}

/** A filled lens shape (top curve + a shallower bottom curve for fullness) — reads as a soft, full
 *  mouth rather than a stroked line. */
private fun DrawScope.drawMouthShape(cx: Float, mouthCy: Float, amp: Float, width: Float, wobble: Float) {
    val half = width / 2f
    val fullness = width * 0.16f
    val path = Path().apply {
        moveTo(cx - half, mouthCy - wobble)
        quadraticBezierTo(cx, mouthCy + amp, cx + half, mouthCy + wobble)
        quadraticBezierTo(cx, mouthCy + amp + fullness, cx - half, mouthCy - wobble)
        close()
    }
    drawPath(path, color = FaceInk)
}

/** Mirrors [AgentFace]'s own per-state intent (energy, period) as a face instead of a formation.
 *  Vertical/narrow by construction (see [FaceTarget]) — eyes well above center, mouth well below,
 *  narrow spacing, independent of the surrounding screen's own aspect ratio. */
private fun agentStateTarget(state: AgentState, t: Double, rd: Double): FaceTarget {
    val eyeUp = -rd * 0.34
    return when (state) {
        AgentState.IDLE -> {
            val breathe = sin(t * 3.0)
            FaceTarget(
                eyeR = (rd * (0.095 + breathe * 0.01)).toFloat(),
                spacing = (rd * 0.9).toFloat(),
                gazeX = 0f,
                gazeY = (eyeUp + sin(t * 1.5) * rd * 0.008).toFloat(),
                openL = 1f, openR = 1f,
                headX = (sin(t * 0.9) * rd * 0.015).toFloat(),
                headY = (sin(t * 1.5 + 1.4) * rd * 0.012).toFloat(),
                mouthCy = (rd * 0.95).toFloat(),
                mouthAmp = (rd * (0.045 + breathe * 0.008)).toFloat(),
                mouthWidth = (rd * 0.5).toFloat(),
                mouthWobble = 0f,
                rgb = IDLE_RGB,
            )
        }
        AgentState.LISTENING -> {
            val pulse = abs(sin(t * 1.1)).pow(6.0)
            val scan = sin(t * 1.8) * rd * 0.05
            FaceTarget(
                eyeR = (rd * (0.105 + pulse * 0.015)).toFloat(),
                spacing = (rd * (0.9 + pulse * 0.02)).toFloat(),
                gazeX = scan.toFloat(),
                gazeY = (eyeUp - pulse * rd * 0.01).toFloat(),
                openL = (1.1 + pulse * 0.1).toFloat(), openR = (1.1 + pulse * 0.1).toFloat(),
                headX = (scan * 0.3).toFloat(),
                headY = (-pulse * rd * 0.01).toFloat(),
                mouthCy = (rd * 0.9).toFloat(),
                mouthAmp = (rd * 0.012).toFloat(),
                mouthWidth = (rd * 0.4).toFloat(),
                mouthWobble = 0f,
                rgb = LISTEN_RGB,
            )
        }
        AgentState.THINKING -> {
            val dart = sin(t * 2.1) * rd * 0.06 + sin(t * 5.7) * rd * 0.02
            val squint = (sin(t * 1.5) + 1) / 2
            FaceTarget(
                eyeR = (rd * 0.085).toFloat(),
                spacing = (rd * 0.9).toFloat(),
                gazeX = dart.toFloat(),
                gazeY = (eyeUp - rd * 0.02).toFloat(),
                openL = (0.85 - squint * 0.4).toFloat(),
                openR = (0.55 + squint * 0.35).toFloat(),
                headX = (dart * 0.15).toFloat(),
                headY = (-rd * 0.02).toFloat(),
                mouthCy = (rd * 0.86).toFloat(),
                mouthAmp = (-rd * 0.012).toFloat(),
                mouthWidth = (rd * 0.28).toFloat(),
                mouthWobble = (sin(t * 7.2) * rd * 0.035).toFloat(),
                rgb = THINK_RGB,
            )
        }
        AgentState.SPEAKING -> {
            val talk = abs(sin(t * 7.5)).pow(2.0) * 0.7 + abs(sin(t * 11.3)).pow(2.0) * 0.5
            val brow = abs(sin(t * 3.1)).pow(4.0)
            FaceTarget(
                eyeR = (rd * (0.095 + brow * 0.01)).toFloat(),
                spacing = (rd * 0.9).toFloat(),
                gazeX = 0f,
                gazeY = (eyeUp - brow * rd * 0.012).toFloat(),
                openL = (1 + brow * 0.2).toFloat(), openR = (1 + brow * 0.2).toFloat(),
                headX = 0f, headY = (-brow * rd * 0.015).toFloat(),
                mouthCy = (rd * 1.0).toFloat(),
                mouthAmp = (rd * (0.05 + talk * 0.28)).toFloat(),
                mouthWidth = (rd * (0.45 + talk * 0.18)).toFloat(),
                mouthWobble = ((talk - 0.5) * rd * 0.05).toFloat(),
                rgb = SPEAK_RGB,
            )
        }
        AgentState.BRAIN_OFF -> FaceTarget(
            eyeR = (rd * 0.095).toFloat(),
            spacing = (rd * 0.9).toFloat(),
            gazeX = 0f,
            gazeY = (-rd * 0.30 + sin(t * 0.45) * rd * 0.008).toFloat(),
            openL = 1f, openR = 1f, // forceShut carries the closed-lid look, not these
            headX = 0f, headY = (rd * 0.02).toFloat(),
            mouthCy = (rd * 0.86).toFloat(),
            mouthAmp = (-rd * 0.03).toFloat(),
            mouthWidth = (rd * 0.3).toFloat(),
            mouthWobble = 0f,
            rgb = BRAIN_OFF_RGB,
            forceShut = true,
        )
    }
}

/** Admin/onboarding's ambient mood per [OnboardingCategory] — the narrative steps (INTRO through
 *  DONE) ride the same cool→warm ramp as [OnboardingParticles] ([OnboardingCategory.progress]);
 *  the Admin-only sections (MEMORY/VOICE/API/TRAINER) get their own fixed tint and ignore it,
 *  exactly like the particle version does. */
private fun categoryTarget(category: OnboardingCategory, t: Double, rd: Double, memberCount: Int, activeSlot: Int): FaceTarget {
    val eyeUp = -rd * 0.34
    fun ramp(): FloatArray {
        val p = category.progress
        val cool = floatArrayOf(90f, 130f, 170f)
        val warm = floatArrayOf(230f, 120f, 70f)
        return floatArrayOf(cool[0] + (warm[0] - cool[0]) * p, cool[1] + (warm[1] - cool[1]) * p, cool[2] + (warm[2] - cool[2]) * p)
    }
    return when (category) {
        OnboardingCategory.INTRO -> FaceTarget(
            eyeR = (rd * 0.08).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = (sin(t * 0.4) * rd * 0.05).toFloat(), gazeY = (eyeUp + sin(t * 0.3) * rd * 0.02).toFloat(),
            openL = 0.8f, openR = 0.8f,
            headX = (sin(t * 0.25) * rd * 0.01).toFloat(), headY = (sin(t * 0.3 + 0.7) * rd * 0.01).toFloat(),
            mouthCy = (rd * 0.85).toFloat(), mouthAmp = 0f, mouthWidth = (rd * 0.28).toFloat(), mouthWobble = 0f,
            rgb = ramp(),
        )
        OnboardingCategory.HOUSEHOLD -> {
            val slots = memberCount.coerceAtLeast(1)
            val ang = if (activeSlot in 0 until slots) (activeSlot.toDouble() / slots) * TAU + t * 0.05 else t * 0.3
            FaceTarget(
                eyeR = (rd * 0.10).toFloat(), spacing = (rd * 0.9).toFloat(),
                gazeX = (cos(ang) * rd * 0.06).toFloat(), gazeY = (eyeUp + sin(ang) * rd * 0.02).toFloat(),
                openL = 1f, openR = 1f,
                headX = 0f, headY = 0f,
                mouthCy = (rd * 0.9).toFloat(), mouthAmp = (rd * 0.06).toFloat(), mouthWidth = (rd * 0.5).toFloat(), mouthWobble = 0f,
                rgb = ramp(),
            )
        }
        OnboardingCategory.LANGUAGES -> FaceTarget(
            eyeR = (rd * 0.095).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = (sin(t * 1.2) * rd * 0.08).toFloat(), gazeY = eyeUp.toFloat(),
            openL = 1f, openR = 1f,
            headX = 0f, headY = 0f,
            mouthCy = (rd * 0.88).toFloat(), mouthAmp = (rd * 0.02).toFloat(), mouthWidth = (rd * 0.32).toFloat(), mouthWobble = 0f,
            rgb = ramp(),
        )
        OnboardingCategory.HOME -> FaceTarget(
            eyeR = (rd * 0.10).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = 0f, gazeY = eyeUp.toFloat(),
            openL = 0.85f, openR = 0.85f,
            headX = (sin(t * 0.4) * rd * 0.012).toFloat(), headY = (sin(t * 0.5 + 1.0) * rd * 0.012).toFloat(),
            mouthCy = (rd * 0.9).toFloat(), mouthAmp = (rd * 0.08).toFloat(), mouthWidth = (rd * 0.55).toFloat(), mouthWobble = 0f,
            rgb = ramp(),
        )
        OnboardingCategory.DONE -> FaceTarget(
            eyeR = (rd * 0.10).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = 0f, gazeY = (eyeUp + rd * 0.03).toFloat(),
            openL = 0.75f, openR = 0.75f,
            headX = 0f, headY = (sin(t * 0.2) * rd * 0.01).toFloat(),
            mouthCy = (rd * 0.88).toFloat(), mouthAmp = (rd * 0.05).toFloat(), mouthWidth = (rd * 0.45).toFloat(), mouthWobble = 0f,
            rgb = ramp(),
        )
        OnboardingCategory.MEMORY -> FaceTarget(
            eyeR = (rd * 0.095).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = (cos(t * 0.3) * rd * 0.05).toFloat(), gazeY = (eyeUp + sin(t * 0.3) * rd * 0.03).toFloat(),
            openL = 0.65f, openR = 0.65f,
            headX = 0f, headY = 0f,
            mouthCy = (rd * 0.86).toFloat(), mouthAmp = (rd * 0.03).toFloat(), mouthWidth = (rd * 0.35).toFloat(), mouthWobble = 0f,
            rgb = THINK_RGB,
        )
        OnboardingCategory.VOICE -> {
            val talk = abs(sin(t * 7.5)).pow(2.0) * 0.7 + abs(sin(t * 11.3)).pow(2.0) * 0.5
            FaceTarget(
                eyeR = (rd * 0.10).toFloat(), spacing = (rd * 0.9).toFloat(),
                gazeX = 0f, gazeY = eyeUp.toFloat(),
                openL = 1.05f, openR = 1.05f,
                headX = 0f, headY = 0f,
                mouthCy = (rd * 1.0).toFloat(),
                mouthAmp = (rd * (0.05 + talk * 0.28)).toFloat(),
                mouthWidth = (rd * (0.4 + talk * 0.18)).toFloat(),
                mouthWobble = ((talk - 0.5) * rd * 0.05).toFloat(),
                rgb = SPEAK_RGB,
            )
        }
        OnboardingCategory.API -> FaceTarget(
            eyeR = (rd * 0.085).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = 0f, gazeY = eyeUp.toFloat(),
            openL = 0.6f, openR = 0.6f,
            headX = 0f, headY = 0f,
            mouthCy = (rd * 0.86).toFloat(), mouthAmp = (-rd * 0.01).toFloat(), mouthWidth = (rd * 0.25).toFloat(), mouthWobble = 0f,
            rgb = GUARDED_RGB,
        )
        OnboardingCategory.TRAINER -> FaceTarget(
            eyeR = (rd * 0.10).toFloat(), spacing = (rd * 0.9).toFloat(),
            gazeX = 0f, gazeY = eyeUp.toFloat(),
            openL = 1.15f, openR = 1.15f,
            headX = 0f, headY = 0f,
            mouthCy = (rd * 0.86).toFloat(), mouthAmp = (rd * 0.01).toFloat(), mouthWidth = (rd * 0.3).toFloat(), mouthWobble = 0f,
            rgb = GUARDED_RGB,
        )
    }
}
