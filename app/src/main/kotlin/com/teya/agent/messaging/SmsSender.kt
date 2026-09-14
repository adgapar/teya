package com.teya.agent.messaging

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.teya.agent.BuildConfig
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.Member

/**
 * Outbound SMS — Teya's second transport, so what she knows is reachable from outside the house
 * (the shopping list is only useful in the supermarket aisle). Native SMS, not a messenger bot:
 * everyone already has a phone number and there is nothing for the family to install or configure.
 *
 * Recipients are **household members**, resolved by the name the family actually uses, exactly as
 * `telephony.TelephonyActuator` resolves who may be called — one roster, one source of numbers.
 *
 * Teya does not need to be the default SMS app: sending needs only SEND_SMS. (Same trap the call
 * feature hit with ROLE_DIALER — no role required.)
 *
 * Sending half only; inbound arrives via [SmsReceiver] and comes back here through [sendTo].
 */
class SmsSender(
    private val context: Context,
    private val householdManager: HouseholdManager
) {
    /** [displayName] is the resolved member's real name, which may differ from the alias asked for. */
    sealed interface Result {
        data class Sent(val displayName: String, val segments: Int) : Result
        object NoSim : Result
        object NoPermission : Result
        object NotAllowed : Result
        object NoNumber : Result
        object EmptyBody : Result
        data class Failed(val reason: String) : Result
    }

    fun canSend(): Boolean = hasWorkingSim() && hasSendPermission()

    fun canReceive(): Boolean = hasWorkingSim() && hasReceivePermission()

    private fun hasWorkingSim(): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simState == TelephonyManager.SIM_STATE_READY &&
            telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    private fun hasSendPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasReceivePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun send(recipientNameOrAlias: String, body: String): Result {
        if (recipientNameOrAlias.isBlank()) return Result.NotAllowed
        val member = householdManager.resolveMember(
            recipientNameOrAlias, householdManager.members()
        ) ?: return Result.NotAllowed
        return sendTo(member, body)
    }

    /** Send to an already-resolved member — the reply path, where the recipient came from their own number. */
    suspend fun sendTo(member: Member, body: String): Result {
        if (body.isBlank()) return Result.EmptyBody
        if (!hasWorkingSim()) return Result.NoSim
        // Rechecked at send time for the same reason place_call rechecks CALL_PHONE: this
        // START_STICKY service outlives a permission revoked from Settings.
        if (!hasSendPermission()) return Result.NoPermission

        val number = normalizeDiallable(member.phone) ?: return Result.NoNumber

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            // A segment is 160 GSM-7 chars, or 70 once anything forces UCS-2 — let the platform
            // decide where the cuts fall rather than guessing at the encoding.
            val parts = smsManager.divideMessage(body)
            val sent = sentIntent(parts.size)
            if (parts.size > 1) {
                // One PendingIntent per part — sendMultipartTextMessage wants a list the same
                // length as the parts, or it reports nothing at all.
                smsManager.sendMultipartTextMessage(
                    number, null, parts, sent?.let { ArrayList(List(parts.size) { _ -> it }) }, null
                )
            } else {
                smsManager.sendTextMessage(number, null, body, sent, null)
            }
            Result.Sent(member.displayName, parts.size)
        } catch (e: Exception) {
            // An SmsManager throw is the only synchronous failure; a carrier-side one arrives
            // later on the sent PendingIntent (logged, see below) and can't reach this return.
            Log.w(TAG, "SMS send failed: ${e.javaClass.simpleName}")
            Result.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * A send that the radio accepts can still fail at the carrier, minutes later and silently.
     * There is nowhere to report that to — the model has long since answered — so it is logged
     * rather than swallowed, and only in debug builds, since the log line identifies a recipient
     * (no-PII rule, roadmap H2).
     */
    private fun sentIntent(partCount: Int): PendingIntent? {
        if (!BuildConfig.DEBUG) return null
        // A per-send action, so two concurrent sends don't land on each other's receiver.
        val action = "$ACTION_SMS_SENT.${System.currentTimeMillis()}"
        var remaining = partCount
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // SmsManager reports success as Activity.RESULT_OK (-1); its errors are small positives.
                val outcome = if (resultCode == android.app.Activity.RESULT_OK) "OK" else "FAILED ($resultCode)"
                Log.d(TAG, "SMS part result: $outcome")
                if (--remaining <= 0) runCatching { ctx.unregisterReceiver(this) }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Deliberately not FLAG_ONE_SHOT: a multipart send reuses this same intent for every part.
        return PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Same rule as the call path: strip contact formatting, then optional-+ and 3–15 digits only. */
    private fun normalizeDiallable(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("[\\s\\-().]"), "")
        return if (cleaned.matches(Regex("\\+?\\d{3,15}"))) cleaned else null
    }

    companion object {
        private const val TAG = "SmsSender"
        private const val ACTION_SMS_SENT = "com.teya.agent.SMS_SENT"
    }
}
