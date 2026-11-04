package com.agentcall.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import com.agentcall.app.settings.CallerTuneManager

/**
 * Process-wide owner of the audible ring for the live incoming call.
 *
 * The ring used to start only when [IncomingCallActivity] was created, so a
 * notification the user never opened (OEM full-screen-intent suppression, a
 * locked screen, a rejected heads-up) stayed silent and then auto-declined
 * after 60s. The notification post is the real start of the ring, so the ring
 * is started (and stopped) by the notification lifecycle instead:
 *
 * - started by [CallService.showIncomingCallNotification] (FGS and FCM paths),
 * - reused — never restarted — by [IncomingCallActivity] when it opens,
 * - stopped by [CallService.cancelIncomingNotification], which every answer,
 *   decline, later, timeout and terminal-ws path already funnels through.
 *
 * The ring is keyed by callId so duplicate posts or activity re-creation can
 * never stack two ringtones, and quiet hours post silently.
 */
object IncomingCallRinger {

    private const val TAG = "IncomingCallRinger"

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var fallback: Ringtone? = null
    private var activeCallId: String? = null
    private var quietRing = false

    /** Idempotent: a second start for the same call keeps the current ring. */
    fun start(context: Context, callId: String, quiet: Boolean) {
        if (callId.isBlank()) return
        synchronized(lock) {
            if (activeCallId == callId) return
            stopLocked()
            activeCallId = callId
            quietRing = quiet
        }
        if (quiet) {
            Log.i(TAG, "[RING] quiet hours — silent ring for $callId")
            return
        }
        val app = context.applicationContext
        val tune = runCatching { CallerTuneManager(app).uri }.getOrNull()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(app, tune)
            mp.isLooping = true
            mp.setOnPreparedListener { prepared ->
                val stillCurrent = synchronized(lock) {
                    activeCallId == callId && player === prepared
                }
                if (!stillCurrent) {
                    runCatching { prepared.release() }
                    return@setOnPreparedListener
                }
                // The tune can disappear (deleted file / revoked uri) between
                // setDataSource and prepare — fall back to the system ringtone
                // instead of answering a silent call.
                if (runCatching { prepared.start() }.isFailure) {
                    startFallback(app, tune)
                }
            }
            mp.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "[RING] player error what=$what extra=$extra")
                synchronized(lock) {
                    if (player === failed) {
                        player = null
                        runCatching { failed.release() }
                        startFallback(app, tune)
                    }
                }
                true
            }
            synchronized(lock) { player = mp }
            mp.prepareAsync()
            Log.i(TAG, "[RING] start callId=$callId tune=$tune")
        } catch (e: Exception) {
            Log.w(TAG, "[RING] player setup failed — using system ringtone", e)
            runCatching { mp.release() }
            synchronized(lock) { if (player === mp) player = null }
            startFallback(app, tune)
        }
    }

    private fun startFallback(context: Context, tune: android.net.Uri) {
        synchronized(lock) {
            if (activeCallId == null) return
            if (fallback != null) return
            fallback = runCatching {
                RingtoneManager.getRingtone(context, tune)
                    ?: RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            }.getOrNull()?.also { runCatching { it.play() } }
        }
    }

    /** Safe to call repeatedly from any path. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        fallback?.let { runCatching { it.stop() } }
        fallback = null
        if (activeCallId != null) Log.i(TAG, "[RING] stop callId=$activeCallId quiet=$quietRing")
        activeCallId = null
        quietRing = false
    }
}
