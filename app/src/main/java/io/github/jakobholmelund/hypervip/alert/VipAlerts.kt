package io.github.jakobholmelund.hypervip.alert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import io.github.jakobholmelund.hypervip.R
import io.github.jakobholmelund.hypervip.call.CallTracker
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher

/**
 * Rings for VIPs by posting a notification whose sound plays on the alarm stream. Silent and
 * vibrate mode don't mute alarms, and Do Not Disturb treats alarm-usage notifications as alarms,
 * which it allows by default. The system plays the sound (looping, via FLAG_INSISTENT), so it
 * keeps ringing even if HyperOS freezes this app; cancelling the notification stops it.
 */
object VipAlerts {
    const val ACTION_STOP = "io.github.jakobholmelund.hypervip.action.STOP_ALERT"
    const val CHANNEL_CALLS = "vip_calls"
    const val CHANNEL_MESSAGES = "vip_messages"

    private const val NOTIFICATION_CALL = 1
    private const val NOTIFICATION_MESSAGE = 2

    private const val HOLDER_CALL = "call"
    private const val HOLDER_MESSAGE = "message"

    /** Unanswered calls normally go to voicemail well before this. */
    private const val CALL_TIMEOUT_MS = 90_000L
    private const val MESSAGE_SOUND_MS = 8_000L
    private const val TEST_RING_MS = 8_000L

    /** How long to give the phone to start ringing by itself before we ring instead. */
    private const val RING_CHECK_DELAY_MS = 1_200L

    private const val PREFS = "alert"
    private const val KEY_SOURCE = "source"
    private const val KEY_APP_KEY = "app_key"
    private const val KEY_STARTED = "started"

    enum class Source { PHONE, APP, TEST }

    private val alarmSound: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun createChannels(context: Context) {
        val calls = NotificationChannel(CHANNEL_CALLS, "VIP calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when a VIP calls while your phone is silenced"
            setSound(Settings.System.DEFAULT_RINGTONE_URI, alarmSound)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        val messages = NotificationChannel(CHANNEL_MESSAGES, "VIP messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Plays a sound when a VIP messages you while your phone is silenced"
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, alarmSound)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(calls, messages))
    }

