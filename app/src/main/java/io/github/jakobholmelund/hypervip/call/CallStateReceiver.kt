package io.github.jakobholmelund.hypervip.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.EventLog
import kotlin.concurrent.thread

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val app = context.applicationContext
        CallTracker.update(app, state)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Android sends one copy of this broadcast without the number, and, to apps with
                // call log access, a second copy with it.
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (number.isNullOrBlank()) {
                    if (!hasCallLogAccess(app)) {
                        EventLog.log(app, "Incoming call, but Android hid the number — allow call log access in Hyper VIP")
                    }
                    return
                }
                val pending = goAsync()
                thread(name = "hyper-vip-incoming-call") {
                    try {
                        VipAlerts.onIncomingPhoneCall(app, number)
                    } catch (e: Exception) {
                        EventLog.log(app, "Error while handling a call: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                VipAlerts.stopAlert(app, "call answered", onlySource = VipAlerts.Source.PHONE)
            TelephonyManager.EXTRA_STATE_IDLE -> {
                VipAlerts.stopAlert(app, "call ended", onlySource = VipAlerts.Source.PHONE)
                AudioState.cleanup(app)
            }
        }
    }

    private fun hasCallLogAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
}
