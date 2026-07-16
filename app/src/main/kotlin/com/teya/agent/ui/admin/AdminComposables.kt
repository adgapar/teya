package com.teya.agent.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import com.teya.agent.household.Languages
import com.teya.agent.household.Member
import com.teya.agent.household.MemoryEntry
import com.teya.agent.household.Note
import com.teya.agent.household.TeyaColors
import com.teya.agent.ui.face.AgentState
import com.teya.agent.ui.face.AgentVisualization
import com.teya.agent.ui.face.AgentVisualizations
import com.teya.agent.ui.face.OnboardingCategory
import com.teya.agent.ui.face.householdClusterPixelOffset
import kotlin.math.PI
import kotlin.math.cos

/** An Admin section — each maps to its own particle formation (see [OnboardingCategory]), so
 *  moving between sections morphs the SAME field the face and onboarding use, rather than
 *  swapping to a different background. */
enum class AdminSection(val label: String, val navLabel: String, val category: OnboardingCategory) {
    HOUSEHOLD("Household", "FAMILY", OnboardingCategory.HOUSEHOLD),
    MEMORY("Memory", "MEMORY", OnboardingCategory.MEMORY),
    LANGUAGES("Languages", "LANG", OnboardingCategory.LANGUAGES),
    HOME("Home", "HOME", OnboardingCategory.HOME),
    VOICE("Voice", "VOICE", OnboardingCategory.VOICE),
    SETTINGS("Settings", "SETTINGS", OnboardingCategory.API),
    // Temporary — remove alongside WakeWordSamplePanel.kt once wake word training is done.
    TRAINER("Wake Word", "SAMPLE", OnboardingCategory.TRAINER),
}

// ---- nav (idea 1: the nav's own icons are shaped like each section's formation) ----

