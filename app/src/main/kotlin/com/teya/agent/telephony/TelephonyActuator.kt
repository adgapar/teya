package com.teya.agent.telephony

import android.content.Context
import android.net.Uri
import android.telecom.TelecomManager
import com.teya.agent.safety.ContactAllowlistManager

class TelephonyActuator(
    private val context: Context,
    private val allowlistManager: ContactAllowlistManager
) {
    suspend fun placeCall(name: String): Boolean {
        if (!allowlistManager.isAllowed(name)) {
            return false
        }
        
        val phoneNumber = allowlistManager.getPhoneNumber(name) ?: return false
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", phoneNumber, null)
        
        // Note: Default dialer role is required for this to work seamlessly
        telecomManager.placeCall(uri, null)
        return true
    }
}
