package com.teya.agent

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.teya.agent.harness.ConfigManager
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.Languages
import com.teya.agent.household.LocationProbe
import com.teya.agent.household.Member
import com.teya.agent.household.TeyaColors
import com.teya.agent.household.toAliasList
import com.teya.agent.ui.face.OnboardingCategory
import com.teya.agent.ui.face.OnboardingParticles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-run onboarding: Teya asks, one question per screen, chat-style — not a form. STT isn't
 * reliable before the language is set, so answers are typed/tapped, not spoken (chicken/egg).
 * Portrait and handheld — done before the phone is mounted. On finish it saves the household
 * profile and launches the (landscape, wall-mounted) MainActivity. Runs again only until an API
 * key exists. Shape locked via an interactive prototype (see docs/roadmap.md, 2026-07-10 feedback).
 */
class SetupActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var household: HouseholdManager

    private val detectedCity = mutableStateOf("Detecting location…")
    private val detectedCoords = mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { detectLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configManager = ConfigManager(this)
        household = HouseholdManager(this)

        if (configManager.isConfigured()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Location (for the "is this home?" step) + contacts (to seed household members).
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.GET_ACCOUNTS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
        )

        setContent {
            AgenticOnboardingWizard(
                city = detectedCity.value,
                coords = detectedCoords.value,
                onComplete = ::finishOnboarding,
            )
        }
    }

    private fun finishOnboarding(data: OnboardingData) {
        configManager.mistralApiKey = data.apiKey.trim()
        configManager.languages = data.languages
        configManager.homeConfirmed = data.homeConfirmed
        lifecycleScope.launch {
            household.saveHousehold(data.members)   // seed Contacts + alias augmentation
            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun detectLocation() {
        lifecycleScope.launch {
            val home = LocationProbe.detect(this@SetupActivity)
            detectedCity.value = home.city
            detectedCoords.value = home.coords
        }
    }
}

data class OnboardingData(
    val apiKey: String,
    val members: List<Member>,
    val languages: List<String>,
    val homeConfirmed: Boolean,
)

// ---- the chat state machine ----

private enum class Step {
    WELCOME, API_KEY, HOUSEHOLD_INTRO, MEMBER_FIRST, MEMBER_LAST, MEMBER_ALIASES,
    MEMBER_PHONE, MEMBER_EMAIL, MEMBER_BIRTHDAY, MEMBER_MORE, LANGUAGES, HOME, DONE,
}

private sealed class Composer {
    data class TextInput(
        val placeholder: String,
        val optional: Boolean,
        val isPassword: Boolean = false,
        val keyboardType: KeyboardType = KeyboardType.Text,
        val onSubmit: (String) -> Unit,
    ) : Composer()

    data class YesNo(val yes: String, val no: String, val onSubmit: (Boolean) -> Unit) : Composer()

    data class LanguagePick(val initial: List<String>, val onSubmit: (List<String>) -> Unit) : Composer()

    data class Finish(val onStart: () -> Unit) : Composer()

    object None : Composer()
}

@Composable
fun AgenticOnboardingWizard(city: String, coords: String, onComplete: (OnboardingData) -> Unit) {
    var currentQuestion by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    val members = remember { mutableStateListOf<Member>() }
    var currentMember by remember { mutableStateOf(Member()) }
    val languages = remember { mutableStateListOf("English") }
    var homeConfirmed by remember { mutableStateOf(true) }
    var step by remember { mutableStateOf(Step.WELCOME) }
    var stepToken by remember { mutableStateOf(0) }
    var composer by remember { mutableStateOf<Composer>(Composer.None) }
    var memberCount by remember { mutableStateOf(1) }

    fun goTo(next: Step) {
        step = next
        stepToken++
        composer = Composer.None
    }

    // Each question replaces the last — no growing history. It fades/drifts out and the next
    // fades/drifts in, like moving from one star to the next rather than scrolling a transcript.
    LaunchedEffect(stepToken) {
        delay(260)
        // one cluster per person already added, plus the one currently being entered (grows the
        // moment a new name-question starts, not only once that person is fully saved) — memberMore
        // (between people) and household intro must NOT count a not-yet-begun next person.
        memberCount = when (step) {
            Step.MEMBER_FIRST, Step.MEMBER_LAST, Step.MEMBER_ALIASES,
            Step.MEMBER_PHONE, Step.MEMBER_EMAIL, Step.MEMBER_BIRTHDAY -> members.size + 1
            else -> maxOf(1, members.size)
        }
        when (step) {
            Step.WELCOME -> {
                currentQuestion = "Hi — I'm Teya. Before I go on the wall, I need to know your " +
                    "household: who lives here, what you speak, and where home is. I'll ask one " +
                    "thing at a time — won't take long."
                delay(1400)
                goTo(Step.API_KEY)
            }
            Step.API_KEY -> {
                currentQuestion = "First, a Mistral API key so I can think and speak — you can " +
                    "change this later in Admin."
                composer = Composer.TextInput(placeholder = "sk-…", optional = false, isPassword = true) { v ->
                    apiKey = v
                    goTo(Step.HOUSEHOLD_INTRO)
                }
            }
            Step.HOUSEHOLD_INTRO -> {
                phase = 1
                currentQuestion = "Now, who lives here? I'll go person by person — name, what the " +
                    "family calls them, and how to reach them."
                delay(1300)
                currentMember = Member()
                goTo(Step.MEMBER_FIRST)
            }
            Step.MEMBER_FIRST -> {
                currentQuestion = if (members.isEmpty())
                    "What's their first name?" else "And the next person — first name?"
                composer = Composer.TextInput(placeholder = "First name", optional = false) { v ->
                    currentMember = currentMember.copy(first = v)
                    goTo(Step.MEMBER_LAST)
                }
            }
            Step.MEMBER_LAST -> {
                currentQuestion = "Last name?"
                composer = Composer.TextInput(placeholder = "Last name", optional = true) { v ->
                    currentMember = currentMember.copy(last = v)
                    goTo(Step.MEMBER_ALIASES)
                }
            }
            Step.MEMBER_ALIASES -> {
                currentQuestion = "What does the family call " +
                    "${currentMember.first.ifBlank { "them" }}? Nicknames like “Dad” or " +
                    "“Gran” — I listen for those too. Comma-separate a few."
                composer = Composer.TextInput(placeholder = "e.g. Dad, Papa", optional = true) { v ->
                    currentMember = currentMember.copy(aliases = v.toAliasList())
                    goTo(Step.MEMBER_PHONE)
                }
            }
            Step.MEMBER_PHONE -> {
                currentQuestion = "Phone number? So I can place a call when asked."
                composer = Composer.TextInput(
                    placeholder = "+1 555 0100", optional = true, keyboardType = KeyboardType.Phone,
                ) { v ->
                    currentMember = currentMember.copy(phone = v)
                    goTo(Step.MEMBER_EMAIL)
                }
            }
            Step.MEMBER_EMAIL -> {
                currentQuestion = "Email? Only needed for calendar invites later — skip if you'd " +
                    "rather not."
                composer = Composer.TextInput(
                    placeholder = "name@example.com", optional = true, keyboardType = KeyboardType.Email,
                ) { v ->
                    currentMember = currentMember.copy(email = v)
                    goTo(Step.MEMBER_BIRTHDAY)
                }
            }
            Step.MEMBER_BIRTHDAY -> {
                currentQuestion = "Birthday? I'll remember it. (YYYY-MM-DD, or skip.)"
                composer = Composer.TextInput(placeholder = "2015-04-02", optional = true) { v ->
                    currentMember = currentMember.copy(birthday = v)
                    members.add(currentMember)
                    goTo(Step.MEMBER_MORE)
                }
            }
            Step.MEMBER_MORE -> {
                val added = members.last()
                val calledStr = if (added.aliases.isNotEmpty())
                    " (called ${added.aliases.joinToString(", ")})" else ""
                currentQuestion = "Got it — ${added.first.ifBlank { "that person" }} is on the " +
                    "list$calledStr. Anyone else in the household?"
                composer = Composer.YesNo(yes = "Someone else", no = "That's everyone") { more ->
                    if (more) {
                        currentMember = Member()
                        goTo(Step.MEMBER_FIRST)
                    } else {
                        goTo(Step.LANGUAGES)
                    }
                }
            }
            Step.LANGUAGES -> {
                phase = 2
                currentQuestion = "What languages does the household speak? Pick every one — I'll " +
                    "answer in whichever language you speak to me in, matched from this list."
                composer = Composer.LanguagePick(initial = languages.toList()) { langs ->
                    languages.clear(); languages.addAll(langs)
                    goTo(Step.HOME)
                }
            }
            Step.HOME -> {
                phase = 3
                currentQuestion = "Last thing — I'm picking up a location near $city. Is this home?"
                composer = Composer.YesNo(yes = "Yes, that's home", no = "No, that's not right") { ok ->
                    homeConfirmed = ok
                    goTo(Step.DONE)
                }
            }
            Step.DONE -> {
                phase = 4
                val memberWord = if (members.size == 1) "member" else "members"
                val langWord = if (languages.size == 1) "language" else "languages"
                currentQuestion = "All set! ${members.size} household $memberWord, " +
                    "${languages.size} $langWord, home ${if (homeConfirmed) "confirmed" else "not confirmed"}. " +
                    "Starting Teya now."
                composer = Composer.Finish {
                    onComplete(OnboardingData(apiKey, members.toList(), languages.toList(), homeConfirmed))
                }
            }
        }
    }

    val particleCategory = when (phase) {
        0 -> OnboardingCategory.INTRO
        1 -> OnboardingCategory.HOUSEHOLD
        2 -> OnboardingCategory.LANGUAGES
        3 -> OnboardingCategory.HOME
        else -> OnboardingCategory.DONE
    }
    // imePadding on the OUTER box, not just the content column — the canvas needs to shrink and
    // recenter with the keyboard too, or the formation stays positioned against the full screen
    // height and ends up rendering behind/below the keyboard instead of in the visible area above it.
    Box(Modifier.fillMaxSize().background(TeyaColors.Page).systemBarsPadding().imePadding()) {
        OnboardingParticles(particleCategory, memberCount, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            PhaseDots(phase, Modifier.padding(horizontal = 22.dp, vertical = 18.dp))

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = stepToken,
                    transitionSpec = {
                        (fadeIn(tween(520)) + scaleIn(initialScale = 0.9f, animationSpec = tween(520))) togetherWith
                            (fadeOut(tween(340)) + scaleOut(targetScale = 1.08f, animationSpec = tween(340)))
                    },
                    label = "question",
                    // fixed bounds — without this, AnimatedContent resizes its own container to each
                    // question's different line count and re-centers mid-fade, reading as a jiggle.
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        currentQuestion,
                        color = TeyaColors.Ink,
                        fontSize = 19.sp,
                        lineHeight = 27.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            ComposerArea(composer, Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp, top = 8.dp))
        }
    }
}

