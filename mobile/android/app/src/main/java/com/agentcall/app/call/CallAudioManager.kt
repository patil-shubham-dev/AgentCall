package com.agentcall.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal audio-routing wrapper for the voice-first call surface.
 *
 * Owns the audio-focus lifecycle of a call (TTS speech is transient by
 * nature) and the speakerphone toggle. Platform APIs only — no new
 * dependencies. Speaker state is intentionally not persisted across calls:
 * each call starts on the earpiece route.
 */
@Singleton
class CallAudioManager @Inject constructor(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    fun requestFocus() {
        try {
            if (hasFocus) return
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                hasFocus = change == AudioManager.AUDIOFOCUS_GAIN
                Log.d(TAG, "[AUDIO] focus change=$change hasFocus=$hasFocus")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focusRequest = request
                hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                hasFocus = audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            Log.d(TAG, "[AUDIO] focus requested, granted=$hasFocus")
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO] focus request failed", e)
        }
    }

    fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        focusRequest = null
        hasFocus = false
    }

    val isSpeakerOn: Boolean
        get() = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                communicationOutputs()
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { it == audioManager.communicationDevice }
                    ?: false
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn
            }
        } catch (_: Exception) {
            false
        }

    fun setSpeakerphone(on: Boolean): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val speaker = communicationOutputs()
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker == null) return isSpeakerOn
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    val fallback = communicationOutputs().firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                    if (fallback != null) {
                        audioManager.setCommunicationDevice(fallback)
                    } else {
                        audioManager.clearCommunicationDevice()
                    }
                }
                Log.d(TAG, "[AUDIO] speaker=$on")
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
                Log.d(TAG, "[AUDIO] speaker=$on")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO] speaker set failed", e)
        }
        return isSpeakerOn
    }

    fun toggleSpeaker(): Boolean {
        return setSpeakerphone(!isSpeakerOn)
    }

    private fun communicationOutputs(): Array<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    companion object {
        private const val TAG = "AgentCall"
    }
}