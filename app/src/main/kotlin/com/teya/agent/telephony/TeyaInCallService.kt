package com.teya.agent.telephony

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class TeyaInCallService : InCallService() {
    companion object {
        private const val TAG = "TeyaInCallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")
        
        // As a default dialer/InCallService, we can manage the call
        // For v1, we automatically route to speakerphone and show the "Face" UI
        
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                Log.d(TAG, "onStateChanged: $state")
            }
        })
        
        // Auto-answer logic or routing can go here
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
    }
}
