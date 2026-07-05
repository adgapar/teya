package com.teya.agent.ui.face

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform

enum class AgentState {
    IDLE, LISTENING, THINKING, SPEAKING
}

@Composable
fun AgentFace(state: AgentState) {
    val infiniteTransition = rememberInfiniteTransition(label = "face_animation")
    
    // Core breathing animation
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Rotation for "Thinking" state
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Dynamic colors based on state
    val baseColor by animateColorAsState(
        targetValue = when (state) {
            AgentState.IDLE -> Color(0xFF3F51B5)      // Deep Indigo
            AgentState.LISTENING -> Color(0xFF00BCD4) // Cyan
            AgentState.THINKING -> Color(0xFF9C27B0)  // Purple
            AgentState.SPEAKING -> Color(0xFF4CAF50)  // Material Green
        },
        animationSpec = tween(500),
        label = "baseColor"
    )

    val secondaryColor by animateColorAsState(
        targetValue = when (state) {
            AgentState.IDLE -> Color(0xFF1A237E)
            AgentState.LISTENING -> Color(0xFFB2EBF2)
            AgentState.THINKING -> Color(0xFFE1BEE7)
            AgentState.SPEAKING -> Color(0xFFC8E6C9)
        },
        animationSpec = tween(500),
        label = "secondaryColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)) // Dark background for contrast
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.height.coerceAtMost(size.width) / 3

            // Background Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(baseColor.copy(alpha = 0.4f), Color.Transparent),
                    center = center,
                    radius = radius * 2 * pulse
                ),
                radius = radius * 2 * pulse,
                center = center
            )

            // The Main Orb
            withTransform({
                if (state == AgentState.THINKING) {
                    rotate(rotation, center)
                }
                scale(pulse, pulse, center)
            }) {
                // Multi-layered gradient orb
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(baseColor, secondaryColor),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius)
                    ),
                    radius = radius,
                    center = center
                )

                // Highlight/Reflection
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
                        center = center - Offset(radius * 0.3f, radius * 0.3f),
                        radius = radius * 0.5f
                    ),
                    radius = radius * 0.5f,
                    center = center - Offset(radius * 0.3f, radius * 0.3f)
                )
            }

            // Interactive "Waves" for Listening/Speaking
            if (state == AgentState.LISTENING || state == AgentState.SPEAKING) {
                val waveCount = 3
                for (i in 0 until waveCount) {
                    val wavePhase = (System.currentTimeMillis() % 2000) / 2000f
                    val waveScale = 1f + (wavePhase + i / waveCount.toFloat()) % 1f
                    val waveAlpha = 1f - ((wavePhase + i / waveCount.toFloat()) % 1f)
                    
                    drawCircle(
                        color = baseColor.copy(alpha = waveAlpha * 0.3f),
                        radius = radius * waveScale,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                    )
                }
            }
        }
    }
}
