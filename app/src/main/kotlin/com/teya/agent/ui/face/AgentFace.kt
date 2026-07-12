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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Broadcast by [com.teya.agent.harness.HarnessService] — names must stay in sync. BRAIN_OFF is the
 *  gated "resting" look when Mistral is rejecting requests (bad API key) — a different formation
 *  from IDLE, not just a recolor, since TTS is what's broken in that case so she can't say so. */
enum class AgentState { IDLE, LISTENING, THINKING, SPEAKING, BRAIN_OFF }

/** Background of the whole face — the points glow additively on top of this. */
val FaceBackground = Color(0xFF060708)

/** The accent hue for a state — also used by the centered transcript's role label. */
fun stateColor(state: AgentState): Color = when (state) {
    AgentState.IDLE -> Color(0xFF4A86C8)      // sea blue
    AgentState.LISTENING -> Color(0xFF45D0E0) // aqua
    AgentState.THINKING -> Color(0xFFA98BFF)  // violet
    AgentState.SPEAKING -> Color(0xFFFFBE4B)  // amber
    AgentState.BRAIN_OFF -> Color(0xFF9C5650) // dusty red — off, not resting
}

private const val N = 828          // total points
private const val COLS = 46        // sea grid columns
private const val ROWS = 18        // sea grid rows  (46*18 = 828)
private val TAU = PI * 2

/**
 * One field of points that morphs between forms as the state changes: a calm sea at rest,
 * rings drawn inward while listening, an orbiting swirl while thinking, a voice-waveform
 * ribbon while speaking. Points are the primitive; the same field reassembles each mode.
 */
@Composable
fun AgentFace(state: AgentState, modifier: Modifier = Modifier) {
    val field = remember { ParticleField() }
    val current by rememberUpdatedState(state)
    var frameNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(FaceBackground)
    ) {
        val now = frameNanos                 // read → redraw every frame
        val dt = field.consumeDt(now)
        field.step(size.width, size.height, dt, current)
        field.draw(this)
    }
}

private class ParticleField {
    private val px = FloatArray(N)
    private val py = FloatArray(N)
    private val ps = FloatArray(N)
    private val pa = FloatArray(N)
    private val ra = FloatArray(N)
    private val rb = FloatArray(N)
    private val rc = FloatArray(N)
    private val col = floatArrayOf(74f, 134f, 200f) // current rgb (0..255), eased
    private var phase = 0f
    private var energy = 0.45f
    private var seeded = false
    private var lastNanos = 0L

    // scratch target for the current point
    private var tX = 0f
    private var tY = 0f
    private var tS = 0f
    private var tA = 0f

    init {
        val rnd = Random(42)
        for (i in 0 until N) {
            ra[i] = rnd.nextFloat(); rb[i] = rnd.nextFloat(); rc[i] = rnd.nextFloat()
        }
    }

