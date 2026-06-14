package com.pomotick.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a short system alarm sound for timer reminders.
 *
 * Notification channels can be muted by watch-specific SystemUI policy, so this
 * player is a small fallback controlled by the same bounded reminder lifecycle.
 */
class ReminderSoundPlayer(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var stopJob: Job? = null

    fun playOnce(scope: CoroutineScope) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val next = RingtoneManager.getRingtone(context, uri) ?: return
            next.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone = next
            next.play()
            stopJob = scope.launch {
                delay(MAX_RING_MS)
                stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "play reminder sound failed", e)
        }
    }

    fun stop() {
        stopJob?.cancel()
        stopJob = null
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop reminder sound failed", e)
        } finally {
            ringtone = null
        }
    }

    companion object {
        private const val TAG = "PomoTick/Sound"
        private const val MAX_RING_MS = 4_000L
    }
}