@Composable
private fun PhaseDots(phase: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val shown = phase.coerceAtMost(3)
        for (n in 0..3) {
            val done = phase >= 4 || n < shown
            val active = n == shown && phase < 4
            val color = when {
                active -> TeyaColors.Accent
                done -> TeyaColors.Accent.copy(alpha = 0.5f)
                else -> TeyaColors.Edge
            }
            if (active) {
                val transition = rememberInfiniteTransition(label = "phaseDotPulse")
                val alpha by transition.animateFloat(
                    initialValue = 1f, targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                    label = "alpha",
                )
                Box(Modifier.size(7.dp).alpha(alpha).background(color, CircleShape))
            } else {
                Box(Modifier.size(7.dp).background(color, CircleShape))
            }
        }
    }
}

/** The tap target for Continue/Yes/No/Start — plain text, no box or border, no glow of its own;
 *  it reads as part of the same minimal, particle-driven surface as everything else on screen. */
@Composable
private fun StarLabel(text: String, modifier: Modifier = Modifier, muted: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (muted) TeyaColors.Muted else TeyaColors.Ink,
            fontSize = 14.sp,
            fontWeight = if (muted) FontWeight.Medium else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(if (enabled) 1f else 0.35f),
        )
    }
}

/** Borderless text field — no underline, no box, just typed text over the particle field. */
@Composable
private fun BorderlessField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (value.isEmpty()) {
            Text(placeholder, color = TeyaColors.Muted2, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TeyaColors.Ink, fontSize = 18.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(TeyaColors.Accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComposerArea(composer: Composer, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        when (composer) {
            is Composer.TextInput -> {
                var value by remember(composer) { mutableStateOf("") }
                BorderlessField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = composer.placeholder,
                    isPassword = composer.isPassword,
                    keyboardType = composer.keyboardType,
                )
                Spacer(Modifier.height(4.dp))
                StarLabel(
                    "Continue",
                    enabled = composer.optional || value.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { composer.onSubmit(value.trim()) },
                )
                if (composer.optional) {
                    StarLabel("Skip this one", muted = true, modifier = Modifier.fillMaxWidth()) { composer.onSubmit("") }
                }
            }

            is Composer.YesNo -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StarLabel(composer.no, muted = true, modifier = Modifier.weight(1f)) { composer.onSubmit(false) }
                    StarLabel(composer.yes, modifier = Modifier.weight(1f)) { composer.onSubmit(true) }
                }
            }

            is Composer.LanguagePick -> {
                var selected by remember(composer) { mutableStateOf(composer.initial.toSet()) }
                LanguageChips(
                    selected = selected,
                    onToggle = { lang -> selected = if (lang in selected) selected - lang else selected + lang },
                )
                Spacer(Modifier.height(6.dp))
                StarLabel(
                    "Continue",
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { composer.onSubmit(selected.toList()) },
                )
            }

            is Composer.Finish -> StarLabel("Start Teya", modifier = Modifier.fillMaxWidth(), onClick = composer.onStart)

            Composer.None -> Unit
        }
    }
}

