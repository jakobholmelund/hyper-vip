package io.github.jakobholmelund.hypervip.alert

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.core.content.edit

/**
 * Makes sure a VIP alert is audible while it plays, then puts everything back.
 *
 * Alerts use the alarm stream, which silent and vibrate mode don't mute and Do Not Disturb lets
 * through by default. Two things can still keep them quiet: a low alarm volume, and a Do Not
 * Disturb setting that blocks alarms (Total silence, or alarms switched off). Both are overridden
 * while at least one holder is active. The original state is kept in SharedPreferences so it can
 * be restored even if the process dies mid-alert, and holders expire so a lost release can't keep
 * the override forever.
 */
object AudioState {
    private const val PREFS = "audio_state"
    private const val KEY_HOLDERS = "holders"
    private const val KEY_ALARM_VOLUME = "alarm_volume"
    private const val KEY_FILTER = "interruption_filter"

    @Synchronized
    fun acquire(context: Context, holder: String, volumePercent: Int, ttlMs: Long) {
        val prefs = prefs(context)
        val holders = activeHolders(prefs)
        if (holders.isEmpty()) {
            // A leftover snapshot means the last alert never cleaned up; restore it first so we
            // don't save the overridden state as the "original".
            restore(context, prefs)
            override(context, prefs, volumePercent)
        }
        holders[holder] = System.currentTimeMillis() + ttlMs
        writeHolders(prefs, holders)
    }

    @Synchronized
    fun release(context: Context, holder: String) {
        val prefs = prefs(context)
        val holders = activeHolders(prefs)
        holders.remove(holder)
        writeHolders(prefs, holders)
        if (holders.isEmpty()) restore(context, prefs)
    }

    @Synchronized
    fun releaseAll(context: Context) {
        val prefs = prefs(context)
        writeHolders(prefs, emptyMap())
        restore(context, prefs)
    }

    /** Restores the original state once every holder has expired. */
    @Synchronized
    fun cleanup(context: Context) {
        val prefs = prefs(context)
        val holders = activeHolders(prefs)
        writeHolders(prefs, holders)
        if (holders.isEmpty()) restore(context, prefs)
    }

    /** Whether the current Do Not Disturb state lets alarms make sound. */
    fun alarmsAllowed(nm: NotificationManager): Boolean = when (nm.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> false
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
            val policy = runCatching { nm.consolidatedNotificationPolicy }.getOrNull()
                ?: runCatching { nm.notificationPolicy }.getOrNull()
            policy == null ||
                (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
        }
        else -> true
    }

    private fun override(context: Context, prefs: SharedPreferences, volumePercent: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)
        prefs.edit(commit = true) {
            if (!alarmsAllowed(nm) && nm.isNotificationPolicyAccessGranted) {
                putInt(KEY_FILTER, nm.currentInterruptionFilter)
                runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
                if (!alarmsAllowed(nm)) {
                    runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                }
            }
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = targetVolume(am, volumePercent)
            if (current < target) {
                putInt(KEY_ALARM_VOLUME, current)
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
            }
        }
    }

    private fun restore(context: Context, prefs: SharedPreferences) {
        val volume = prefs.getInt(KEY_ALARM_VOLUME, -1)
        val filter = prefs.getInt(KEY_FILTER, NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        if (volume >= 0) {
            val am = context.getSystemService(AudioManager::class.java)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        if (filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter != filter) {
                runCatching { nm.setInterruptionFilter(filter) }
            }
        }
        prefs.edit(commit = true) {
            remove(KEY_ALARM_VOLUME)
            remove(KEY_FILTER)
        }
    }

    private fun targetVolume(am: AudioManager, percent: Int): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val min = am.getStreamMinVolume(AudioManager.STREAM_ALARM)
        return ((max * percent.coerceIn(0, 100) + 99) / 100).coerceIn(min, max)
    }

    private fun activeHolders(prefs: SharedPreferences): MutableMap<String, Long> {
        val now = System.currentTimeMillis()
        return prefs.getStringSet(KEY_HOLDERS, emptySet()).orEmpty().mapNotNull { entry ->
            val expiry = entry.substringAfter('=').toLongOrNull() ?: return@mapNotNull null
            if (expiry > now) entry.substringBefore('=') to expiry else null
        }.toMap(mutableMapOf())
    }

    private fun writeHolders(prefs: SharedPreferences, holders: Map<String, Long>) {
        prefs.edit(commit = true) { putStringSet(KEY_HOLDERS, holders.map { "${it.key}=${it.value}" }.toSet()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
