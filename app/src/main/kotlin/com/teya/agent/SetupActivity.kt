package com.teya.agent

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.teya.agent.harness.ConfigManager
import com.teya.agent.household.Eyebrow
import com.teya.agent.household.Heading
import com.teya.agent.household.HomeConfirmCard
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.LanguagePicker
import com.teya.agent.household.LocationProbe
import com.teya.agent.household.Member
import com.teya.agent.household.MemberEditor
import com.teya.agent.household.Note
import com.teya.agent.household.PrimaryButton
import com.teya.agent.household.SecondaryButton
import com.teya.agent.household.SubText
import com.teya.agent.household.TeyaColors
import com.teya.agent.household.TeyaField
import kotlinx.coroutines.launch

/**
 * First-run onboarding: a guided 4-step form (API key → household → languages → home). Portrait
 * and handheld — done before the phone is mounted. STT isn't reliable before language is set, so
 * this is a form, not a conversation. On finish it saves the household profile and launches the
 * (landscape, wall-mounted) MainActivity. Runs again only until an API key exists.
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
            )
        )

        setContent {
            OnboardingWizard(
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

@Composable
fun OnboardingWizard(
    city: String,
    coords: String,
    onComplete: (OnboardingData) -> Unit,
) {
    val total = 4
    var step by remember { mutableStateOf(1) }
    var apiKey by remember { mutableStateOf("") }
    val members = remember { mutableStateListOf(Member()) }
    val languages = remember { mutableStateListOf("English") }
    var homeConfirmed by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(TeyaColors.Page)) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
                    .padding(top = 24.dp, bottom = 12.dp)
            ) {
                StepDots(step, total)
                Spacer(Modifier.height(22.dp))
                when (step) {
                    1 -> StepApiKey(apiKey) { apiKey = it }
                    2 -> StepHousehold(members) { new -> members.clear(); members.addAll(new) }
                    3 -> StepLanguages(languages) { lang ->
                        if (lang in languages) languages.remove(lang) else languages.add(lang)
                    }
                    else -> StepHome(city, coords, homeConfirmed) { homeConfirmed = it }
                }
            }

            NavBar(
                step = step,
                total = total,
                nextEnabled = step != 1 || apiKey.isNotBlank(),
                onBack = { if (step > 1) step-- },
                onNext = {
                    if (step < total) step++
                    else onComplete(OnboardingData(apiKey, members.toList(), languages.toList(), homeConfirmed))
                },
            )
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (n in 1..total) {
            val color = when {
                n == step -> TeyaColors.Accent
                n < step -> TeyaColors.Accent.copy(alpha = 0.45f)
                else -> TeyaColors.Edge
            }
            Box(Modifier.width(22.dp).height(4.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun StepApiKey(apiKey: String, onChange: (String) -> Unit) {
    Eyebrow("Welcome")
    Spacer(Modifier.height(6.dp))
    Heading("Let’s set up Teya")
    Spacer(Modifier.height(6.dp))
    SubText("A few quick things so Teya knows your household. Takes a minute.")
    Spacer(Modifier.height(22.dp))
    TeyaField(apiKey, onChange, "sk-…", label = "Mistral API key", isPassword = true)
    Spacer(Modifier.height(14.dp))
    Note("Stored encrypted on the device. You can change it later in Admin.")
}

@Composable
private fun StepHousehold(members: List<Member>, onChange: (List<Member>) -> Unit) {
    Eyebrow("Step 2 · Household")
    Spacer(Modifier.height(6.dp))
    Heading("Who lives here?")
    Spacer(Modifier.height(6.dp))
    SubText("Add each person and what the family calls them — Teya listens for those words.")
    Spacer(Modifier.height(22.dp))
    MemberEditor(members, onChange)
    Spacer(Modifier.height(14.dp))
    Note(
        "Names are exact. Nicknames like “Dad” can point to more than one person, so Teya will ask " +
            "which one — until she can tell voices apart and just know who’s asking."
    )
}

@Composable
private fun StepLanguages(languages: List<String>, onToggle: (String) -> Unit) {
    Eyebrow("Step 3 · Language")
    Spacer(Modifier.height(6.dp))
    Heading("What do you speak?")
    Spacer(Modifier.height(6.dp))
    SubText("Pick every language spoken at home so Teya understands everyone.")
    Spacer(Modifier.height(22.dp))
    LanguagePicker(selected = languages.toSet(), onToggle = onToggle)
}

@Composable
private fun StepHome(city: String, coords: String, confirmed: Boolean, onChange: (Boolean) -> Unit) {
    Eyebrow("Step 4 · Home")
    Spacer(Modifier.height(6.dp))
    Heading("Is this home?")
    Spacer(Modifier.height(6.dp))
    SubText("Confirm where “home” is.")
    Spacer(Modifier.height(22.dp))
    HomeConfirmCard(
        locationCity = city,
        coords = coords,
        confirmed = confirmed,
        onConfirmedChange = onChange,
    )
}

@Composable
private fun NavBar(step: Int, total: Int, nextEnabled: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 14.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (step > 1) {
            SecondaryButton("Back", Modifier.weight(1f), onBack)
        }
        PrimaryButton(
            text = if (step == total) "Start Teya" else "Next",
            enabled = nextEnabled,
            modifier = Modifier.weight(1f),
            onClick = onNext,
        )
    }
}
