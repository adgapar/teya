package com.teya.agent.household

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The prototype's exact dark palette. Accent (#45D0E0) matches the particle face's LISTENING hue. */
object TeyaColors {
    val Page = Color(0xFF060708)
    val Card = Color(0xFF0F1517)
    val Field = Color(0xFF0B1012)
    val Edge = Color(0x14FFFFFF)       // white 8%
    val Ink = Color(0xFFEEF3F4)
    val Muted = Color(0xFF7C8C8F)
    val Muted2 = Color(0xFF4A595C)
    val Accent = Color(0xFF45D0E0)
    val AccentInk = Color(0xFF062023)
    val Danger = Color(0xFFFF6B6B)
    val AccentSoft = Color(0x1F45D0E0) // ~12%
    val AccentBorder = Color(0x4745D0E0) // ~28%
    val NoteBg = Color(0x0F45D0E0)     // ~6%
    val NoteBorder = Color(0x2945D0E0) // ~16%
}

// ---- primitives ----

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = TeyaColors.Accent,
        fontSize = 11.sp,
        letterSpacing = 2.6.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TeyaColors.Ink,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        modifier = modifier,
    )
}

@Composable
fun SubText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = TeyaColors.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)
}

@Composable
fun FieldLabel(label: String, optional: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            color = TeyaColors.Muted,
            fontSize = 10.5.sp,
            letterSpacing = 1.4.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (optional != null) {
            Text(
                text = "  · $optional",
                color = TeyaColors.Muted2,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = TeyaColors.Muted,
        fontSize = 11.sp,
        letterSpacing = 2.2.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(TeyaColors.NoteBg, RoundedCornerShape(12.dp))
            .border(1.dp, TeyaColors.NoteBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Text(text = text, color = TeyaColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun TeyaField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    label: String? = null,
    optional: String? = null,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    Column(modifier) {
        if (label != null) {
            FieldLabel(label, optional)
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder, color = TeyaColors.Muted2, fontSize = 15.sp) },
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (isPassword)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TeyaColors.Ink,
                unfocusedTextColor = TeyaColors.Ink,
                cursorColor = TeyaColors.Accent,
                focusedBorderColor = TeyaColors.Accent,
                unfocusedBorderColor = TeyaColors.Edge,
                focusedContainerColor = TeyaColors.Field,
                unfocusedContainerColor = TeyaColors.Field,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (enabled) TeyaColors.Accent else TeyaColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TeyaColors.AccentInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .border(1.dp, TeyaColors.Edge, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TeyaColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- member editor ----

/** The list of member cards + an "Add family member" button. Shared by onboarding & Admin. */
@Composable
fun MemberEditor(
    members: List<Member>,
    onMembersChange: (List<Member>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        members.forEachIndexed { i, member ->
            androidx.compose.runtime.key(member.lookupKey ?: "new-$i") {
                MemberCard(
                    index = i,
                    member = member,
                    onChange = { updated -> onMembersChange(members.updatedAt(i) { updated }) },
                    onRemove = { onMembersChange(members.filterIndexed { idx, _ -> idx != i }) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, TeyaColors.Edge, RoundedCornerShape(14.dp))
                .clickable { onMembersChange(members + Member()) }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("+ Add family member", color = TeyaColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MemberCard(
    index: Int,
    member: Member,
    onChange: (Member) -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(TeyaColors.Card, RoundedCornerShape(16.dp))
            .border(1.dp, TeyaColors.Edge, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PERSON ${index + 1}",
                    color = TeyaColors.Muted2,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.6.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .background(TeyaColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = TeyaColors.Danger, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TeyaField(member.first, { onChange(member.copy(first = it)) }, "First",
                    label = "First name", modifier = Modifier.weight(1f))
                TeyaField(member.last, { onChange(member.copy(last = it)) }, "Last",
                    label = "Last name", optional = "optional", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("Called", "what the family says")
            Spacer(Modifier.height(6.dp))
            AliasEditor(member.aliases) { onChange(member.copy(aliases = it)) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TeyaField(member.email, { onChange(member.copy(email = it)) }, "name@…",
                    label = "Email", optional = "optional", modifier = Modifier.weight(1f))
                TeyaField(member.phone, { onChange(member.copy(phone = it)) }, "+34…",
                    label = "Phone", optional = "optional", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            BirthdayField(member.birthday) { onChange(member.copy(birthday = it)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AliasEditor(aliases: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }
    fun commit() {
        val v = draft.trim()
        if (v.isNotEmpty()) { onChange(aliases + v); draft = "" }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        aliases.forEachIndexed { i, alias ->
            AliasTag(alias) { onChange(aliases.filterIndexed { idx, _ -> idx != i }) }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text("+ Dad, Papa…", color = TeyaColors.Muted2, fontSize = 13.sp) },
            shape = RoundedCornerShape(999.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TeyaColors.Ink,
                unfocusedTextColor = TeyaColors.Ink,
                cursorColor = TeyaColors.Accent,
                focusedBorderColor = TeyaColors.Accent,
                unfocusedBorderColor = TeyaColors.Edge,
                focusedContainerColor = TeyaColors.Field,
                unfocusedContainerColor = TeyaColors.Field,
            ),
            modifier = Modifier.width(150.dp),
        )
    }
}

@Composable
private fun AliasTag(alias: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .background(TeyaColors.AccentSoft, RoundedCornerShape(999.dp))
            .border(1.dp, TeyaColors.AccentBorder, RoundedCornerShape(999.dp))
            .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(alias, color = TeyaColors.Accent, fontSize = 13.sp)
        Box(
            Modifier.padding(start = 4.dp).size(18.dp).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove alias", tint = TeyaColors.Accent, modifier = Modifier.size(13.dp))
        }
    }
}

// ---- birthday ----

/** A read-only field that opens a dark-themed date picker; stores/returns ISO "YYYY-MM-DD". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayField(value: String, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        FieldLabel("Birthday", "optional")
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(TeyaColors.Field, RoundedCornerShape(12.dp))
                .border(1.dp, TeyaColors.Edge, RoundedCornerShape(12.dp))
                .clickable { showPicker = true }
                .padding(horizontal = 13.dp, vertical = 15.dp),
        ) {
            Text(
                text = value.ifBlank { "Select date" },
                color = if (value.isBlank()) TeyaColors.Muted2 else TeyaColors.Ink,
                fontSize = 15.sp,
            )
        }
    }
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToUtcMillis(value))
        val colors = DatePickerDefaults.colors(
            containerColor = TeyaColors.Card,
            titleContentColor = TeyaColors.Muted,
            headlineContentColor = TeyaColors.Ink,
            weekdayContentColor = TeyaColors.Muted,
            subheadContentColor = TeyaColors.Muted,
            yearContentColor = TeyaColors.Ink,
            currentYearContentColor = TeyaColors.Accent,
            selectedYearContentColor = TeyaColors.AccentInk,
            selectedYearContainerColor = TeyaColors.Accent,
            dayContentColor = TeyaColors.Ink,
            selectedDayContentColor = TeyaColors.AccentInk,
            selectedDayContainerColor = TeyaColors.Accent,
            todayContentColor = TeyaColors.Accent,
            todayDateBorderColor = TeyaColors.Accent,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(utcMillisToIso(it)) }
                    showPicker = false
                }) { Text("OK", color = TeyaColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel", color = TeyaColors.Muted) }
            },
            colors = colors,
        ) {
            DatePicker(state = state, colors = colors, showModeToggle = false)
        }
    }
}

/** ISO "YYYY-MM-DD" → UTC epoch millis (the picker's clock), or null if blank/unparseable. */
private fun isoToUtcMillis(iso: String): Long? = runCatching {
    LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

// ---- language picker ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePicker(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Languages.ALL.forEach { lang ->
                LanguageChip(
                    label = lang,
                    voiced = Languages.isVoiced(lang),
                    on = lang in selected,
                    onClick = { onToggle(lang) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Note(
            "🔊 = Teya can speak it (9 languages, with voice cloning). She understands all 13 — " +
                "Russian, Chinese, Japanese & Korean are understand-only, so she replies in a language she can speak."
        )
    }
}

@Composable
private fun LanguageChip(label: String, voiced: Boolean, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (on) TeyaColors.Accent else TeyaColors.Field, RoundedCornerShape(999.dp))
            .border(1.dp, if (on) TeyaColors.Accent else TeyaColors.Edge, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (on) TeyaColors.AccentInk else TeyaColors.Muted,
            fontSize = 14.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (voiced) Text("  🔊", fontSize = 11.sp)
    }
}

// ---- home confirmation ----

@Composable
fun HomeConfirmCard(
    locationCity: String,
    coords: String,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(TeyaColors.Card, RoundedCornerShape(16.dp))
                .border(1.dp, TeyaColors.Edge, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Column {
                Text(
                    "DETECTED FROM GPS",
                    color = TeyaColors.Muted2,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.6.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Text(locationCity, color = TeyaColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(coords, color = TeyaColors.Muted2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeToggle("This is home", confirmed, Modifier.weight(1f)) { onConfirmedChange(true) }
                    HomeToggle("Not home", !confirmed, Modifier.weight(1f)) { onConfirmedChange(false) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Note(
            "Teya reads your location live from the device — no need to type it. Confirming just tells " +
                "her which place is home for “what’s the weather” and travel times."
        )
    }
}

@Composable
private fun HomeToggle(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (on) TeyaColors.Accent else TeyaColors.Field, RoundedCornerShape(12.dp))
            .border(1.dp, if (on) TeyaColors.Accent else TeyaColors.Edge, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (on) TeyaColors.AccentInk else TeyaColors.Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Return a copy of the list with element [i] transformed. */
private inline fun List<Member>.updatedAt(i: Int, transform: (Member) -> Member): List<Member> =
    mapIndexed { idx, m -> if (idx == i) transform(m) else m }
