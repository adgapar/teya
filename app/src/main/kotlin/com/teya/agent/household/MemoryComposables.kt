package com.teya.agent.household

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Admin "Memory" section — **review / correct / monitor** Teya's durable memory ([MemoryEntry]).
 * - Review: memories grouped per household member (persona memory) + a "General" bucket.
 * - Correct: delete a wrong row (the manual "forget" — immediate, not batched with Save changes).
 * - Monitor: totals per tier + category, so we can watch decay + the dreamer behave (later slices).
 * See thoughts/shared/plans/2026-07-08-memory-and-dreaming.md (slice 2).
 */
@Composable
fun MemorySectionBody(
    memories: List<MemoryEntry>?,
    members: List<Member>,
    lastDreamText: String?,
    dreamLog: List<String>,
    onRunDream: () -> Unit,
    onDelete: (Int) -> Unit,
) {
    if (memories == null) { Note("Loading memories…"); return }
    if (memories.isEmpty()) {
        Note("Nothing remembered yet. Teya saves facts, preferences and routines when the family asks her to.")
        Spacer(Modifier.height(12.dp))
        DreamerControl(lastDreamText, dreamLog, onRunDream)
        return
    }

    MemorySummary(memories, lastDreamText, dreamLog, onRunDream)
    Spacer(Modifier.height(16.dp))

    // Group per member (persona memory) by lookupKey; everything else falls under "General".
    val nameByKey = members.mapNotNull { m -> m.lookupKey?.let { it to m.displayName } }.toMap()
    val groups = LinkedHashMap<String, MutableList<MemoryEntry>>()
    memories.forEach { e ->
        val name = if (e.subjectType == MemoryManager.SUBJECT_CONTACT) nameByKey[e.subjectKey] else null
        groups.getOrPut(name ?: "General") { mutableListOf() }.add(e)
    }
    groups.entries.sortedBy { if (it.key == "General") 1 else 0 }.forEach { (subject, entries) ->
        MemoryGroupHeader(subject, entries.size)
        Spacer(Modifier.height(8.dp))
        entries.forEach { e ->
            MemoryRow(e) { onDelete(e.id) }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MemorySummary(memories: List<MemoryEntry>, lastDreamText: String?, dreamLog: List<String>, onRunDream: () -> Unit) {
    val hot = memories.count { it.tier == MemoryManager.TIER_HOT }
    val cold = memories.size - hot
    val byCat = memories.groupingBy { it.category }.eachCount()
    val cats = listOf(
        MemoryManager.CAT_FACT, MemoryManager.CAT_PREFERENCE,
        MemoryManager.CAT_ROUTINE, MemoryManager.CAT_EPISODIC,
    ).mapNotNull { c -> byCat[c]?.let { "$it ${c.lowercase()}" } }.joinToString("  ·  ")
    Box(
        Modifier.fillMaxWidth()
            .background(TeyaColors.Card, RoundedCornerShape(16.dp))
            .border(1.dp, TeyaColors.Edge, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Text("${memories.size} memories", color = TeyaColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("$hot hot  ·  $cold cold", color = TeyaColors.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            if (cats.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(cats, color = TeyaColors.Muted2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(14.dp))
            DreamerControl(lastDreamText, dreamLog, onRunDream)
        }
    }
}

/** The dreamer monitor + manual trigger — last run, a rolling log of recent runs, and a "Run now" button. */
@Composable
private fun DreamerControl(lastDreamText: String?, dreamLog: List<String>, onRunDream: () -> Unit) {
    Text(
        "Dreamer — ${lastDreamText ?: "not run yet"}",
        color = TeyaColors.Muted2, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton("Run dream now", Modifier.fillMaxWidth(), onRunDream)
    if (dreamLog.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "RECENT DREAMS", color = TeyaColors.Muted, fontSize = 10.5.sp,
            letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        dreamLog.take(12).forEach { entry ->
            Text(
                "· $entry", color = TeyaColors.Muted2, fontSize = 11.sp,
                lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(3.dp))
        }
    }
}

@Composable
private fun MemoryGroupHeader(subject: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(subject, color = TeyaColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = TeyaColors.Muted2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MemoryRow(e: MemoryEntry, onDelete: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(TeyaColors.Field, RoundedCornerShape(12.dp))
            .border(1.dp, TeyaColors.Edge, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(e.text, color = TeyaColors.Ink, fontSize = 15.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(5.dp))
                Text(memoryMeta(e), color = TeyaColors.Muted2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .background(TeyaColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Delete memory", tint = TeyaColors.Danger, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** "fact · hot · 100% · 3d ago" — the row's monitor line (category, tier, strength, age). */
private fun memoryMeta(e: MemoryEntry): String {
    val pct = (e.strength.coerceIn(0f, 1f) * 100).toInt()
    return listOf(e.category.lowercase(), e.tier.lowercase(), "$pct%", relativeAdded(e.addedAt)).joinToString("  ·  ")
}

private fun relativeAdded(millis: Long): String {
    if (millis <= 0L) return "—"
    val days = (System.currentTimeMillis() - millis) / 86_400_000L
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 30L -> "${days}d ago"
        days < 365L -> "${days / 30}mo ago"
        else -> "${days / 365}y ago"
    }
}
