package com.teya.agent.ui.face

import androidx.compose.foundation.Canvas
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
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Onboarding **and Admin** category — each is a genuinely different kind of motion (several are
 *  AgentFace's own state formulas, reused directly rather than approximated). [progress] (0..1)
 *  drives the cool→ember warmth ramp for the onboarding narrative; [energy] mirrors AgentFace's
 *  per-state energy constant. MEMORY/VOICE/API are Admin-only — they set their own fixed tint
 *  (see `target()`) and ignore [progress], since Admin's sections aren't a sequence.
 *
 *  This is the SAME field Admin uses (`SettingsActivity`), not a lookalike copy — a formation
 *  means the same thing everywhere: rings = a location being pinpointed (onboarding's HOME step,
 *  Admin's Home location section), orbiting clusters = people (onboarding's HOUSEHOLD step,
 *  Admin's Household section), the globe = languages (both). */
enum class OnboardingCategory(val progress: Float, val energy: Float) {
    INTRO(0f, 0.3f), HOUSEHOLD(0f, 0.5f), LANGUAGES(0.33f, 0.9f), HOME(0.66f, 0.75f), DONE(1f, 0.95f),
    MEMORY(1f, 0.6f), VOICE(1f, 1.0f), API(0f, 0.3f),
}

private const val N = 760
private val TAU = (PI * 2)

/** A fixed continent (angular center + radius on the unit sphere) — a handful of these, not
 *  per-point noise, is what makes the globe read as coherent continents instead of speckle. */
private data class Continent(val phi: Double, val theta: Double, val r: Double)
private val CONTINENTS = listOf(
    Continent(1.05, 0.3, 0.62), Continent(1.75, 1.9, 0.5), Continent(0.85, 3.5, 0.48),
    Continent(2.35, 4.7, 0.55), Continent(1.4, 5.6, 0.42), Continent(2.6, 2.6, 0.4),
)
private fun isLand(phi: Double, theta: Double): Boolean = CONTINENTS.any { c ->
    val cosD = cos(phi) * cos(c.phi) + sin(phi) * sin(c.phi) * cos(theta - c.theta)
    acos(cosD.coerceIn(-1.0, 1.0)) < c.r
}

private val OCEAN = Triple(58, 108, 168)
private val LAND = Triple(110, 140, 80)

/** Same field-of-points idea as [AgentFace] — same density/glow/vignette treatment, several
 *  states reusing AgentFace's own formulas directly — driven by onboarding category instead of
 *  voice state. [memberCount] (Household only) grows the cluster ring by one per person added;
 *  [activeSlot] (Household only, -1 = none) brightens and enlarges one cluster — Admin uses this
 *  to show which person is currently in focus in its person-pager. */
@Composable
fun OnboardingParticles(
    category: OnboardingCategory,
    memberCount: Int,
    modifier: Modifier = Modifier,
    activeSlot: Int = -1,
) {
    val field = remember { OnboardingField() }
    val currentCategory by rememberUpdatedState(category)
    val currentMemberCount by rememberUpdatedState(memberCount)
    val currentActiveSlot by rememberUpdatedState(activeSlot)
    var frameNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(modifier) {
        val now = frameNanos
        val dt = field.consumeDt(now)
        field.step(size.width, size.height, dt, currentCategory, currentMemberCount, currentActiveSlot)
        field.draw(this)
    }
}

/** Where a household member's own cluster (slot [slot] of [totalSlots]) sits on screen right now,
 *  in pixel coordinates for a [w]x[h] canvas — mirrors the HOUSEHOLD branch's cluster-center math
 *  in [OnboardingField.target] exactly, so a caller (Admin) can lay an invisible tap target over
 *  the actual moving cluster instead of a fixed spot. [tSeconds] only needs to track elapsed time
 *  since composition — it doesn't have to be the same clock instance as the particle field's own
 *  (both accumulate identically from real frame time, so in practice they stay in sync). */
fun householdClusterPixelOffset(slot: Int, totalSlots: Int, tSeconds: Double, w: Float, h: Float): Offset {
    val slots = totalSlots.coerceAtLeast(1)
    val clusterR = if (slots == 1) 0.0 else 0.55
    val clusterAng = (slot.toDouble() / slots) * TAU + tSeconds * 0.05
    val cx = w / 2f
    val cy = h / 2f
    val rd = min(w, h) / 3.4f
    val ox = (cos(clusterAng) * clusterR).toFloat() * rd
    val oy = (sin(clusterAng) * clusterR * 0.9).toFloat() * rd
    return Offset(cx + ox, cy + oy)
}

private class OnboardingField {
    private val px = FloatArray(N)
    private val py = FloatArray(N)
    private val ps = FloatArray(N)
    private val pa = FloatArray(N)
    private val pcr = FloatArray(N)
    private val pcg = FloatArray(N)
    private val pcb = FloatArray(N)
    private val col = floatArrayOf(90f, 130f, 170f) // shared eased color for non-globe categories
    private var lastNanos = 0L
    private var clock = 0f
    private val rnd = Random(7)
    private val ra = FloatArray(N) { rnd.nextFloat() }
    private val rb = FloatArray(N) { rnd.nextFloat() }
    private val rc = FloatArray(N) { rnd.nextFloat() }
    private var seeded = false

    private var tX = 0f; private var tY = 0f; private var tS = 0f; private var tA = 0f
    private var tR = 0f; private var tG = 0f; private var tB = 0f; private var tHasColor = false

    fun consumeDt(now: Long): Float {
        val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1_000_000f).coerceIn(0f, 100f)
        lastNanos = now
        return dt
    }

    fun step(w: Float, h: Float, dtMs: Float, category: OnboardingCategory, memberCount: Int, activeSlot: Int = -1) {
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val rd = min(w, h) / 3.4f

        if (!seeded) { for (i in 0 until N) { px[i] = cx; py[i] = cy }; seeded = true }
        clock += (dtMs / 1000f) * 0.55f // overall slower, calmer motion — matches the prototype

        val target = warmthRgb(category.progress)
        val ck = (dtMs / 500f).coerceAtMost(1f)
        for (c in 0..2) col[c] += (target[c] - col[c]) * ck

        val k = (dtMs / 220f).coerceAtMost(1f)
        for (i in 0 until N) {
            target(i, category, memberCount, clock.toDouble(), activeSlot)
            px[i] += (cx + tX * rd - px[i]) * k
            py[i] += (cy + tY * rd - py[i]) * k
            ps[i] += (tS * rd - ps[i]) * k
            pa[i] += (tA - pa[i]) * k
            if (tHasColor) {
                pcr[i] = tR; pcg[i] = tG; pcb[i] = tB
            } else {
                pcr[i] = col[0]; pcg[i] = col[1]; pcb[i] = col[2]
            }
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        if (!seeded) return@with
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rd = min(size.width, size.height) / 3.4f
        val base = Color(col[0] / 255f, col[1] / 255f, col[2] / 255f)

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(base.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(cx, cy), radius = rd * 2.3f,
            ),
            size = size,
        )

        for (i in 0 until N) {
            val a = pa[i]
            if (a < 0.01f) continue
            drawCircle(
                color = Color(pcr[i] / 255f, pcg[i] / 255f, pcb[i] / 255f, a.coerceIn(0f, 1f)),
                radius = ps[i].coerceAtLeast(0.5f),
                center = Offset(px[i], py[i]),
                blendMode = BlendMode.Plus,
            )
        }

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    FaceBackground.copy(alpha = 0.4f), FaceBackground.copy(alpha = 0.1f), Color.Transparent,
                ),
                center = Offset(cx, cy), radius = rd * 1.3f,
            ),
            size = size,
        )
    }

    private fun warmthRgb(progress: Float): FloatArray {
        val cool = floatArrayOf(90f, 130f, 170f)
        val warm = floatArrayOf(230f, 120f, 70f)
        return floatArrayOf(
            cool[0] + (warm[0] - cool[0]) * progress,
            cool[1] + (warm[1] - cool[1]) * progress,
            cool[2] + (warm[2] - cool[2]) * progress,
        )
    }

    /** Compute the target position/size/alpha (center-relative, unit-scaled by rd) for point [i]. */
    private fun target(i: Int, category: OnboardingCategory, memberCount: Int, t: Double, activeSlot: Int = -1) {
        tHasColor = false
        val e = category.energy.toDouble()
        when (category) {
            OnboardingCategory.INTRO -> {
                // quiet, unformed — dots just fly around freely, no center, no orbit, no ring,
                // no clustering. Each wanders its own independent path.
                val x = sin(t * 0.09 + ra[i] * 23) * 0.5 + sin(t * 0.05 + rb[i] * 17) * 0.32
                val y = cos(t * 0.08 + rb[i] * 19) * 0.5 + cos(t * 0.045 + rc[i] * 13) * 0.32
                tX = x.toFloat(); tY = (y * 0.95).toFloat()
                tS = (0.0095 * (0.6 + 0.5 * rb[i])).toFloat()
                tA = (0.3 + 0.45 * rb[i]).toFloat()
            }
            OnboardingCategory.HOUSEHOLD -> {
                // one small orbiting cluster PER household member — adding a person literally
                // duplicates another cluster onto the ring, driven by real app state, not time.
                val slots = memberCount.coerceAtLeast(1)
                val slot = i % slots
                val idxInSlot = i / slots
                val clusterR = if (slots == 1) 0.0 else 0.55
                val clusterAng = (slot.toDouble() / slots) * TAU + t * 0.05
                val ccx = cos(clusterAng) * clusterR
                val ccy = sin(clusterAng) * clusterR * 0.9
                val orbitR = 0.1 + 0.16 * ra[i]
                val ang = rb[i] * TAU + t * (0.3 + 0.5 * rb[i]) * (if (rc[i] < 0.5f) 1 else -1)
                val wob = 1 + 0.08 * sin(t * TAU * 0.6 + idxInSlot * 0.9)
                tX = (ccx + cos(ang) * orbitR * wob).toFloat()
                tY = (ccy + sin(ang) * orbitR * wob).toFloat()
                tS = (0.009 * (1.15 - orbitR * 2.5)).toFloat()
                tA = (0.28 + 0.55 * (1 - orbitR / 0.26)).toFloat()
                // Admin's person-pager: brighten+enlarge whichever cluster is in focus, dim the rest —
                // the field itself shows who's being edited instead of just sitting behind the UI.
                // Only when there's more than one person: with a single member the cluster sits dead
                // center (clusterR=0), right behind the name field, so emphasizing it just adds a
                // bright ring over the text with nothing to actually distinguish it from.
                if (slots > 1 && activeSlot in 0 until slots) {
                    if (slot == activeSlot) {
                        tS *= 1.55f; tA = (tA * 1.9f).coerceAtMost(1f)
                        tHasColor = true; tR = 232f; tG = 98f; tB = 42f
                    } else {
                        tS *= 0.6f; tA *= 0.35f
                    }
                }
            }
            OnboardingCategory.LANGUAGES -> {
                // a rotating sphere — world languages. Colored by a continent/ocean mask fixed to
                // (phi, theta) so land rotates WITH the globe's surface, not the shared warmth hue.
                val golden = PI * (1 + sqrt(5.0))
                val phi = acos(1 - 2 * (i + 0.5) / N)
                val theta = golden * i + t * 0.3
                val x3 = sin(phi) * cos(theta); val y3 = cos(phi); val z3 = sin(phi) * sin(theta)
                val r = 0.88
                val depth = (z3 + 1) / 2
                val thetaMod = ((theta % TAU) + TAU) % TAU
                val land = isLand(phi, thetaMod)
                val (cr, cg, cb) = if (land) LAND else OCEAN
                tX = (x3 * r).toFloat(); tY = (y3 * r).toFloat()
                tS = (0.0105 * (0.45 + 0.65 * depth)).toFloat()
                tA = (0.16 + 0.82 * depth).toFloat()
                tHasColor = true; tR = cr.toFloat(); tG = cg.toFloat(); tB = cb.toFloat()
            }
            OnboardingCategory.HOME -> {
                // AgentFace's LISTENING rings, drawn inward — a location being pinpointed
                val rings = 6
                val ppr = (N / rings).coerceAtLeast(1)
                val ring = i / ppr
                val idx = i % ppr
                val ang = (idx.toDouble() / ppr) * TAU
                var u = (ring.toDouble() / rings) - t
                u -= floor(u)
                val trem = 1 + 0.05 * e * sin(ang * 6 + t * TAU * 3)
                val rad = (0.16 + 0.80 * u) * trem
                tX = (cos(ang) * rad).toFloat(); tY = (sin(ang) * rad).toFloat()
                tS = 0.0072f
                tA = ((0.12 + 0.6 * sin(PI * u)) * (0.5 + 0.5 * e)).toFloat()
            }
            OnboardingCategory.DONE -> {
                // AgentFace's THINKING swirl — orbiting, everything settling into place
                val rr = 0.18 + 0.78 * ra[i]
                val speed = 0.4 + rb[i] * 1.3
                val dir = if (rc[i] < 0.5f) 1 else -1
                val ang = ra[i] * TAU + t * speed * dir
                val ell = 0.72 + 0.28 * rb[i]
                val wob = 1 + 0.10 * sin(t * TAU * 2 + i * 0.7)
                tX = (cos(ang) * rr * wob).toFloat(); tY = (sin(ang) * rr * ell * wob).toFloat()
                tS = 0.0075f
                tA = (0.2 + 0.5 * e).toFloat()
            }
            OnboardingCategory.MEMORY -> {
                // AgentFace's THINKING swirl, tinted violet — memory consolidation reuses the same
                // "processing" motion as active thinking.
                val rr = 0.18 + 0.78 * ra[i]
                val speed = 0.4 + rb[i] * 1.3
                val dir = if (rc[i] < 0.5f) 1 else -1
                val ang = ra[i] * TAU + t * speed * dir
                val ell = 0.72 + 0.28 * rb[i]
                val wob = 1 + 0.10 * sin(t * TAU * 2 + i * 0.7)
                tX = (cos(ang) * rr * wob).toFloat(); tY = (sin(ang) * rr * ell * wob).toFloat()
                tS = 0.0075f
                tA = (0.2 + 0.5 * e).toFloat()
                tHasColor = true; tR = 169f; tG = 139f; tB = 255f
            }
            OnboardingCategory.VOICE -> {
                // AgentFace's SPEAKING waveform ribbon, tinted amber — barge-in/wake tuning sits
                // directly over the voice's own shape.
                val lines = 6
                val cols = (N / lines).coerceAtLeast(1)
                val line = i % lines
                val cIdx = i / lines
                val span = 2.0
                // N isn't guaranteed divisible by `lines` (unlike AgentFace's own N=828), so the last
                // column can push d slightly past ±1 — clamp it, or cos(d·π/2) goes negative and
                // .pow(1.2) of a negative number is NaN (crashes Color() downstream).
                val d = (cIdx.toDouble() / (cols - 1).coerceAtLeast(1) * 2 - 1).coerceIn(-1.0, 1.0)
                val env = cos(d * PI / 2).pow(1.2)
                val syl = 0.35 + 0.65 * abs(sin(t * TAU * 1.7 + d * 2.2))
                val amp = 0.44 * e * env * syl
                val y0 = sin(d * 8 * PI - t * TAU * 2) * amp
                val mid = (lines - 1) / 2.0
                val band = 0.05 + abs(y0) * 0.7
                val off = (line - mid) / lines * band * 2
                tX = (d * span).toFloat(); tY = (y0 + off).toFloat()
                tS = 0.0085f
                tA = ((0.14 + 0.55 * env * e) * (1 - abs(line - mid) / mid * 0.45)).toFloat()
                tHasColor = true; tR = 255f; tG = 190f; tB = 75f
            }
            OnboardingCategory.API -> {
                // a small, quiet orbit — one guarded value, not a formation of many
                val rr = 0.1 + 0.12 * ra[i]
                val ang = rb[i] * TAU + t * (0.15 + 0.2 * rb[i]) * (if (rc[i] < 0.5f) 1 else -1)
                tX = (cos(ang) * rr).toFloat(); tY = (sin(ang) * rr * 0.9).toFloat()
                tS = 0.006f
                tA = (0.15 + 0.3 * ra[i]).toFloat()
                tHasColor = true; tR = 120f; tG = 140f; tB = 150f
            }
        }
    }
}
