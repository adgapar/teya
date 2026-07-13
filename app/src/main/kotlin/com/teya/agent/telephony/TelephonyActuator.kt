package com.teya.agent.telephony

import android.content.Context
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.teya.agent.safety.ContactAllowlistManager

enum class CallResult { SUCCESS, NO_SIM, NOT_ALLOWED, NO_NUMBER }

class TelephonyActuator(
    private val context: Context,
    private val allowlistManager: ContactAllowlistManager
) {
    // getLine1Number() (the device's own number) is unreliable — carriers often leave it
    // blank even on a working SIM — so "can we call at all" is a SIM-readiness check, not
    // a number lookup.
    private fun hasWorkingSim(): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simState == TelephonyManager.SIM_STATE_READY &&
            telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    suspend fun placeCall(name: String): CallResult {
        if (!hasWorkingSim()) {
            return CallResult.NO_SIM
        }
        if (!allowlistManager.isAllowed(name)) {
            return CallResult.NOT_ALLOWED
        }

        val phoneNumber = allowlistManager.getPhoneNumber(name) ?: return CallResult.NO_NUMBER
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", phoneNumber, null)

        // Note: Default dialer role is required for this to work seamlessly
        telecomManager.placeCall(uri, null)
        return CallResult.SUCCESS
    }
}
