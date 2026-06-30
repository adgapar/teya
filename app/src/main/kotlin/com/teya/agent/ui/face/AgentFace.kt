package com.teya.agent.ui.face

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class AgentState {
    IDLE, LISTENING, THINKING, SPEAKING
}

@Composable
fun AgentFace(state: AgentState) {
    val infiniteTransition = rememberInfiniteTransition(label = "face_animation")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val color = when (state) {
        AgentState.IDLE -> Color.Gray
        AgentState.LISTENING -> Color.Cyan
        AgentState.THINKING -> Color.Magenta
        AgentState.SPEAKING -> Color.Green
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = size.width.coerceAtMost(size.height) / 4
        
        // Simple geometric face representation
        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = baseRadius * pulseScale,
            center = center
        )
        
        drawCircle(
            color = color,
            radius = baseRadius * 0.8f,
            center = center
        )
        
        // Eyes
        val eyeOffset = baseRadius / 3
        drawCircle(
            color = Color.White,
            radius = baseRadius / 10,
            center = center + Offset(-eyeOffset, -eyeOffset)
        )
        drawCircle(
            color = Color.White,
            radius = baseRadius / 10,
            center = center + Offset(eyeOffset, -eyeOffset)
        )
    }
}
