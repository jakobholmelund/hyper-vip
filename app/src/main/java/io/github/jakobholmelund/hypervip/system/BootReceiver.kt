package io.github.jakobholmelund.hypervip.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts

/** Undoes any alert that was cut short by a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext
                VipAlerts.stopAlert(app, "phone restarted")
                AudioState.releaseAll(app)
            }
        }
    }
}
