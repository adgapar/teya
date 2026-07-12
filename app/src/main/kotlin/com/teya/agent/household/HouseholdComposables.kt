package com.teya.agent.household

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The ember palette, lifted from the app icon's own particle swirl (ic_launcher_foreground.xml).
 *  [Accent] (vivid) is for text/icon/foreground use — bright enough to read on the near-black page,
 *  same role cyan used to play. [AccentFill] (the icon's own darker ring) is only for solid-block
 *  backgrounds (buttons, selected dots) — a big block of the vivid tone reads as too loud/annoying.
 *
 *  Shared by the onboarding wizard, the particle face, and Admin — one palette, one surface. */
object TeyaColors {
    val Page = Color(0xFF060708)
    val Card = Color(0xFF0F1517)
    val Field = Color(0xFF0B1012)
    val Edge = Color(0x14FFFFFF)       // white 8%
    val Ink = Color(0xFFEEF3F4)
    val Muted = Color(0xFF7C8C8F)
    val Muted2 = Color(0xFF4A595C)
    val Accent = Color(0xFFE8622A)
    val AccentFill = Color(0xFFB95627)
    val AccentInk = Color(0xFFEEF3F4)
    val Danger = Color(0xFFFF6B6B)
    val AccentSoft = Color(0x29B95627) // ~16%
    val AccentBorder = Color(0x73B95627) // ~45%
    val NoteBg = Color(0x14B95627)     // ~8%
    val NoteBorder = Color(0x2FB95627) // ~18%
}

/** A short aside, set off with a quiet left accent stripe rather than a boxed card — matches
 *  Admin's borderless/hairline language (see docs/roadmap.md Admin redesign). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(TeyaColors.AccentBorder))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = TeyaColors.Muted,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
