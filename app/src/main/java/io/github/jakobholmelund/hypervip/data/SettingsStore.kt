package io.github.jakobholmelund.hypervip.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    /** Master switch. */
    val enabled: Boolean = true,
    /** Alarm volume while a VIP alert sounds, in percent of max. Never lowers the current volume. */
    val alertVolumePercent: Int = 80,
    /** Ring for VIP calls in WhatsApp, Signal, Telegram etc. Needs notification access. */
    val appCallAlerts: Boolean = true,
    /** Play a sound for messages from VIPs. Needs notification access. */
    val messageAlerts: Boolean = false,
    /** The user says HyperOS autostart is on; Android has no API to check it. */
    val autostartConfirmed: Boolean = false,
)

object SettingsStore {
    private const val PREFS = "settings"
    private var state: MutableStateFlow<AppSettings>? = null

    fun flow(context: Context): StateFlow<AppSettings> = state(context)

    fun get(context: Context): AppSettings = state(context).value

    @Synchronized
    fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        val flow = state(context)
        val next = transform(flow.value)
        prefs(context).edit {
            putBoolean("enabled", next.enabled)
            putInt("alert_volume_percent", next.alertVolumePercent)
            putBoolean("app_call_alerts", next.appCallAlerts)
            putBoolean("message_alerts", next.messageAlerts)
            putBoolean("autostart_confirmed", next.autostartConfirmed)
        }
        flow.value = next
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<AppSettings> =
        state ?: MutableStateFlow(load(prefs(context))).also { state = it }

    private fun load(prefs: SharedPreferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            enabled = prefs.getBoolean("enabled", defaults.enabled),
            alertVolumePercent = prefs.getInt("alert_volume_percent", defaults.alertVolumePercent),
            appCallAlerts = prefs.getBoolean("app_call_alerts", defaults.appCallAlerts),
            messageAlerts = prefs.getBoolean("message_alerts", defaults.messageAlerts),
            autostartConfirmed = prefs.getBoolean("autostart_confirmed", defaults.autostartConfirmed),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
