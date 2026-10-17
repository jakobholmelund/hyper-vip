package io.github.jakobholmelund.hypervip.notify

import android.content.Context
import androidx.core.content.edit
import io.github.jakobholmelund.hypervip.data.EventLog

/**
 * Whether the notification listener receives "ongoing" notifications, which is what WhatsApp-style
 * incoming calls are. Android lets users (and HyperOS's defaults) hide notification types from a
 * listener, and then the call goes unseen. Checked during a test ring: Hyper VIP's own ringing
 * notification is ongoing too, so the listener should see it.
 */
object OngoingVisibility {
    private const val PREFS = "listener_check"
    private const val KEY_LAST_SEEN = "last_seen"
    private const val KEY_RESULT = "result"

    fun onOwnOngoingSeen(context: Context) {
        prefs(context).edit(commit = true) {
            putLong(KEY_LAST_SEEN, System.currentTimeMillis())
            putBoolean(KEY_RESULT, true)
        }
    }

    /** Called when a test ring that started at [startedAt] ends. */
    fun onTestRingFinished(context: Context, startedAt: Long) {
        if (!VipNotificationListener.connected) return
        val prefs = prefs(context)
        val seen = prefs.getLong(KEY_LAST_SEEN, 0) >= startedAt
        val before = result(context)
        prefs.edit(commit = true) { putBoolean(KEY_RESULT, seen) }
        if (!seen) {
            EventLog.log(context, "Notification access can't see ongoing notifications, so WhatsApp calls are missed — see Setup")
        } else if (before != true) {
            EventLog.log(context, "Notification access can see incoming app calls")
        }
    }

    /** null until a test ring has run with notification access on. */
    fun result(context: Context): Boolean? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_RESULT)) prefs.getBoolean(KEY_RESULT, false) else null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
