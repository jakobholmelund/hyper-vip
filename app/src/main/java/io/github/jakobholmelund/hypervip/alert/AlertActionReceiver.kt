package io.github.jakobholmelund.hypervip.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles "Silence", tapping and dismissing the ringing notification. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == VipAlerts.ACTION_STOP) {
            VipAlerts.stopAlert(context.applicationContext, "silenced")
        }
    }
}
