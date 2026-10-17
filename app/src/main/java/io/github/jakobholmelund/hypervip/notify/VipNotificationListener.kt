package io.github.jakobholmelund.hypervip.notify

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.AppSettings
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher

/**
 * Watches other apps' notifications for VIP calls (WhatsApp, Signal, Telegram, …) and messages.
 * Being bound by the system also keeps Hyper VIP's process alive, which helps on HyperOS.
 */
class VipNotificationListener : NotificationListenerService() {
    private val lastMessageAlert = HashMap<String, Long>()

    override fun onListenerConnected() {
        connected = true
        AudioState.cleanup(this)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val settings = SettingsStore.get(this)
        if (!settings.enabled || (!settings.appCallAlerts && !settings.messageAlerts)) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        try {
            if (notification.category == Notification.CATEGORY_CALL) onCallNotification(sbn, settings)
            else onOtherNotification(sbn, settings)
        } catch (e: Exception) {
            Log.w("HyperVip", "Failed to handle notification from ${sbn.packageName}", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        VipAlerts.stopAlert(this, "call ended", onlySource = VipAlerts.Source.APP, onlyAppKey = sbn.key)
    }

    private fun onCallNotification(sbn: StatusBarNotification, settings: AppSettings) {
        // Cellular calls are handled from the call-state broadcast, which has the real number.
        if (!settings.appCallAlerts || isPhoneApp(sbn.packageName)) return
        val notification = sbn.notification
        if (!isIncomingCall(notification)) {
            // The incoming-call notification turning into an ongoing-call one means it was answered.
            VipAlerts.stopAlert(this, "call answered", onlySource = VipAlerts.Source.APP, onlyAppKey = sbn.key)
            return
        }
        if (VipAlerts.isActive(this)) return
        val app = appName(sbn.packageName)
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        val vip = VipMatcher.byNotification(this, notification)
        if (vip == null) {
            EventLog.log(this, "$app call from “$title” — not a VIP")
            return
        }
        if (!isSilenced()) {
            EventLog.log(this, "${vip.name} is calling on $app — the phone isn't silenced, so $app rings itself")
            return
        }
        VipAlerts.startAlert(this, vip.name, VipAlerts.Source.APP, sbn.key)
    }

    private fun onOtherNotification(sbn: StatusBarNotification, settings: AppSettings) {
        if (!settings.messageAlerts) return
        val notification = sbn.notification
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val category = notification.category
        if (category != null && category != Notification.CATEGORY_MESSAGE) return
        val vip = VipMatcher.byNotification(this, notification) ?: return
        if (wasAlertedNormally(sbn)) return
        val now = System.currentTimeMillis()
        val key = vip.lookupKey.ifEmpty { vip.contactId.toString() }
        if (now - (lastMessageAlert[key] ?: 0L) < MESSAGE_COOLDOWN_MS) return
        lastMessageAlert[key] = now
        VipAlerts.messageAlert(this, vip.name)
    }

    /**
     * Whether other apps' calls would be quiet. Calling apps play their own ringtone, which Do Not
     * Disturb can mute at the audio level even when it lets the notification through, so any Do
     * Not Disturb mode counts.
     */
    private fun isSilenced(): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        return audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.getStreamVolume(AudioManager.STREAM_RING) == 0 ||
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /** True when Do Not Disturb didn't hold the notification back and the ringer is on. */
    private fun wasAlertedNormally(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        val known = currentRanking?.getRanking(sbn.key, ranking) == true
        val audio = getSystemService(AudioManager::class.java)
        return known && ranking.matchesInterruptionFilter() &&
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    private fun isIncomingCall(notification: Notification): Boolean {
        val extras = notification.extras
        when (extras.getInt(EXTRA_CALL_TYPE, CALL_TYPE_UNKNOWN)) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> return true
            CALL_TYPE_ONGOING -> return false
        }
        // Without CallStyle, a running call timer is the tell-tale sign of an answered call.
        return !extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
    }

    private fun isPhoneApp(pkg: String): Boolean {
        if (pkg in PHONE_PACKAGES) return true
        val dialer = runCatching { getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        return pkg == dialer
    }

    private fun appName(pkg: String): String = APP_NAMES[pkg] ?: pkg

    companion object {
        /** Whether the system currently has the listener bound; shown in the app for troubleshooting. */
        @Volatile
        var connected = false
            private set

        private const val MESSAGE_COOLDOWN_MS = 20_000L

        // Values of the CallStyle extras (Notification.EXTRA_CALL_TYPE and CallStyle.CALL_TYPE_*).
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val CALL_TYPE_UNKNOWN = 0
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3

        private val PHONE_PACKAGES = setOf(
            "com.android.incallui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.android.contacts", // HyperOS's own Phone app
            "com.google.android.dialer",
        )

        private val APP_NAMES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.thoughtcrime.securesms" to "Signal",
            "org.telegram.messenger" to "Telegram",
            "com.facebook.orca" to "Messenger",
            "com.viber.voip" to "Viber",
            "com.skype.raider" to "Skype",
            "com.microsoft.teams" to "Teams",
            "com.google.android.apps.tachyon" to "Google Meet",
        )
    }
}
