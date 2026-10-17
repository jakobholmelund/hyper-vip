package io.github.jakobholmelund.hypervip.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import io.github.jakobholmelund.hypervip.R

/**
 * Plays VIP alerts the way alarm clock apps do: our own MediaPlayer on the alarm stream. HyperOS
 * silent mode mutes notification sounds whatever their audio attributes, but not alarm playback.
 * Tries the chosen ringtone, then the phone's ringtone and alarm sound, then a bundled sound.
 *
 * Android only cancels a vibration, or abandons audio focus, through the same Vibrator or
 * AudioManager instance that started it, and those instances differ per Context. So everything
 * goes through the application context, and the instances in use are kept until [stop].
 */
object Ringer {
    private const val TAG = "HyperVip"

    private val alarm: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val ringPattern = longArrayOf(0, 800, 600)
    private val messagePattern = longArrayOf(0, 250, 150, 250)

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focus: Pair<AudioManager, AudioFocusRequest>? = null
    private var ringing = false

    /** Rings in a loop until [stop]. */
    @Synchronized
    fun startRinging(context: Context, ringtone: Uri?) {
        val app = context.applicationContext
        stop()
        val sources = listOfNotNull(ringtone, Settings.System.DEFAULT_RINGTONE_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
        player = createPlayer(app, sources, R.raw.vip_ring, looping = true)?.also { it.start() }
        if (player == null) Log.w(TAG, "No playable ringtone")
        requestFocus(app)
        vibrate(app, ringPattern, repeat = 0)
        ringing = true
    }

    /** Plays the notification sound once. Does nothing while a call is ringing. */
    @Synchronized
    fun playOnce(context: Context, onDone: () -> Unit) {
        if (ringing) return onDone()
        val app = context.applicationContext
        stop()
        val once = createPlayer(app, listOf(Settings.System.DEFAULT_NOTIFICATION_URI), R.raw.vip_message, looping = false)
            ?: return onDone()
        player = once
        once.setOnCompletionListener {
            synchronized(this) {
                // Leave the short vibration to finish on its own.
                if (player === it) {
                    it.release()
                    player = null
                    abandonFocus()
                }
            }
            onDone()
        }
        requestFocus(app)
        once.start()
        vibrate(app, messagePattern, repeat = -1)
    }

    @Synchronized
    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        abandonFocus()
        ringing = false
    }

    private fun abandonFocus() {
        focus?.let { (am, request) -> runCatching { am.abandonAudioFocusRequest(request) } }
        focus = null
    }

    private fun createPlayer(context: Context, sources: List<Uri>, fallback: Int, looping: Boolean): MediaPlayer? {
        for (uri in sources) {
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(alarm)
                candidate.setDataSource(context, uri)
                candidate.isLooping = looping
                candidate.prepare()
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "Can't play $uri: ${e.message}")
                candidate.release()
            }
        }
        val am = context.getSystemService(AudioManager::class.java)
        return runCatching {
            MediaPlayer.create(context, fallback, alarm, am.generateAudioSessionId())?.apply { isLooping = looping }
        }.getOrNull()
    }

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarm)
            .build()
        // Pauses music while a VIP rings. Failing to get focus doesn't stop the alert.
        runCatching { am.requestAudioFocus(request) }
        focus = am to request
    }

    private fun vibrate(context: Context, pattern: LongArray, repeat: Int) {
        val device = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(pattern, repeat)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(effect, alarm)
            }
        }
        vibrator = device
    }
}
