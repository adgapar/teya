package com.teya.agent.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.teya.agent.household.HouseholdManager

/**
 * Outbound calling, over the native cellular dialer + SIM only (no VoIP — nobody is going to
 * configure an account). Outbound is the whole feature: this device is a wall appliance, not a
 * number anyone dials, so there is no inbound side and no dialer role to hold.
 *
 * The **household roster is the allowlist**. Onboarding already collects every member's real phone
 * number into native Contacts, and [HouseholdManager.resolveMember] already maps what the family
 * actually says ("Dad", "Babcia") onto a member — so "who may Teya call" needs no second store to
 * populate and keep in sync. The old `safety.ContactAllowlistManager` Room table it used to consult
 * had exactly zero writers anywhere in the app, which is why every call returned NOT_ALLOWED.
 */
class TelephonyActuator(
    private val context: Context,
    private val householdManager: HouseholdManager
) {
    /** [displayName] is the resolved member's real name, which may differ from the alias asked for. */
    sealed interface Result {
        data class Placed(val displayName: String) : Result
        object NoSim : Result
        object NoPermission : Result
        object NotAllowed : Result
        object NoNumber : Result
    }

    // getLine1Number() (the device's own number) is unreliable — carriers often leave it
    // blank even on a working SIM — so "can we call at all" is a SIM-readiness check, not
    // a number lookup.
    private fun hasWorkingSim(): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simState == TelephonyManager.SIM_STATE_READY &&
            telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun placeCall(nameOrAlias: String): Result {
        if (nameOrAlias.isBlank()) return Result.NotAllowed
        if (!hasWorkingSim()) return Result.NoSim
        // Rechecked at call time, not at startup: CALL_PHONE can be revoked from Settings while
        // this START_STICKY service keeps running, and ACTION_CALL throws SecurityException without it.
        if (!hasCallPermission()) return Result.NoPermission

        val member = householdManager.resolveMember(nameOrAlias, householdManager.members())
            ?: return Result.NotAllowed
        val number = normalizeDiallable(member.phone) ?: return Result.NoNumber

        // ACTION_CALL dials immediately through the platform dialer. FLAG_ACTIVITY_NEW_TASK is
        // required because this starts from a Service, with no activity task of its own.
        context.startActivity(
            Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return Result.Placed(member.displayName)
    }

    /**
     * Strip formatting a contact may carry (spaces, dashes, parens, dots) and accept only what is
     * actually diallable: an optional leading `+` then digits. Anything else — a wildcard, a URI
     * scheme, an empty field, a too-short string — is refused rather than handed to the dialer.
     */
    private fun normalizeDiallable(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("[\\s\\-().]"), "")
        if (!cleaned.matches(Regex("\\+?\\d{3,15}"))) return null
        return cleaned
    }
}
