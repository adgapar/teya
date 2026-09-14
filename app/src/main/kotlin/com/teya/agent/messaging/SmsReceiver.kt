package com.teya.agent.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.teya.agent.harness.HarnessService

/**
 * The inbound half of the text transport: a message arrives, and Teya answers it as a conversation.
 *
 * Teya deliberately is **not** the default SMS app. `SMS_RECEIVED_ACTION` + `RECEIVE_SMS` is enough
 * to observe incoming messages; only `SMS_DELIVER` needs the default-SMS role, which would mean
 * owning the family's whole messaging experience to answer a question about the shopping list. Same
 * trap the call feature avoided with `ROLE_DIALER`.
 *
 * A long message arrives as several PDUs, so parts are concatenated here (in order) before anything
 * downstream sees them — the agent must never be handed half a sentence. Everything else (is this
 * number a household member, rate limiting, the reply) belongs to [HarnessService], which owns the
 * brain and the stores; this receiver only wakes it with the text.
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
        // The number and the body are both PII (roadmap H2) — never logged, in any build.
        Log.d(TAG, "Inbound SMS: ${messages.size} part(s), ${body.length} chars")

        val forward = Intent(context, HarnessService::class.java).apply {
            action = HarnessService.ACTION_SMS_RECEIVED
            putExtra(HarnessService.EXTRA_SMS_SENDER, sender)
            putExtra(HarnessService.EXTRA_SMS_BODY, body)
        }
        // The harness normally already runs in the foreground, which is what makes this start legal
        // under Android 12's background-FGS rules. If the service was killed and the platform
        // refuses the cold start, the message is dropped rather than crashing the receiver — the
        // sender simply gets no reply, same as if the phone were off.
        runCatching { ContextCompat.startForegroundService(context, forward) }
            .onFailure { Log.w(TAG, "Could not hand the message to the harness: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
