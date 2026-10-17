package io.github.jakobholmelund.hypervip.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher

/**
 * Incoming calls in WhatsApp, Signal, Telegram etc., seen either by the notification listener or
 * by the accessibility watcher ([CallWatcherService]). HyperOS doesn't pass WhatsApp's ringing
 * notification to notification listeners, which is why the accessibility route exists.
 *
 * App-call alerts use the calling app's package as their key, so whichever route saw the call,
 * a missed-call notification or the call notification going away stops the right alert.
 */
object AppCalls {
    val APP_NAMES = mapOf(
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

    private val PHONE_PACKAGES = setOf(
        "com.android.incallui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.android.contacts", // HyperOS's own Phone app
        "com.google.android.dialer",
    )

    // Values of the CallStyle extras (Notification.EXTRA_CALL_TYPE and CallStyle.CALL_TYPE_*).
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val CALL_TYPE_UNKNOWN = 0
    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_ONGOING = 2
    private const val CALL_TYPE_SCREENING = 3

    /** Log lines already written, so a notification that's updated doesn't flood the log. */
    private val logged = LinkedHashSet<String>()

    fun appName(pkg: String): String = APP_NAMES[pkg] ?: pkg

    fun isPhoneApp(context: Context, pkg: String): Boolean {
        if (pkg in PHONE_PACKAGES) return true
        val dialer = runCatching { context.getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        return pkg == dialer
    }

    fun isCallNotification(pkg: String, notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        if (notification.extras.containsKey(EXTRA_CALL_TYPE)) return true
        // Not every calling app labels its ringing notification; a full-screen one from a calling
        // app is its incoming-call screen.
        return pkg in APP_NAMES && notification.fullScreenIntent != null
    }

    /** Incoming rather than outgoing or answered: CallStyle says so, or it has a full-screen screen. */
    fun isIncomingCall(notification: Notification): Boolean {
        val extras = notification.extras
        when (extras.getInt(EXTRA_CALL_TYPE, CALL_TYPE_UNKNOWN)) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> return true
            CALL_TYPE_ONGOING -> return false
        }
        if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) return false
        return notification.fullScreenIntent != null
    }

    /**
     * Whether other apps' calls would be quiet. Calling apps play their own ringtone, which Do Not
     * Disturb can mute at the audio level even when it lets the notification through, so any Do
     * Not Disturb mode counts.
     */
    fun isSilenced(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        return audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.getStreamVolume(AudioManager.STREAM_RING) == 0 ||
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /**
     * A call notification from [pkg]. [extraText] is anything else the route knows the
     * notification said (the accessibility event's text); [logKey] identifies it for the log.
     */
    fun onCallNotification(
        context: Context,
        pkg: String,
        notification: Notification,
        logKey: String,
        extraText: List<CharSequence> = emptyList(),
    ) {
        if (isPhoneApp(context, pkg)) return
        val settings = SettingsStore.get(context)
        if (!settings.enabled) return
        val app = appName(pkg)
        val extras = notification.extras
        val shown = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" / ") { "“$it”" }
        if (!settings.appCallAlerts) {
            logOnce(context, "off:$logKey", "$app call ignored — “Calls in other apps” is off")
            return
        }
        if (!isIncomingCall(notification)) {
            // The incoming-call notification turning into an ongoing-call one means it was answered.
            VipAlerts.stopAlert(context, "call answered", onlySource = VipAlerts.Source.APP, onlyAppKey = pkg)
            logOnce(context, "ongoing:$logKey", "$app call notification $shown looks like an ongoing call")
            return
        }
        if (VipAlerts.isActive(context)) return
        val vip = VipMatcher.byNotification(context, notification)
            ?: VipMatcher.byNameMention(context, notification)
            ?: VipMatcher.byTextMention(context, extraText)
        if (vip == null) {
            logOnce(context, "novip:$logKey", "$app call $shown — no VIP found in it")
            return
        }
        ringIfSilenced(context, pkg, vip.name)
    }

    /** A calling app's full-screen call screen opened on a locked phone; [texts] is what it shows. */
    fun onCallScreen(context: Context, pkg: String, texts: List<CharSequence>) {
        val settings = SettingsStore.get(context)
        if (!settings.enabled || !settings.appCallAlerts || VipAlerts.isActive(context)) return
        val app = appName(pkg)
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION || audio.mode == AudioManager.MODE_IN_CALL) {
            // Already talking: this is a call in progress or one you're making, not one ringing.
            return
        }
        val vip = VipMatcher.byTextMention(context, texts)
        if (vip == null) {
            EventLog.log(context, "$app call screen opened — no VIP name on it")
            return
        }
        ringIfSilenced(context, pkg, vip.name)
    }

    fun onMissedCall(context: Context, pkg: String) {
        VipAlerts.stopAlert(context, "missed call", onlySource = VipAlerts.Source.APP, onlyAppKey = pkg)
    }

    private fun ringIfSilenced(context: Context, pkg: String, name: String) {
        val app = appName(pkg)
        if (!isSilenced(context)) {
            EventLog.log(context, "$name is calling on $app — the phone isn't silenced, so $app rings itself")
            return
        }
        VipAlerts.startAlert(context, name, VipAlerts.Source.APP, pkg)
    }

    @Synchronized
    private fun logOnce(context: Context, key: String, message: String) {
        if (!logged.add(key)) return
        if (logged.size > 100) logged.remove(logged.first())
        EventLog.log(context, message)
    }
}
