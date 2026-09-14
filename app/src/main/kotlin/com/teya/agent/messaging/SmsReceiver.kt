package com.teya.agent.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.teya.agent.harness.HarnessService

/**
 * Inbound SMS: concatenates the PDU parts of one message and hands it to [HarnessService].
 * `SMS_RECEIVED` + `RECEIVE_SMS` needs no default-SMS role, unlike `SMS_DELIVER`.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress?.trim().orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (sender.isBlank() || body.isBlank()) return
        Log.d(TAG, "Inbound SMS: ${messages.size} part(s), ${body.length} chars")

        val forward = Intent(context, HarnessService::class.java).apply {
            action = HarnessService.ACTION_SMS_RECEIVED
            putExtra(HarnessService.EXTRA_SMS_SENDER, sender)
            putExtra(HarnessService.EXTRA_SMS_BODY, body)
        }
        // Android 12+ refuses a background FGS start; legal only while the harness is already foreground.
        runCatching { ContextCompat.startForegroundService(context, forward) }
            .onFailure { Log.w(TAG, "Could not hand the message to the harness: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
