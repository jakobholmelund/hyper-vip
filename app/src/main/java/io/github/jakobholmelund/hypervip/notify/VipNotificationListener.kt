package io.github.jakobholmelund.hypervip.notify

import android.app.Notification
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.AppSettings
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher

/**
 * Watches other apps' notifications for VIP calls (WhatsApp, Signal, Telegram, …) and messages.
 * Being bound by the system also keeps Hyper VIP's process alive, which helps on HyperOS.
 */
class VipNotificationListener : NotificationListenerService() {
    private val lastMessageAlert = HashMap<String, Long>()

    override fun onListenerConnected() {
        AudioState.cleanup(this)
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
        if (!isIncomingCall(sbn.notification)) {
            // The incoming-call notification turning into an ongoing-call one means it was answered.
            VipAlerts.stopAlert(this, "call answered", onlySource = VipAlerts.Source.APP, onlyAppKey = sbn.key)
            return
        }
        if (VipAlerts.isActive(this)) return
        val vip = VipMatcher.byNotification(this, sbn.notification) ?: return
        if (willAlertByItself(sbn)) return
        VipAlerts.startAlert(this, vip.name, VipAlerts.Source.APP, sbn.key)
    }

    private fun onOtherNotification(sbn: StatusBarNotification, settings: AppSettings) {
        if (!settings.messageAlerts) return
        val notification = sbn.notification
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val category = notification.category
        if (category != null && category != Notification.CATEGORY_MESSAGE) return
        val vip = VipMatcher.byNotification(this, notification) ?: return
        if (willAlertByItself(sbn)) return
        val now = System.currentTimeMillis()
        val key = vip.lookupKey.ifEmpty { vip.contactId.toString() }
        if (now - (lastMessageAlert[key] ?: 0L) < MESSAGE_COOLDOWN_MS) return
        lastMessageAlert[key] = now
        VipAlerts.messageAlert(this, vip.name)
    }

    /** True when Do Not Disturb didn't hold the notification back and the ringer is on. */
    private fun willAlertByItself(sbn: StatusBarNotification): Boolean {
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
        if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) return false
        // Apps that don't use CallStyle still launch their ringing screen as a full-screen intent.
        return notification.fullScreenIntent != null
    }

    private fun isPhoneApp(pkg: String): Boolean {
        if (pkg in PHONE_PACKAGES) return true
        val dialer = runCatching { getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        return pkg == dialer
    }

    private companion object {
        const val MESSAGE_COOLDOWN_MS = 20_000L

        // Values of the CallStyle extras (Notification.EXTRA_CALL_TYPE and CallStyle.CALL_TYPE_*).
        const val EXTRA_CALL_TYPE = "android.callType"
        const val CALL_TYPE_UNKNOWN = 0
        const val CALL_TYPE_INCOMING = 1
        const val CALL_TYPE_ONGOING = 2
        const val CALL_TYPE_SCREENING = 3

        val PHONE_PACKAGES = setOf(
            "com.android.incallui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.android.contacts", // HyperOS's own Phone app
            "com.google.android.dialer",
        )
    }
}