/** Flag emoji per [Languages.ALL] entry — presentation-only, the name/voiced set stays in [Languages]. */
private val LANGUAGE_FLAGS = mapOf(
    "English" to "🇬🇧", "Spanish" to "🇪🇸", "French" to "🇫🇷", "German" to "🇩🇪", "Portuguese" to "🇵🇹",
    "Italian" to "🇮🇹", "Dutch" to "🇳🇱", "Hindi" to "🇮🇳", "Arabic" to "🇸🇦", "Russian" to "🇷🇺",
    "Chinese" to "🇨🇳", "Japanese" to "🇯🇵", "Korean" to "🇰🇷",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LanguageChips(selected: Set<String>, onToggle: (String) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Languages.ALL.forEach { lang ->
                val on = lang in selected
                val voiced = Languages.isVoiced(lang)
                Row(
                    Modifier
                        .clickable { onToggle(lang) }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(LANGUAGE_FLAGS[lang].orEmpty(), fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        lang,
                        color = if (on) TeyaColors.Ink else if (voiced) TeyaColors.Muted else TeyaColors.Muted2,
                        fontSize = 13.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (voiced) "🔊" else "👂", fontSize = 10.sp)
                }
            }
        }
        if (Languages.ALL.any { !Languages.isVoiced(it) }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "🔊 speaks it  ·  👂 understands, replies in another language",
                color = TeyaColors.Muted2,
                fontSize = 10.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
