package io.github.jakobholmelund.hypervip.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.call.CallTracker
import io.github.jakobholmelund.hypervip.data.AppSettings
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher

/**
 * Watches other apps' notifications for VIP calls (WhatsApp, Signal, Telegram, …) and messages.
 * On HyperOS, WhatsApp's ringing notification is only visible in the active-notification list,
 * not as a posted notification, so calls are also found by checking that list. Being bound by
 * the system also keeps Hyper VIP's process alive, which helps on HyperOS.
 */
class VipNotificationListener : NotificationListenerService() {
    private val lastMessageAlert = HashMap<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private val scanAfterChange = Runnable { scanActiveForCalls() }
    private var modeListener: AudioManager.OnModeChangedListener? = null
    /** Calling apps whose call notification was in the list at the last check. */
    private var callAppsShowing = emptySet<String>()

    override fun onListenerConnected() {
        connected = true
        AudioState.cleanup(this)
        val audio = getSystemService(AudioManager::class.java)
        val listener = AudioManager.OnModeChangedListener { mode -> onAudioModeChanged(mode) }
        runCatching { audio.addOnModeChangedListener(mainExecutor, listener) }
        modeListener = listener
    }

    override fun onListenerDisconnected() {
        connected = false
        handler.removeCallbacks(scanAfterChange)
        modeListener?.let { l -> runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(l) } }
        modeListener = null
    }

    /**
     * HyperOS doesn't send us WhatsApp's ringing notification as it's posted, but it does include
     * it in the list of notifications currently showing (tested on a Xiaomi 15 Ultra). So check
     * that list whenever notifications change: a ranking update follows every post and removal.
     */
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        handler.removeCallbacks(scanAfterChange)
        handler.postDelayed(scanAfterChange, SCAN_DEBOUNCE_MS)
    }

    private fun onAudioModeChanged(mode: Int) {
        if (mode != AudioManager.MODE_RINGTONE) return
        scanActiveForCalls()
        // The app's notification can appear a moment after the phone starts ringing, so only
        // report a missing one if it still isn't there shortly after.
        handler.postDelayed({
            val ringingForApp = CallTracker.state(this) == TelephonyManager.EXTRA_STATE_IDLE &&
                getSystemService(AudioManager::class.java).mode == AudioManager.MODE_RINGTONE
            if (scanActiveForCalls() == 0 && ringingForApp) {
                EventLog.log(this, "An app call is ringing, but no call notification is visible to Hyper VIP")
            }
        }, MISSING_CALL_CHECK_MS)
    }

    /**
     * Handles calling-app call notifications in the current list, and stops a VIP ring once its
     * app's call notification has left the list (declined, answered on another device, or
     * given up). Returns how many call notifications are showing.
     */
    private fun scanActiveForCalls(): Int {
        val settings = SettingsStore.get(this)
        if (!settings.enabled || !settings.appCallAlerts) return 0
        val active = runCatching { activeNotifications }.getOrNull() ?: return 0
        val showing = mutableSetOf<String>()
        for (sbn in active) {
            if (sbn.packageName !in AppCalls.APP_NAMES) continue
            val notification = sbn.notification ?: continue
            if (!AppCalls.isCallNotification(sbn.packageName, notification)) continue
            showing += sbn.packageName
            AppCalls.onCallNotification(this, sbn.packageName, notification, "scan:${sbn.key}", via = AppCalls.Route.NOTIFICATION_SCAN)
        }
        for (gone in callAppsShowing - showing) {
            VipAlerts.stopAlert(this, "call ended", onlySource = VipAlerts.Source.APP, onlyAppKey = gone)
        }
        callAppsShowing = showing
        return showing.size
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val settings = SettingsStore.get(this)
        if (!settings.enabled) return
        val notification = sbn.notification ?: return
        // Group summaries are skipped, except calls: an app may post its ringing notification as one.
        val isCall = AppCalls.isCallNotification(sbn.packageName, notification)
        if (!isCall && (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        try {
            when {
                isCall -> AppCalls.onCallNotification(
                    this,
                    sbn.packageName,
                    notification,
                    sbn.key,
                    via = AppCalls.Route.NOTIFICATION_ACCESS,
                )
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
        val notification = sbn.notification ?: return
        if (AppCalls.isCallNotification(sbn.packageName, notification)) {
            VipAlerts.stopAlert(this, "call ended", onlySource = VipAlerts.Source.APP, onlyAppKey = sbn.packageName)
        }
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
        private const val SCAN_DEBOUNCE_MS = 300L
        private const val MISSING_CALL_CHECK_MS = 1_500L

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