    fun consumeDt(now: Long): Float {
        val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1_000_000f).coerceIn(0f, 100f)
        lastNanos = now
        return dt
    }

    private fun periodMs(s: AgentState) = when (s) {
        AgentState.IDLE -> 7000f
        AgentState.LISTENING -> 1600f
        AgentState.THINKING -> 4200f
        AgentState.SPEAKING -> 1500f
        AgentState.BRAIN_OFF -> 9000f
    }

    private fun energyTarget(s: AgentState) = when (s) {
        AgentState.IDLE -> 0.45f
        AgentState.LISTENING -> 1.0f
        AgentState.THINKING -> 0.82f
        AgentState.SPEAKING -> 1.0f
        AgentState.BRAIN_OFF -> 0.4f
    }

    private fun palette(s: AgentState): FloatArray = when (s) {
        AgentState.IDLE -> IDLE_RGB
        AgentState.LISTENING -> LISTEN_RGB
        AgentState.THINKING -> THINK_RGB
        AgentState.SPEAKING -> SPEAK_RGB
        AgentState.BRAIN_OFF -> BRAIN_OFF_RGB
    }

    fun step(w: Float, h: Float, dtMs: Float, state: AgentState) {
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 3.4f

        if (!seeded) {
            for (i in 0 until N) { px[i] = cx; py[i] = cy; ps[i] = 0f; pa[i] = 0f }
            seeded = true
        }

        phase = (phase + dtMs / periodMs(state)) % 1f
        val pal = palette(state)
        val ck = (dtMs / 340f).coerceAtMost(1f)
        for (c in 0..2) col[c] += (pal[c] - col[c]) * ck
        energy += (energyTarget(state) - energy) * (dtMs / 460f).coerceAtMost(1f)

        val k = (dtMs / 180f).coerceAtMost(1f)
        for (i in 0 until N) {
            target(i, cx, cy, r, state)
            px[i] += (tX - px[i]) * k
            py[i] += (tY - py[i]) * k
            ps[i] += (tS - ps[i]) * k
            pa[i] += (tA - pa[i]) * k
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        if (!seeded) return@with
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 3.4f
        val base = Color(col[0] / 255f, col[1] / 255f, col[2] / 255f)

        // ambient glow behind the points
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(base.copy(alpha = 0.12f * (0.4f + 0.6f * energy)), Color.Transparent),
                center = Offset(cx, cy),
                radius = r * 2.1f,
            ),
            size = size,
        )

        // the points, additive
        val rF = col[0] / 255f; val gF = col[1] / 255f; val bF = col[2] / 255f
        for (i in 0 until N) {
            val a = pa[i]
            if (a < 0.01f) continue
            drawCircle(
                color = Color(rF, gF, bF, a.coerceIn(0f, 1f)),
                radius = ps[i].coerceAtLeast(0.5f),
                center = Offset(px[i], py[i]),
                blendMode = BlendMode.Plus,
            )
        }

        // centre vignette so the transcript stays readable over the points
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    FaceBackground.copy(alpha = 0.5f),
                    FaceBackground.copy(alpha = 0.16f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = r * 1.1f,
            ),
            size = size,
        )
    }

    /** Compute the target position/size/alpha for point [i] in the given [state]. */
    private fun target(i: Int, cx: Float, cy: Float, r: Float, state: AgentState) {
        val e = energy.toDouble()
        val t = phase.toDouble()
        val rd = r.toDouble()
        when (state) {
            AgentState.IDLE -> {
                // rolling sea: a perspective grid of points with travelling swells
                val cIdx = i % COLS
                val row = i / COLS
                val gx = cIdx.toDouble() / (COLS - 1) * 2 - 1
                val d = row.toDouble() / (ROWS - 1)                 // 0 far .. 1 near
                val persp = 0.42 + 0.58 * d
                var wave = sin(gx * 3.0 + d * 5.0 - t * TAU) * 0.10
                wave += sin(gx * 6.5 + d * 2.0 + t * TAU * 0.5) * 0.045
                wave *= rd * (0.3 + 0.7 * d) * (0.7 + 0.7 * e)
                tX = (cx + gx * rd * 1.9 * persp).toFloat()
                tY = (cy - rd * 0.32 + d * rd * 0.98 - wave).toFloat()
                tS = (rd * 0.006 * (0.55 + 0.9 * d)).toFloat()
                tA = (0.10 + 0.55 * d).toFloat()
            }
            AgentState.LISTENING -> {
                // concentric rings drawn inward — sound being taken in
                val rings = 6
                val ppr = N / rings
                val ring = i / ppr
                val idx = i % ppr
                val ang = idx.toDouble() / ppr * TAU
                var u = ring.toDouble() / rings - t
                u -= floor(u)                                       // shrinks over time → inward
                val trem = 1 + 0.05 * e * sin(ang * 6 + t * TAU * 3)
                val rad = rd * (0.16 + 0.80 * u) * trem
                tX = (cx + cos(ang) * rad).toFloat()
                tY = (cy + sin(ang) * rad).toFloat()
                tS = (rd * 0.0072).toFloat()
                tA = ((0.12 + 0.6 * sin(PI * u)) * (0.5 + 0.5 * e)).toFloat()
            }
            AgentState.THINKING -> {
                // orbiting swirl — processing
                val rr = rd * (0.18 + 0.78 * ra[i])
                val speed = 0.4 + rb[i] * 1.3
                val dir = if (rc[i] < 0.5f) 1 else -1
                val ang = ra[i] * TAU + t * TAU * speed * dir
                val ell = 0.72 + 0.28 * rb[i]
                val wob = 1 + 0.10 * sin(t * TAU * 2 + i * 0.7)
                tX = (cx + cos(ang) * rr * wob).toFloat()
                tY = (cy + sin(ang) * rr * ell * wob).toFloat()
                tS = (rd * 0.0075).toFloat()
                tA = (0.2 + 0.5 * e).toFloat()
            }
            AgentState.SPEAKING -> {
                // voice waveform drawn as banded dots, fanning at the loud peaks
                val lines = 6
                val cols = N / lines
                val line = i % lines
                val cIdx = i / lines
                val span = rd * 2.0
                val d = cIdx.toDouble() / (cols - 1) * 2 - 1
                val env = cos(d * PI / 2).pow(1.2)
                val syl = 0.35 + 0.65 * abs(sin(t * TAU * 1.7 + d * 2.2))
                val amp = rd * 0.44 * e * env * syl
                val y0 = sin(d * 8 * PI - t * TAU * 2) * amp
                val mid = (lines - 1) / 2.0
                val band = rd * 0.05 + abs(y0) * 0.7
                val off = (line - mid) / lines * band * 2
                tX = (cx + d * span).toFloat()
                tY = (cy + y0 + off).toFloat()
                tS = (rd * 0.0085).toFloat()
                tA = ((0.14 + 0.55 * env * e) * (1 - abs(line - mid) / mid * 0.45)).toFloat()
            }
            AgentState.BRAIN_OFF -> {
                // the sea, sunk and dimmed — not resting, off. Same grid positions as IDLE, but
                // collapsed low with an uneven per-point flicker instead of a travelling wave, so it
                // reads as a dead signal rather than a calm one. Brightness deliberately close to
                // IDLE's own range (not a faint whisper of it) — the shape/color carry "wrong", not
                // sheer dimness, since a too-subtle difference just reads as "nothing happened".
                val cIdx = i % COLS
                val row = i / COLS
                val gx = cIdx.toDouble() / (COLS - 1) * 2 - 1
                val d = row.toDouble() / (ROWS - 1)
                val flicker = 0.45 + 0.55 * sin(t * TAU * 0.6 + ra[i] * TAU * 4)
                tX = (cx + gx * rd * 1.5 * (0.42 + 0.58 * d)).toFloat()
                tY = (cy + rd * (0.5 + d * 0.55)).toFloat()
                tS = (rd * 0.0075 * (0.4 + 0.6 * d)).toFloat()
                tA = (0.14 + 0.42 * d * flicker).toFloat()
            }
        }
    }

    companion object {
        private val IDLE_RGB = floatArrayOf(74f, 134f, 200f)
        private val LISTEN_RGB = floatArrayOf(69f, 208f, 224f)
        private val THINK_RGB = floatArrayOf(169f, 139f, 255f)
        private val BRAIN_OFF_RGB = floatArrayOf(156f, 86f, 80f)
        private val SPEAK_RGB = floatArrayOf(255f, 190f, 75f)
    }
}
