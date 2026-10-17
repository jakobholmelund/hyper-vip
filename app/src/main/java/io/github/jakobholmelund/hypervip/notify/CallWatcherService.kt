package io.github.jakobholmelund.hypervip.notify

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.EventLog
import java.util.Locale

/**
 * Spots incoming calls in WhatsApp, Signal, Telegram etc. through accessibility events, because
 * HyperOS doesn't pass WhatsApp's ringing notification to notification listeners.
 *
 * - Unlocked phone: the call arrives as a heads-up notification, which accessibility services
 *   get as a notification event.
 * - Locked phone: the app opens a full-screen call screen; its text names the caller.
 *
 * Its config (res/xml/call_watcher.xml) limits events to those calling apps. It also lets a
 * volume button press silence a VIP ring, like the phone does for normal calls.
 */
class CallWatcherService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in AppCalls.APP_NAMES) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> onNotificationEvent(pkg, event)
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowEvent(pkg, event)
            }
        } catch (e: Exception) {
            EventLog.log(this, "Error watching ${AppCalls.appName(pkg)}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun onNotificationEvent(pkg: String, event: AccessibilityEvent) {
        val notification = event.parcelableData as? Notification ?: return
        when {
            notification.category == Notification.CATEGORY_MISSED_CALL -> AppCalls.onMissedCall(this, pkg)
            AppCalls.isCallNotification(pkg, notification) -> AppCalls.onCallNotification(
                this,
                pkg,
                notification,
                logKey = "$pkg:${notification.`when`}",
                extraText = event.text.toList(),
            )
        }
    }

    private fun onWindowEvent(pkg: String, event: AccessibilityEvent) {
        val screen = event.className?.toString()?.lowercase(Locale.ROOT) ?: return
        if ("voip" !in screen && "call" !in screen) return
        // Incoming calls only open full screen on a locked phone; unlocked, they're a heads-up
        // notification (handled above), and an open call screen is usually your own outgoing call.
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val eventText = event.text.toList()
        // Give the screen a moment to draw the caller's name before reading it.
        handler.postDelayed({
            val texts = eventText + screenText(rootInActiveWindow)
            AppCalls.onCallScreen(this, pkg, texts)
        }, SCREEN_READ_DELAY_MS)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!VipAlerts.isActive(this)) return false
        if (event.action == KeyEvent.ACTION_DOWN) VipAlerts.stopAlert(this, "volume button")
        return true
    }

    private fun screenText(root: AccessibilityNodeInfo?): List<CharSequence> {
        val out = mutableListOf<CharSequence>()
        var budget = MAX_NODES
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || budget-- <= 0) return
            node.text?.let(out::add)
            node.contentDescription?.let(out::add)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return out
    }

    companion object {
        private const val SCREEN_READ_DELAY_MS = 600L
        private const val MAX_NODES = 300

        /** Whether the system has the service bound; shown in the app for troubleshooting. */
        @Volatile
        var connected = false
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, CallWatcherService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
