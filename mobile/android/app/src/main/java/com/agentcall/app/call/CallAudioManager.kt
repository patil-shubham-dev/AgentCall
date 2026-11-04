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
 * nature) and the communication-device route (earpiece / loudspeaker /
 * Bluetooth / wired). Platform APIs only — no new dependencies. Speaker
 * state is intentionally not persisted across calls: each call starts on
 * the earpiece route (CallService forces it at answer).
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

    // ── Output routes ────────────────────────────────────────────────
    // The picker shows only routes the platform actually reports. Pre-S
    // builds can't select a specific communication device (only the
    // speakerphone flag), so Bluetooth/wired rows are hidden there instead
    // of promising a selection that does nothing.

    /** Logical output route, as shown in the Audio sheet. */
    data class AudioRoute(
        val id: String,
        val name: String,
        val kind: RouteKind,
        val device: AudioDeviceInfo?,
    )

    enum class RouteKind { SPEAKER, EARPIECE, BLUETOOTH, WIRED }

    fun availableRoutes(): List<AudioRoute> {
        val speaker = AudioRoute(
            id = ROUTE_SPEAKER,
            name = "Loudspeaker",
            kind = RouteKind.SPEAKER,
            device = null,
        )
        val earpiece = AudioRoute(
            id = ROUTE_EARPIECE,
            name = "Phone earpiece",
            kind = RouteKind.EARPIECE,
            device = null,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return listOf(speaker, earpiece)
        }
        return try {
            val routes = mutableListOf(speaker, earpiece)
            communicationOutputs().forEach { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    -> routes.add(
                        AudioRoute(
                            id = routeIdFor(d),
                            name = d.productName?.toString() ?: "Bluetooth device",
                            kind = RouteKind.BLUETOOTH,
                            device = d,
                        ),
                    )
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    -> routes.add(
                        AudioRoute(
                            id = routeIdFor(d),
                            name = d.productName?.toString() ?: "Wired headset",
                            kind = RouteKind.WIRED,
                            device = d,
                        ),
                    )
                }
            }
            routes
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO] route inventory failed", e)
            listOf(speaker, earpiece)
        }
    }

    /** Selects a route from [availableRoutes]; returns the resulting speaker state. */
    fun selectRoute(route: AudioRoute): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target: AudioDeviceInfo? = when (route.kind) {
                    RouteKind.SPEAKER -> speakerDevice()
                    RouteKind.EARPIECE -> earpieceOrFallback()
                    else -> route.device
                }
                if (target == null) {
                    Log.w(TAG, "[AUDIO] selectRoute: no device for " + route.id)
                    return isSpeakerOn
                }
                audioManager.setCommunicationDevice(target)
                Log.d(TAG, "[AUDIO] route=" + route.id)
            } else {
                // Pre-S: only the speakerphone flag is addressable.
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = route.kind == RouteKind.SPEAKER
            }
            isSpeakerOn
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO] route set failed", e)
            isSpeakerOn
        }
    }

    /** Logical id of the route audio currently flows through, for sheet highlighting. */
    fun currentRouteId(): String {
        return try {
            if (isSpeakerOn) return ROUTE_SPEAKER
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ROUTE_EARPIECE
            val current = audioManager.communicationDevice
            when (current?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> ROUTE_SPEAKER
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ROUTE_EARPIECE
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID,
                -> routeIdFor(current)
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                -> routeIdFor(current)
                else -> ROUTE_EARPIECE
            }
        } catch (_: Exception) {
            ROUTE_EARPIECE
        }
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
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val speaker = speakerDevice()
                    if (speaker == null) return isSpeakerOn
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    earpieceOrFallback()?.let {
                        audioManager.setCommunicationDevice(it)
                    } ?: audioManager.clearCommunicationDevice()
                }
                Log.d(TAG, "[AUDIO] speaker=$on")
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
                Log.d(TAG, "[AUDIO] speaker=$on")
            }
            isSpeakerOn
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO] speaker set failed", e)
            isSpeakerOn
        }
    }

    fun toggleSpeaker(): Boolean {
        return setSpeakerphone(!isSpeakerOn)
    }

    private fun speakerDevice(): AudioDeviceInfo? =
        communicationOutputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Earpiece first; wired/BT accepted as the non-speaker fallback route. */
    private fun earpieceOrFallback(): AudioDeviceInfo? =
        communicationOutputs().firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }

    private fun communicationOutputs(): Array<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    private fun routeIdFor(d: AudioDeviceInfo): String = d.type.toString() + "-" + d.id

    companion object {
        private const val TAG = "AgentCall"
        const val ROUTE_SPEAKER = "speaker"
        const val ROUTE_EARPIECE = "earpiece"
    }
}
