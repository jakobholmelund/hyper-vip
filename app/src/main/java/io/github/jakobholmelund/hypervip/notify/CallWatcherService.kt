package io.github.jakobholmelund.hypervip.notify

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
 * Two variants, which the user picks between in Accessibility settings:
 * - This one (res/xml/call_watcher.xml) only receives calling apps' notification events. It
 *   can't read the screen or intercept keys, which many banking apps tolerate.
 * - [CallWatcherFullService] (res/xml/call_watcher_full.xml) also reads the caller's name from
 *   the full-screen call screen on a locked phone and lets a volume button silence a VIP ring.
 *
 * The same code serves both; the light variant simply never gets window or key events.
 */
open class CallWatcherService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instances += this
        setKeyFiltering(VipAlerts.isActive(this))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instances -= this
        return super.onUnbind(intent)
    }

    private fun setKeyFiltering(on: Boolean) {
        val info = serviceInfo ?: return
        // Only the full variant declares it may filter keys; the light one never asks.
        if ((info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val flags = if (on) info.flags or flag else info.flags and flag.inv()
        if (flags != info.flags) serviceInfo = info.apply { this.flags = flags }
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
                via = AppCalls.Route.ACCESSIBILITY,
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
            // Only ever read the calling app's own window; by now something else may be in front.
            val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            AppCalls.onCallScreen(this, pkg, eventText + screenText(root))
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

        /** Bound watchers (light and/or full); touched on the main thread only. */
        private val instances = mutableSetOf<CallWatcherService>()

        /**
         * Intercept volume buttons (to silence the ring) only while a VIP rings; the rest of the
         * time key presses don't pass through Hyper VIP at all.
         */
        fun onRingingChanged(ringing: Boolean) {
            Handler(Looper.getMainLooper()).post { instances.forEach { it.setKeyFiltering(ringing) } }
        }

        enum class Mode { OFF, LIGHT, FULL }

        fun mode(context: Context): Mode {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.mapNotNull { ComponentName.unflattenFromString(it) }.orEmpty()
            return when {
                ComponentName(context, CallWatcherFullService::class.java) in enabled -> Mode.FULL
                ComponentName(context, CallWatcherService::class.java) in enabled -> Mode.LIGHT
                else -> Mode.OFF
            }
        }

        fun isEnabled(context: Context): Boolean = mode(context) != Mode.OFF
    }
}
