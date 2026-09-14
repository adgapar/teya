package com.teya.agent.harness.actuators

import android.util.Log
import com.teya.agent.brain.ToolCall
import com.teya.agent.messaging.SmsSender
import com.teya.agent.telephony.TelephonyActuator

/** Tools that reach a person outside the conversation: calls and outbound texts. */
class ReachActuator(
    private val telephony: TelephonyActuator,
    private val sms: SmsSender,
) {
    suspend fun run(tool: ToolCall): String = when (tool.functionName) {
        "place_call" -> {
            val name = tool.arguments["name"] ?: ""
            Log.d(TAG, "place_call")
            when (val result = telephony.placeCall(name)) {
                is TelephonyActuator.Result.Placed -> "Calling ${result.displayName} now."
                TelephonyActuator.Result.NoSim ->
                    "There's no working phone line on this device, so the call was not placed."
                TelephonyActuator.Result.NoPermission ->
                    "I'm not allowed to place calls — permission to make phone calls is turned off in Android settings."
                TelephonyActuator.Result.NotAllowed ->
                    "$name is not someone in the household, so the call was not placed."
                TelephonyActuator.Result.NoNumber ->
                    "I don't have a usable phone number saved for $name."
            }
        }
        "send_message" -> {
            val recipient = tool.arguments["recipient"] ?: ""
            val body = tool.arguments["body"] ?: ""
            // No recipient/body in the log — the no-PII rule covers message contents too.
            Log.d(TAG, "send_message (${body.length} chars)")
            when (val result = sms.send(recipient, body)) {
                is SmsSender.Result.Sent ->
                    "Text sent to ${result.displayName}. It cannot be unsent."
                SmsSender.Result.NoSim ->
                    "There's no working phone line on this device, so the text was not sent."
                SmsSender.Result.NoPermission ->
                    "I'm not allowed to send texts — permission to send SMS is turned off in Android settings."
                SmsSender.Result.NotAllowed ->
                    "$recipient is not someone in the household, so no text was sent."
                SmsSender.Result.NoNumber ->
                    "I don't have a usable phone number saved for $recipient."
                SmsSender.Result.EmptyBody ->
                    "There was nothing to send — the message was empty."
                is SmsSender.Result.Failed ->
                    "The text to $recipient could not be sent (${result.reason})."
            }
        }
        else -> "Unknown reach tool: ${tool.functionName}"
    }

    private companion object { const val TAG = "ReachActuator" }
}
