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
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.jakobholmelund.hypervip.R
import io.github.jakobholmelund.hypervip.call.CallTracker
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.match.VipMatcher
import io.github.jakobholmelund.hypervip.notify.OngoingVisibility

/**
 * Decides when a VIP should ring through, and starts and stops the ringing.
 *
 * Ringing is our own playback on the alarm stream ([Ringer]) inside a foreground service
 * ([RingService]), like an alarm clock. Silent and vibrate mode don't mute alarms, and Do Not
 * Disturb allows them by default. (Posting a notification with alarm audio attributes doesn't
 * work: HyperOS silent mode mutes notification sounds regardless.)
 */
object VipAlerts {
    const val ACTION_STOP = "io.github.jakobholmelund.hypervip.action.STOP_ALERT"
    const val NOTIFICATION_RINGING = 1

    private const val CHANNEL_RINGING = "vip_ringing"
    /** Channels from the version that rang through notification sounds. */
    private val OLD_CHANNELS = listOf("vip_calls", "vip_messages")

    private const val HOLDER_CALL = "call"
    private const val HOLDER_MESSAGE = "message"

    /** Unanswered calls normally go to voicemail well before this. */
    private const val CALL_TIMEOUT_MS = 90_000L
    /** WhatsApp gives up on an unanswered call after about a minute. */
    private const val APP_CALL_TIMEOUT_MS = 60_000L
    private const val TEST_RING_MS = 8_000L
    private const val MESSAGE_HOLD_MS = 10_000L

    /** How long to give the phone to start ringing by itself before we ring instead. */
    private const val RING_CHECK_DELAY_MS = 1_200L

    private const val PREFS = "alert"
    private const val KEY_SOURCE = "source"
    private const val KEY_APP_KEY = "app_key"
    private const val KEY_STARTED = "started"

    enum class Source { PHONE, APP, TEST }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        val ringing = NotificationChannel(CHANNEL_RINGING, "VIP ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while a VIP rings through silent mode or Do Not Disturb, with a button to silence it"
            // The sound comes from Hyper VIP's own player, not the notification.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        nm.createNotificationChannel(ringing)
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
        val app = context.applicationContext
        val settings = SettingsStore.get(app)
        AudioState.acquire(app, HOLDER_CALL, settings.alertVolumePercent, CALL_TIMEOUT_MS + 30_000)

        val title = if (source == Source.TEST) "Hyper VIP test ring" else "$name is calling"
        val text = when (source) {
            Source.PHONE -> "VIP — ringing through silent mode and Do Not Disturb"
            Source.APP -> "VIP call in another app — ringing through silent mode and Do Not Disturb"
            Source.TEST -> "This is what a VIP call sounds like. Tap to stop."
        }
        val timeout = when (source) {
            Source.PHONE -> CALL_TIMEOUT_MS
            Source.APP -> APP_CALL_TIMEOUT_MS
            Source.TEST -> TEST_RING_MS
        }
        val intent = Intent(app, RingService::class.java)
            .putExtra(RingService.EXTRA_TITLE, title)
            .putExtra(RingService.EXTRA_TEXT, text)
            .putExtra(RingService.EXTRA_TIMEOUT_MS, timeout)
            .putExtra(RingService.EXTRA_APP_CALL, source == Source.APP)
        val started = System.currentTimeMillis()
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            // Android refuses foreground services started from the background unless battery
            // optimisation is off. Ring from this process anyway; it may be cut short.
            EventLog.log(app, "Couldn't ring in the foreground (${e.javaClass.simpleName}) — set Battery to No restrictions")
            val main = Handler(Looper.getMainLooper())
            main.post { Ringer.startRinging(app, settings.ringtoneUri) }
            main.postDelayed({
                if (prefs(app).getLong(KEY_STARTED, 0) == started) stopAlert(app, "no answer")
            }, timeout)
            if (canNotify(app)) notify(app, NOTIFICATION_RINGING, ringingNotification(app, title, text))
        }

        prefs(app).edit(commit = true) {
            putString(KEY_SOURCE, source.name)
            putString(KEY_APP_KEY, appKey)
            putLong(KEY_STARTED, started)
        }
        EventLog.log(
            app,
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
        val app = context.applicationContext
        val prefs = prefs(app)
        val source = prefs.getString(KEY_SOURCE, null)
        if (onlySource != null && source != onlySource.name) return
        if (onlyAppKey != null && prefs.getString(KEY_APP_KEY, null) != onlyAppKey) return
        if (source == Source.TEST.name) OngoingVisibility.onTestRingFinished(app, prefs.getLong(KEY_STARTED, 0))
        app.stopService(Intent(app, RingService::class.java))
        Ringer.stop()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_RINGING)
        AudioState.release(app, HOLDER_CALL)
        prefs.edit(commit = true) {
            remove(KEY_SOURCE)
            remove(KEY_APP_KEY)
            remove(KEY_STARTED)
        }
        if (source != null) {
            EventLog.log(app, if (source == Source.TEST.name) "Test ring finished" else "Stopped ringing — $reason")
        }
    }

    fun isActive(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getString(KEY_SOURCE, null) == null) return false
        // Don't let an alert whose end we never heard about block the next one.
        return System.currentTimeMillis() - prefs.getLong(KEY_STARTED, 0) < CALL_TIMEOUT_MS
    }

    fun messageAlert(context: Context, name: String, isTest: Boolean = false) {
        val app = context.applicationContext
        AudioState.acquire(app, HOLDER_MESSAGE, SettingsStore.get(app).alertVolumePercent, MESSAGE_HOLD_MS)
        Handler(Looper.getMainLooper()).post {
            Ringer.playOnce(app) { AudioState.release(app, HOLDER_MESSAGE) }
        }
        EventLog.log(app, if (isTest) "Test message alert played" else "Message from $name — alerted")
    }

    fun testRing(context: Context) {
        if (!isActive(context)) startAlert(context, "Test", Source.TEST)
    }

    fun ringingNotification(context: Context, title: String, text: String): Notification {
        val stop = stopIntent(context)
        return NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(0, "Silence", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
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

    @SuppressLint("MissingPermission") // Callers check canNotify() first.
    private fun notify(context: Context, id: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
