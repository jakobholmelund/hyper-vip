package io.github.jakobholmelund.hypervip.call

import android.content.Context
import android.telephony.TelephonyManager
import androidx.core.content.edit

/** The latest cellular call state, as reported by PHONE_STATE broadcasts. */
object CallTracker {
    private const val PREFS = "call_state"
    private const val KEY_STATE = "state"

    fun state(context: Context): String =
        prefs(context).getString(KEY_STATE, null) ?: TelephonyManager.EXTRA_STATE_IDLE

    fun update(context: Context, state: String) {
        prefs(context).edit(commit = true) { putString(KEY_STATE, state) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
