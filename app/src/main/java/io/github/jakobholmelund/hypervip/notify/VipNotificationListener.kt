package io.github.jakobholmelund.hypervip.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.AppSettings
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher

/**
 * Watches other apps' notifications for VIP messages, and for VIP calls where the system passes
 * them on (HyperOS doesn't for WhatsApp; see [CallWatcherService]). Being bound by the system
 * also keeps Hyper VIP's process alive, which helps on HyperOS.
 */
class VipNotificationListener : NotificationListenerService() {
    private val lastMessageAlert = HashMap<String, Long>()
    private val loggedOther = HashSet<String>()

    override fun onListenerConnected() {
        connected = true
        AudioState.cleanup(this)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) {
            if (sbn.id == VipAlerts.NOTIFICATION_RINGING && sbn.isOngoing) OngoingVisibility.onOwnOngoingSeen(this)
            return
        }
        val settings = SettingsStore.get(this)
        if (!settings.enabled) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        try {
            when {
                AppCalls.isCallNotification(sbn.packageName, notification) ->
                    AppCalls.onCallNotification(this, sbn.packageName, notification, sbn.key)
                notification.category == Notification.CATEGORY_MISSED_CALL ->
                    AppCalls.onMissedCall(this, sbn.packageName)
                else -> onOtherNotification(sbn, settings)
            }
        } catch (e: Exception) {
            Log.w("HyperVip", "Failed to handle notification from ${sbn.packageName}", e)
            EventLog.log(this, "Error reading a ${AppCalls.appName(sbn.packageName)} notification: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        loggedOther.remove(sbn.key)
        val notification = sbn.notification ?: return
        if (AppCalls.isCallNotification(sbn.packageName, notification)) {
            VipAlerts.stopAlert(this, "call ended", onlySource = VipAlerts.Source.APP, onlyAppKey = sbn.packageName)
        }
    }

    private fun onOtherNotification(sbn: StatusBarNotification, settings: AppSettings) {
        val notification = sbn.notification
        if (sbn.packageName in AppCalls.APP_NAMES && notification.category != Notification.CATEGORY_MESSAGE &&
            loggedOther.add(sbn.key)
        ) {
            // Calling apps that ring without a recognisable call notification show up here.
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)
            EventLog.log(
                this,
                "${AppCalls.appName(sbn.packageName)} notification “$title” / “$text” " +
                    "(category ${notification.category ?: "none"}) — not treated as a call",
            )
        }
        if (!settings.messageAlerts) return
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

    /** True when Do Not Disturb didn't hold the notification back and the ringer is on. */
    private fun wasAlertedNormally(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        val known = currentRanking?.getRanking(sbn.key, ranking) == true
        val audio = getSystemService(AudioManager::class.java)
        return known && ranking.matchesInterruptionFilter() &&
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    companion object {
        /** Whether the system currently has the listener bound; shown in the app for troubleshooting. */
        @Volatile
        var connected = false
            private set

        private const val MESSAGE_COOLDOWN_MS = 20_000L

        /** HyperOS sometimes drops the listener's binding; ask for it back if access is granted. */
        fun ensureBound(context: Context) {
            if (connected) return
            val component = ComponentName(context, VipNotificationListener::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.isNotificationListenerAccessGranted(component)) {
                runCatching { NotificationListenerService.requestRebind(component) }
            }
        }
    }
}
