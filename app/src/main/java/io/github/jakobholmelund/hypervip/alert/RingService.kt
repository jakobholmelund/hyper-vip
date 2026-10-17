package io.github.jakobholmelund.hypervip.alert

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import io.github.jakobholmelund.hypervip.data.SettingsStore

/**
 * Keeps Hyper VIP in the foreground while a VIP rings, so HyperOS doesn't freeze or kill the
 * process mid-ring. Started by [VipAlerts.startAlert] and stopped by [VipAlerts.stopAlert].
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { VipAlerts.stopAlert(this, "no answer") }

    /**
     * Calling apps switch the phone into call audio when you answer. That's the only sign of
     * answering we get when the app's call notification isn't visible to us.
     */
    private val watchForAnswer = object : Runnable {
        override fun run() {
            val mode = getSystemService(AudioManager::class.java).mode
            if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                VipAlerts.stopAlert(this@RingService, "call answered", onlySource = VipAlerts.Source.APP)
            } else {
                handler.postDelayed(this, ANSWER_POLL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "A VIP is calling"
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        ServiceCompat.startForeground(
            this,
            VipAlerts.NOTIFICATION_RINGING,
            VipAlerts.ringingNotification(this, title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        Ringer.startRinging(this, SettingsStore.get(this).ringtoneUri)
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, intent?.getLongExtra(EXTRA_TIMEOUT_MS, 0L)?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS)
        handler.removeCallbacks(watchForAnswer)
        if (intent?.getBooleanExtra(EXTRA_APP_CALL, false) == true) handler.postDelayed(watchForAnswer, ANSWER_POLL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(watchForAnswer)
        Ringer.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val EXTRA_APP_CALL = "app_call"
        private const val DEFAULT_TIMEOUT_MS = 90_000L
        private const val ANSWER_POLL_MS = 1_000L
    }
}