    /** Called on a background thread for every incoming cellular call whose number is known. */
    fun onIncomingPhoneCall(context: Context, number: String) {
        if (!SettingsStore.get(context).enabled) return
        val vip = VipMatcher.byNumber(context, number)
        if (vip == null) {
            EventLog.log(context, "Incoming call from someone who isn't a VIP")
            return
        }
        if (isActive(context)) return
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            EventLog.log(context, "${vip.name} is calling while you're on another call — leaving the call-waiting tone to the phone")
            return
        }
        val ringerAudible = am.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            am.getStreamVolume(AudioManager.STREAM_RING) > 0
        if (ringerAudible && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            EventLog.log(context, "${vip.name} is calling — the phone isn't silenced, so it rings normally")
            return
        }
        if (ringerAudible) {
            // Only Do Not Disturb is in the way, and it may already let this caller through
            // (starred contacts, repeat callers): give the phone a moment to start ringing.
            // In silent or vibrate mode a ringtone player can't be heard (it may even be a
            // haptics-only one), so there's nothing to wait for.
            SystemClock.sleep(RING_CHECK_DELAY_MS)
            if (CallTracker.state(context) != TelephonyManager.EXTRA_STATE_RINGING) return
            if (isRingtonePlaying(am)) {
                EventLog.log(context, "${vip.name} is calling — Do Not Disturb already lets the call ring")
                return
            }
        }
        startAlert(context, vip.name, Source.PHONE)
    }

    @Synchronized
    fun startAlert(context: Context, name: String, source: Source, appKey: String? = null) {
        // The call may have been answered while we were deciding.
        if (source == Source.PHONE && CallTracker.state(context) != TelephonyManager.EXTRA_STATE_RINGING) return
        if (!canNotify(context)) {
            EventLog.log(context, "Couldn't ring for $name — notifications are turned off for Hyper VIP")
            return
        }
        AudioState.acquire(context, HOLDER_CALL, SettingsStore.get(context).alertVolumePercent, CALL_TIMEOUT_MS + 30_000)

        val stop = stopIntent(context)
        val title = if (source == Source.TEST) "Hyper VIP test ring" else "$name is calling"
        val text = when (source) {
            Source.PHONE -> "VIP — ringing through silent mode and Do Not Disturb"
            Source.APP -> "VIP call in another app — ringing through silent mode and Do Not Disturb"
            Source.TEST -> "This is what a VIP call sounds like. Tap to stop."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(CALL_TIMEOUT_MS)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(0, "Silence", stop)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        notify(context, NOTIFICATION_CALL, notification)

        prefs(context).edit(commit = true) {
            putString(KEY_SOURCE, source.name)
            putString(KEY_APP_KEY, appKey)
            putLong(KEY_STARTED, System.currentTimeMillis())
        }
        EventLog.log(
            context,
            when (source) {
                Source.PHONE -> "$name is calling — ringing through"
                Source.APP -> "$name is calling in another app — ringing through"
                Source.TEST -> "Test ring started"
            },
        )
    }

    /**
     * Stops the ringing and restores the alarm volume. [onlySource] and [onlyAppKey] limit this
     * to one kind of alert, so e.g. a cellular call ending doesn't silence a WhatsApp call.
     */
    @Synchronized
    fun stopAlert(context: Context, reason: String, onlySource: Source? = null, onlyAppKey: String? = null) {
        val prefs = prefs(context)
        val source = prefs.getString(KEY_SOURCE, null)
        if (onlySource != null && source != onlySource.name) return
        if (onlyAppKey != null && prefs.getString(KEY_APP_KEY, null) != onlyAppKey) return
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_CALL)
        AudioState.release(context, HOLDER_CALL)
        prefs.edit(commit = true) {
            remove(KEY_SOURCE)
            remove(KEY_APP_KEY)
            remove(KEY_STARTED)
        }
        if (source != null) EventLog.log(context, "Stopped ringing — $reason")
    }

    fun isActive(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getString(KEY_SOURCE, null) == null) return false
        // Don't let an alert whose end we never heard about block the next one.
        return System.currentTimeMillis() - prefs.getLong(KEY_STARTED, 0) < CALL_TIMEOUT_MS
    }

    fun messageAlert(context: Context, name: String, isTest: Boolean = false) {
        if (!canNotify(context)) {
            EventLog.log(context, "Couldn't alert for $name's message — notifications are turned off for Hyper VIP")
            return
        }
        val app = context.applicationContext
        AudioState.acquire(app, HOLDER_MESSAGE, SettingsStore.get(app).alertVolumePercent, MESSAGE_SOUND_MS * 2)
        val notification = NotificationCompat.Builder(app, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isTest) "Hyper VIP test message" else "Message from $name")
            .setContentText("VIP — alerted through silent mode and Do Not Disturb")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        notify(app, NOTIFICATION_MESSAGE, notification)
        EventLog.log(app, if (isTest) "Test message alert played" else "Message from $name — alerted")
        Handler(Looper.getMainLooper()).postDelayed({ AudioState.release(app, HOLDER_MESSAGE) }, MESSAGE_SOUND_MS)
    }

    fun testRing(context: Context) {
        val app = context.applicationContext
        startAlert(app, "Test", Source.TEST)
        Handler(Looper.getMainLooper()).postDelayed(
            { stopAlert(app, "test finished", onlySource = Source.TEST) },
            TEST_RING_MS,
        )
    }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun isRingtonePlaying(am: AudioManager): Boolean =
        am.activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlertActionReceiver::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // Every caller checks canNotify() first.
    private fun notify(context: Context, id: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