@Composable
fun ConstellationNav(
    selected: AdminSection,
    portrait: Boolean,
    onSelect: (AdminSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        AdminSection.entries.forEach { section ->
            val on = section == selected
            Column(
                Modifier
                    .clickable { onSelect(section) }
                    .padding(horizontal = if (portrait) 10.dp else 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SectionGlyph(section, tint = if (on) TeyaColors.Accent else TeyaColors.Muted2, size = if (portrait) 18.dp else 16.dp)
                Spacer(Modifier.height(4.dp))
                if (!portrait) {
                    Text(
                        section.navLabel,
                        color = if (on) TeyaColors.Accent else TeyaColors.Muted2,
                        fontSize = 8.5.sp, letterSpacing = 0.8.sp, fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Box(Modifier.size(4.dp).background(if (on) TeyaColors.Accent else Color.Transparent, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun SectionGlyph(section: AdminSection, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = size.toPx()
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (section) {
            AdminSection.HOUSEHOLD -> {
                val r = s * 0.11f
                drawCircle(tint, r, center = Offset(s * 0.28f, s * 0.52f))
                drawCircle(tint, r, center = Offset(s * 0.72f, s * 0.3f))
                drawCircle(tint, r, center = Offset(s * 0.72f, s * 0.72f))
            }
            AdminSection.MEMORY -> {
                drawArc(
                    tint, startAngle = -40f, sweepAngle = 300f, useCenter = false, style = stroke,
                    topLeft = Offset(s * 0.1f, s * 0.1f), size = Size(s * 0.8f, s * 0.8f),
                )
            }
            AdminSection.LANGUAGES -> {
                val c = s / 2f
                drawCircle(tint, c * 0.82f, center = Offset(c, c), style = stroke)
                drawOval(tint, topLeft = Offset(c * 0.55f, c * 0.18f), size = Size(c * 0.9f, c * 1.64f), style = stroke)
                drawLine(tint, Offset(s * 0.06f, s * 0.36f), Offset(s * 0.94f, s * 0.36f), strokeWidth = stroke.width)
                drawLine(tint, Offset(s * 0.06f, s * 0.64f), Offset(s * 0.94f, s * 0.64f), strokeWidth = stroke.width)
            }
            AdminSection.HOME -> {
                val c = Offset(s / 2f, s / 2f)
                listOf(0.16f, 0.32f, 0.48f).forEach { f -> drawCircle(tint, s * f, center = c, style = stroke) }
            }
            AdminSection.VOICE -> {
                val h = s
                var prev = Offset(0f, h * 0.5f)
                val pts = listOf(
                    Offset(s * 0.18f, h * 0.5f), Offset(s * 0.35f, h * 0.14f), Offset(s * 0.5f, h * 0.86f),
                    Offset(s * 0.65f, h * 0.28f), Offset(s * 0.82f, h * 0.5f), Offset(s, h * 0.5f),
                )
                pts.forEach { p -> drawLine(tint, prev, p, strokeWidth = stroke.width, cap = StrokeCap.Round); prev = p }
            }
            AdminSection.SETTINGS -> {
                val c = Offset(s / 2f, s / 2f)
                drawCircle(tint, s * 0.07f, center = c)
                drawCircle(tint, s * 0.4f, center = c, style = stroke)
            }
            AdminSection.TRAINER -> {
                // A simple mic glyph: capsule body + stand.
                drawRoundRect(
                    tint, topLeft = Offset(s * 0.36f, s * 0.12f), size = Size(s * 0.28f, s * 0.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f), style = stroke,
                )
                drawArc(
                    tint, startAngle = 0f, sweepAngle = 180f, useCenter = false, style = stroke,
                    topLeft = Offset(s * 0.2f, s * 0.38f), size = Size(s * 0.6f, s * 0.42f),
                )
                drawLine(tint, Offset(s * 0.5f, s * 0.8f), Offset(s * 0.5f, s * 0.92f), strokeWidth = stroke.width)
            }
        }
    }
}

// ---- idea 2 (reworked): tap a person's own cluster to select them, inside Household itself ----

/** Invisible tap targets over every OTHER member's cluster (the active one is already selected) —
 *  positioned by the exact same math the particle field uses, so the hit area tracks the moving
 *  cluster. No button anywhere; something already on screen just responds to touch. */
@Composable
fun HouseholdTapOverlay(
    memberCount: Int,
    activeSlot: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (memberCount <= 1) return
    var seconds by remember { mutableStateOf(0.0) }
    var startNanos by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                seconds = (now - startNanos) / 1_000_000_000.0
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        for (slot in 0 until memberCount) {
            if (slot == activeSlot) continue
            val off = householdClusterPixelOffset(slot, memberCount, seconds, wPx, hPx)
            val xDp = with(density) { off.x.toDp() }
            val yDp = with(density) { off.y.toDp() }
            Box(
                Modifier
                    .offset(x = xDp - 32.dp, y = yDp - 32.dp)
                    .size(64.dp)
                    .clickable { onSelect(slot) },
            )
        }
    }
}

// ---- shared field primitives ----

@Composable
fun AdminEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TeyaColors.Muted, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace, modifier = modifier)
}

/** Borderless, underline-only field — the onboarding wizard's own language, brought into Admin. */
@Composable
fun BorderlessField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (value.isEmpty()) {
            Text(placeholder, color = TeyaColors.Muted2, fontSize = fontSize, fontWeight = fontWeight, textAlign = TextAlign.Center)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TeyaColors.Ink, fontSize = fontSize, fontWeight = fontWeight, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(TeyaColors.Accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(TeyaColors.Edge))
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    optional: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onLabelClick: (() -> Unit)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier,
        ) {
            Text(label.uppercase(), color = TeyaColors.Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
            if (optional != null) {
                Text("  · $optional", color = TeyaColors.Muted2, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            if (onLabelClick != null) {
                Text(" ⓘ", color = TeyaColors.Accent, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        BorderlessField(value, onValueChange, "", fontSize = 13.sp, keyboardType = keyboardType, isPassword = isPassword)
    }
}

@Composable
private fun TextActionButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (danger) TeyaColors.Danger.copy(alpha = 0.85f) else TeyaColors.Accent,
        fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    )
}

// ---- Household: person pager ----

@Composable
fun PersonPager(
    members: List<Member>,
    personIdx: Int,
    onPersonIdxChange: (Int) -> Unit,
    onMemberChange: (Int, Member) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    portrait: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AdminEyebrow("HOUSEHOLD")
        Spacer(Modifier.height(14.dp))

        if (members.isEmpty()) {
            Note(
                "No household members yet. Add the family so Teya knows who's who.",
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(Modifier.height(14.dp))
            TextActionButton("+ Add family member", onClick = onAdd)
            return@Column
        }

        val idx = personIdx.coerceIn(0, members.size - 1)
        val member = members[idx]

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PagerArrow(Icons.Filled.ChevronLeft, enabled = idx > 0) { onPersonIdxChange(idx - 1) }
            Text(
                "PERSON ${idx + 1} OF ${members.size}",
                color = TeyaColors.Muted2, fontSize = 9.5.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.widthIn(min = 130.dp), textAlign = TextAlign.Center,
            )
            PagerArrow(Icons.Filled.ChevronRight, enabled = idx < members.size - 1) { onPersonIdxChange(idx + 1) }
        }
        Spacer(Modifier.height(8.dp))
        BorderlessField(
            value = member.first, onValueChange = { onMemberChange(idx, member.copy(first = it)) },
            placeholder = "First name", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = 170.dp),
        )
        Spacer(Modifier.height(2.dp))
        BorderlessField(
            value = member.last, onValueChange = { onMemberChange(idx, member.copy(last = it)) },
            placeholder = "Last name (optional)", fontSize = 14.sp,
            modifier = Modifier.widthIn(min = 150.dp),
        )
        Spacer(Modifier.height(16.dp))
        AliasChips(member.aliases, onChange = { onMemberChange(idx, member.copy(aliases = it)) })
        Spacer(Modifier.height(18.dp))

        val fieldsModifier = if (portrait) Modifier.fillMaxWidth(0.85f) else Modifier.widthIn(max = 380.dp)
        if (portrait) {
            Column(fieldsModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Email", member.email, { onMemberChange(idx, member.copy(email = it)) }, optional = "optional", keyboardType = KeyboardType.Email)
                LabeledField("Phone", member.phone, { onMemberChange(idx, member.copy(phone = it)) }, optional = "optional", keyboardType = KeyboardType.Phone)
            }
        } else {
            Row(fieldsModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LabeledField("Email", member.email, { onMemberChange(idx, member.copy(email = it)) }, optional = "optional", keyboardType = KeyboardType.Email, modifier = Modifier.weight(1f))
                LabeledField("Phone", member.phone, { onMemberChange(idx, member.copy(phone = it)) }, optional = "optional", keyboardType = KeyboardType.Phone, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        AdminBirthdayField(member.birthday) { onMemberChange(idx, member.copy(birthday = it)) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            TextActionButton("Remove this person", danger = true) {
                onRemove(idx)
                onPersonIdxChange((idx - 1).coerceAtLeast(0))
            }
            TextActionButton("+ Add another") { onAdd() }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PagerArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) TeyaColors.Ink else TeyaColors.Muted2.copy(alpha = 0.4f), modifier = Modifier.size(19.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AliasChips(aliases: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember(aliases) { mutableStateOf("") }
    FlowRow(
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        aliases.forEachIndexed { i, alias ->
            Row(
                Modifier
                    .background(TeyaColors.AccentSoft, RoundedCornerShape(999.dp))
                    .border(1.dp, TeyaColors.AccentBorder, RoundedCornerShape(999.dp))
                    .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(alias, color = TeyaColors.Accent, fontSize = 12.5.sp)
                Box(
                    Modifier.padding(start = 4.dp).size(16.dp)
                        .clickable { onChange(aliases.filterIndexed { idx, _ -> idx != i }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove alias", tint = TeyaColors.Accent, modifier = Modifier.size(11.dp))
                }
            }
        }
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = TextStyle(color = TeyaColors.Ink, fontSize = 12.5.sp),
            cursorBrush = SolidColor(TeyaColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val v = draft.trim()
                if (v.isNotEmpty()) { onChange(aliases + v); draft = "" }
            }),
            modifier = Modifier.width(112.dp).padding(vertical = 6.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) Text("+ Dad, Papa…", color = TeyaColors.Muted2, fontSize = 12.5.sp)
                    inner()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminBirthdayField(value: String, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BIRTHDAY  ·  OPTIONAL", color = TeyaColors.Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        Box(contentAlignment = Alignment.Center) {
            Text(
                value.ifBlank { "Select date" },
                color = if (value.isBlank()) TeyaColors.Muted2 else TeyaColors.Ink,
                fontSize = 13.sp,
                modifier = Modifier.clickable { showPicker = true }.padding(vertical = 4.dp).widthIn(min = 100.dp),
                textAlign = TextAlign.Center,
            )
            Box(Modifier.align(Alignment.BottomCenter).width(100.dp).height(1.dp).background(TeyaColors.Edge))
        }
    }
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToUtcMillis(value))
        val colors = DatePickerDefaults.colors(
            containerColor = TeyaColors.Card, titleContentColor = TeyaColors.Muted, headlineContentColor = TeyaColors.Ink,
            weekdayContentColor = TeyaColors.Muted, subheadContentColor = TeyaColors.Muted, yearContentColor = TeyaColors.Ink,
            currentYearContentColor = TeyaColors.Accent, selectedYearContentColor = TeyaColors.AccentInk,
            selectedYearContainerColor = TeyaColors.AccentFill, dayContentColor = TeyaColors.Ink,
            selectedDayContentColor = TeyaColors.AccentInk, selectedDayContainerColor = TeyaColors.AccentFill,
            todayContentColor = TeyaColors.Accent, todayDateBorderColor = TeyaColors.Accent,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(utcMillisToIso(it)) }
                    showPicker = false
                }) { Text("OK", color = TeyaColors.Accent) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel", color = TeyaColors.Muted) } },
            colors = colors,
        ) { DatePicker(state = state, colors = colors, showModeToggle = false) }
    }
}

private fun isoToUtcMillis(iso: String): Long? = runCatching {
    java.time.LocalDate.parse(iso).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun utcMillisToIso(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

// ---- Memory: horizontal decay timeline (landscape) / vertical scrolling list (portrait) ----

private val MemoryDotColor = Color(0xFFA98BFF)

@Composable
fun MemoryPanel(
    memories: List<MemoryEntry>?,
    lastDreamText: String?,
    onRunDream: () -> Unit,
    onDelete: (Int) -> Unit,
    portrait: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AdminEyebrow("MEMORY")
        Spacer(Modifier.height(10.dp))
        if (memories == null) {
            Note("Loading memories…", modifier = Modifier.widthIn(max = 300.dp))
            return@Column
        }
        if (memories.isEmpty()) {
            Note(
                "Nothing remembered yet. Teya saves facts, preferences and routines when the family asks her to.",
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(Modifier.height(14.dp))
            TextActionButton("Dream now", onClick = onRunDream)
            return@Column
        }

        Text("${memories.size} memories", color = TeyaColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Last dream: ${lastDreamText ?: "not run yet"}",
            color = TeyaColors.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        var selected by remember { mutableStateOf<MemoryEntry?>(null) }
        if (portrait) {
            MemoryList(memories, onSelect = { selected = it }, onDelete = onDelete, modifier = Modifier.heightIn(max = 200.dp).width(280.dp))
        } else {
            MemoryTimeline(memories, onSelect = { selected = it }, modifier = Modifier.width(320.dp).height(46.dp))
        }
        Spacer(Modifier.height(10.dp))
        selected?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(it.text, color = TeyaColors.Ink, fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 240.dp))
                TextActionButton("Forget", danger = true) { onDelete(it.id); selected = null }
            }
        }
        Spacer(Modifier.height(14.dp))
        TextActionButton("Dream now", onClick = onRunDream)
    }
}

@Composable
private fun MemoryTimeline(memories: List<MemoryEntry>, onSelect: (MemoryEntry) -> Unit, modifier: Modifier = Modifier) {
    val minAt = memories.minOf { it.addedAt }
    val maxAt = memories.maxOf { it.addedAt }
    val span = (maxAt - minAt).coerceAtLeast(1L)
    BoxWithConstraints(modifier) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(1.dp).background(TeyaColors.Edge))
        memories.forEach { m ->
            val frac = ((m.addedAt - minAt).toFloat() / span).coerceIn(0f, 1f)
            val alpha = (0.25f + 0.75f * m.strength).coerceIn(0.15f, 1f)
            val dotSize = (5 + m.strength * 7).dp
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * frac - dotSize / 2)
                    .size(dotSize)
                    .background(MemoryDotColor.copy(alpha = alpha), CircleShape)
                    .clickable { onSelect(m) },
            )
        }
    }
}

@Composable
private fun MemoryList(memories: List<MemoryEntry>, onSelect: (MemoryEntry) -> Unit, onDelete: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sorted = remember(memories) { memories.sortedByDescending { it.addedAt } }
    LazyColumn(modifier) {
        items(sorted, key = { it.id }) { m ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onSelect(m) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val alpha = (0.25f + 0.75f * m.strength).coerceIn(0.15f, 1f)
                Box(Modifier.size(8.dp).background(MemoryDotColor.copy(alpha = alpha), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(m.text, color = TeyaColors.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
                Text(
                    relativeAdded(m.addedAt), color = TeyaColors.Muted2, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 6.dp),
                )
                Box(
                    Modifier.size(20.dp).padding(start = 6.dp).clickable { onDelete(m.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Forget", tint = TeyaColors.Danger, modifier = Modifier.size(12.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TeyaColors.Edge.copy(alpha = 0.5f)))
        }
    }
}

private fun relativeAdded(millis: Long): String {
    if (millis <= 0L) return "—"
    val days = (System.currentTimeMillis() - millis) / 86_400_000L
    return when {
        days <= 0L -> "today"
        days == 1L -> "1d"
        else -> "${days}d"
    }
}

// ---- Languages ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagesPanel(selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AdminEyebrow("LANGUAGES")
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Languages.ALL.forEach { lang ->
                val on = lang in selected
                val voiced = Languages.isVoiced(lang)
                Row(
                    Modifier
                        .background(if (on) TeyaColors.AccentFill else Color.Transparent, RoundedCornerShape(999.dp))
                        .border(1.dp, if (on) TeyaColors.AccentFill else TeyaColors.Edge, RoundedCornerShape(999.dp))
                        .clickable { onToggle(lang) }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang, color = if (on) TeyaColors.AccentInk else TeyaColors.Muted, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    if (voiced) Text("  🔊", fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "🔊 speaks it  ·  👂 understands, replies in a language she can speak",
            color = TeyaColors.Muted2, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

// ---- Home ----

@Composable
fun HomePanel(city: String, coords: String, confirmed: Boolean, onConfirmedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AdminEyebrow("HOME LOCATION")
        Spacer(Modifier.height(12.dp))
        Text("DETECTED FROM GPS", color = TeyaColors.Muted2, fontSize = 9.5.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Text(city, color = TeyaColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(coords, color = TeyaColors.Muted2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeTogglePill("This is home", confirmed) { onConfirmedChange(true) }
            HomeTogglePill("Not home", !confirmed) { onConfirmedChange(false) }
        }
        Spacer(Modifier.height(16.dp))
        Note(
            "Teya reads your location live from the device — confirming just tells her which place is home.",
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

@Composable
private fun HomeTogglePill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) TeyaColors.AccentFill else Color.Transparent, RoundedCornerShape(999.dp))
            .border(1.dp, if (on) TeyaColors.AccentFill else TeyaColors.Edge, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = if (on) TeyaColors.AccentInk else TeyaColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Voice tuning (idea 4: tactile ribbon for the two most-tweaked knobs) ----

/** What each knob does, its default, and when to move it — tap any label (or the ribbon readout)
 *  to show it below. Keyed by the same short label used in the grid/ribbon. */
private data class TuneInfo(val title: String, val default: String, val body: String)

private val TUNE_INFO: Map<String, TuneInfo> = mapOf(
    "gain" to TuneInfo(
        "Barge-in gain", "6.0",
        "Software mic gain applied to barge-in audio before it's scored for speech — this device has no hardware AGC. Raise it if she doesn't notice being interrupted from across the room; lower it if background noise cuts her off.",
    ),
    "sens" to TuneInfo(
        "Wake sensitivity", "0.2",
        "Wake-word detection threshold (0–1) — lower fires more easily but risks false positives. Lower it if \"Hey Teya\" isn't being heard; raise it if it triggers on unrelated words or sounds.",
    ),
    "VAD thresh" to TuneInfo(
        "VAD threshold", "0.7",
        "How confident the speech model must be that a frame is speech before barge-in reacts. Lower it if barge-in misses quiet or distant speech; raise it if background noise falsely interrupts her.",
    ),
    "Speech ms" to TuneInfo(
        "Speech confirm", "50",
        "Consecutive milliseconds of detected speech required before barge-in actually fires — debounces a single noisy frame. Raise it to ignore short noises (a cough, a clatter); lower it for a snappier interrupt.",
    ),
    "Silence ms" to TuneInfo(
        "Silence reset", "300",
        "How long a trailing silence must last before barge-in resets to \"not speaking\". Raise it if it resets too eagerly during natural pauses; lower it for a quicker reset.",
    ),
    "Gap ms" to TuneInfo(
        "Barge-in fallback gap", "350",
        "Used on the fallback path — active until you grant \"draw over other apps\" in system Settings (real echo cancellation needs it), and again any time that overlay fails to start. On this path she only listens for an interrupt in the pause after each sentence, not while actually talking. Raise it to give more time to interrupt; lower it to shorten the pause between sentences.",
    ),
    "Wake gain" to TuneInfo(
        "Wake gain", "6.0",
        "Software mic gain applied before the wake-word model scores each frame. Raise it for a quiet room or a far-field mic; lower it if \"Hey Teya\" fires on unrelated sounds.",
    ),
    "Patience" to TuneInfo(
        "Wake patience", "1",
        "Consecutive frames that must stay above the wake threshold before it fires. Raise it to cut down on false positives; lower it for a faster wake.",
    ),
    "TTS boost" to TuneInfo(
        "Volume boost", "6.0",
        "Extra loudness (dB) added on top of the device's own volume when Teya speaks — separate from the volume buttons, which still work normally (turn her down or mute for quiet hours). Raise it if she's hard to hear from across the room; lower it if her voice sounds distorted or too loud.",
    ),
    // Voice ID / Voice ID (confident) — see WakeWordSamplePanel.kt's own VOICE_ID_INFO; they moved
    // there with the fields since they're meaningless without the enrollment that panel does.
)

/** Visualization picker — each option renders a small live preview via its own
 *  [AgentVisualization.Face] so you can see the design before picking it, not just read its name.
 *  Iterates [AgentVisualizations.all], so a new visualization just needs registering there to show
 *  up here automatically. */
@Composable
private fun FaceStylePicker(visualization: AgentVisualization, onChange: (AgentVisualization) -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text("FACE STYLE", color = TeyaColors.Muted2, fontSize = 9.5.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentVisualizations.all.forEach { option ->
                FaceStyleOption(option, selected = option.id == visualization.id) { onChange(option) }
            }
        }
    }
}

@Composable
private fun FaceStyleOption(visualization: AgentVisualization, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .border(1.dp, if (selected) TeyaColors.AccentFill else TeyaColors.Edge, RoundedCornerShape(14.dp))
            .background(if (selected) TeyaColors.AccentFill.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))) {
            visualization.Face(state = AgentState.IDLE)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            visualization.displayName,
            color = if (selected) TeyaColors.AccentInk else TeyaColors.Muted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** The TTS voice picker (actor pill row, then that actor's emotion variants) — see
 *  docs/mistral-voices.md / [com.teya.agent.brain.MistralVoices] for the full 30-voice catalog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePicker(voice: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val current = com.teya.agent.brain.MistralVoices.bySlug(voice)
    var actor by remember(voice) { mutableStateOf(current.actor) }
    val actors = com.teya.agent.brain.MistralVoices.ALL.map { it.actor }.distinct()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.widthIn(max = 340.dp)) {
        Text("TTS VOICE", color = TeyaColors.Muted2, fontSize = 9.5.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actors.forEach { a -> VoiceChip(a, a == actor) { actor = a } }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            com.teya.agent.brain.MistralVoices.ALL.filter { it.actor == actor }.forEach { v ->
                VoiceChip(v.emotion, v.slug == voice) { onChange(v.slug) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${current.locale} · ${current.tags}",
            color = TeyaColors.Muted2, fontSize = 10.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VoiceChip(text: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (on) TeyaColors.AccentFill else Color.Transparent, RoundedCornerShape(999.dp))
            .border(1.dp, if (on) TeyaColors.AccentFill else TeyaColors.Edge, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (on) TeyaColors.AccentInk else TeyaColors.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun VoiceTuningPanel(
    tuning: com.teya.agent.VoiceTuning,
    onChange: (com.teya.agent.VoiceTuning) -> Unit,
    portrait: Boolean,
    overlayGranted: Boolean,
    onGrantOverlay: () -> Unit,
    ttsVoice: String,
    onTtsVoiceChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var info by remember { mutableStateOf<TuneInfo?>(null) }
    val scrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AdminEyebrow("VOICE TUNING")
            Spacer(Modifier.height(6.dp))
            VoicePicker(voice = ttsVoice, onChange = onTtsVoiceChange)
            Spacer(Modifier.height(18.dp))
            if (!overlayGranted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 320.dp)) {
                    Note(
                        "Real echo-cancelled barge-in is off — \"display over other apps\" isn't " +
                            "granted, so she's on the fallback path (the Gap setting below, no echo " +
                            "cancellation, listens only between sentences).",
                    )
                    Spacer(Modifier.height(8.dp))
                    TextActionButton("Grant permission") { onGrantOverlay() }
                }
                Spacer(Modifier.height(14.dp))
            }
            Note(
                "Barge-in and wake-word sensitivity. Rebuild-free. Tap any label for what it does.",
                modifier = Modifier.widthIn(max = 320.dp),
            )
            Spacer(Modifier.height(8.dp))
            TextActionButton("Set defaults") { onChange(com.teya.agent.VoiceTuning.DEFAULTS) }
            Spacer(Modifier.height(10.dp))

            TactileVoiceRibbon(
                gain = tuning.bargeInGain.toFloatOrNull() ?: 6f,
                sensitivity = tuning.wakeWordThreshold.toFloatOrNull() ?: 0.53f,
                onChange = { gain, sens ->
                    onChange(tuning.copy(bargeInGain = "%.1f".format(gain), wakeWordThreshold = "%.2f".format(sens)))
                },
                onInfo = { key -> info = TUNE_INFO[key] },
            )
            Spacer(Modifier.height(14.dp))
            TuningGrid(
                tuning, onChange, columns = if (portrait) 2 else 4,
                onInfo = { key -> info = TUNE_INFO[key] },
                modifier = Modifier.widthIn(max = 440.dp),
            )
            info?.let { i ->
                Spacer(Modifier.height(14.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 320.dp)) {
                    Text(
                        "${i.title}  ·  default ${i.default}",
                        color = TeyaColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(i.body, color = TeyaColors.Muted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        // The panel overflows a landscape wall screen (8 knobs + explanations don't fit at once) —
        // without this, the other 6 fields below the ribbon are invisible with no hint they exist.
        ScrollMoreHint(scrollState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** A small pulsing "more below" chevron, shown only while there's unscrolled content beneath —
 *  hides once the user has scrolled to the bottom, and never appears if content already fits. */
@Composable
private fun ScrollMoreHint(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val visible = scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue - 4
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val transition = rememberInfiniteTransition(label = "scrollHint")
        val offset by transition.animateFloat(
            initialValue = 0f, targetValue = 6f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "offset",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, TeyaColors.Page.copy(alpha = 0.85f)),
                    ),
                )
                .padding(bottom = 8.dp, top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "more below", color = TeyaColors.Muted2, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                modifier = Modifier.offset(y = offset.dp),
            )
        }
    }
}

@Composable
private fun TactileVoiceRibbon(gain: Float, sensitivity: Float, onChange: (Float, Float) -> Unit, onInfo: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Text(
                "Barge-in gain ${"%.1f".format(gain)}",
                color = TeyaColors.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onInfo("gain") },
            )
            Text("   ·   ", color = TeyaColors.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(
                "Wake sensitivity ${"%.2f".format(sensitivity)}",
                color = TeyaColors.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onInfo("sens") },
            )
        }
        Spacer(Modifier.height(8.dp))
        val barCount = 24
        Box(
            Modifier
                .width(260.dp)
                .height(64.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val x = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val y = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        onChange(y * 10f, x)
                    }
                },
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val mid = (barCount - 1) / 2f
                repeat(barCount) { i ->
                    val env = cos(((i - mid) / mid) * (PI / 2)).toFloat().coerceAtLeast(0f)
                    val h = (6 + env * 50 * (gain / 10f)).dp
                    val alpha = (0.35f + sensitivity * 2.5f * env).coerceIn(0.15f, 1f)
                    Box(
                        Modifier
                            .padding(horizontal = 1.dp)
                            .width(3.dp)
                            .height(h)
                            .background(TeyaColors.Accent.copy(alpha = alpha), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "drag vertically for gain  ·  horizontally for sensitivity",
            color = TeyaColors.Muted2, fontSize = 9.sp,
        )
    }
}

@Composable
private fun TuningGrid(
    tuning: com.teya.agent.VoiceTuning,
    onChange: (com.teya.agent.VoiceTuning) -> Unit,
    columns: Int,
    onInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    data class F(val label: String, val value: String, val set: (String) -> com.teya.agent.VoiceTuning)
    val fields = listOf(
        F("VAD thresh", tuning.vadThreshold) { tuning.copy(vadThreshold = it) },
        F("Speech ms", tuning.vadSpeechDurationMs) { tuning.copy(vadSpeechDurationMs = it) },
        F("Silence ms", tuning.vadSilenceDurationMs) { tuning.copy(vadSilenceDurationMs = it) },
        F("Gap ms", tuning.bargeInGapMs) { tuning.copy(bargeInGapMs = it) },
        F("Wake gain", tuning.wakeWordInputGain) { tuning.copy(wakeWordInputGain = it) },
        F("Patience", tuning.wakeWordPatience) { tuning.copy(wakeWordPatience = it) },
        F("TTS boost", tuning.ttsVolumeBoostDb) { tuning.copy(ttsVolumeBoostDb = it) },
        // Voice ID / Voice ID (confident) live in WakeWordSamplePanel (SAMPLE tab) instead — they
        // tune the same voice-ID matching that panel records samples for, not barge-in/wake-word.
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { f ->
                    LabeledField(
                        f.label, f.value, { onChange(f.set(it)) }, keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f), onLabelClick = { onInfo(f.label) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- Settings (API key + appearance — generic, non-voice-tuning knobs) ----

@Composable
fun SettingsPanel(
    apiKey: String,
    onChange: (String) -> Unit,
    lastAuthErrorAt: Long,
    visualization: AgentVisualization,
    onVisualizationChange: (AgentVisualization) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AdminEyebrow("SETTINGS")
        Spacer(Modifier.height(16.dp))
        FaceStylePicker(visualization = visualization, onChange = onVisualizationChange)
        Spacer(Modifier.height(20.dp))
        LabeledField("Mistral API key", apiKey, onChange, isPassword = true, modifier = Modifier.width(240.dp))
        if (lastAuthErrorAt > 0L) {
            Spacer(Modifier.height(16.dp))
            Text(
                "⚠ Mistral rejected this key ${relativeAdminTime(lastAuthErrorAt)} — brain shows " +
                    "\"off\" on the face until it's fixed and she's asked again.",
                color = TeyaColors.Danger, fontSize = 11.5.sp, lineHeight = 15.sp,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp),
            )
        }
    }
}

private fun relativeAdminTime(millis: Long): String {
    val mins = (System.currentTimeMillis() - millis) / 60_000L
    return when {
        mins < 1L -> "just now"
        mins < 60L -> "${mins}m ago"
        mins < 1440L -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}
