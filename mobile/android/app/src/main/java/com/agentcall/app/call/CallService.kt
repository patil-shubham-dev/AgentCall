package com.agentcall.app.call

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.agentcall.app.AgentCallApp
import com.agentcall.app.R
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.TokenManager
import com.agentcall.app.data.model.TurnCredentialsResponse
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.webrtc.*
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var webRTCClient: WebRTCClient
    @Inject lateinit var signalingClient: SignalingClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var callId: String? = null
    private var isMuted = false
    private var isSpeakerOn = false
    private val api: ApiService = ApiClient.create()

    override fun onCreate() {
        super.onCreate()
        webRTCClient.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                callId = id
                startForeground(NOTIFICATION_ID, createOngoingCallNotification())
                acquireWakeLock()
                startCall(id)
            }
            ACTION_MUTE -> toggleMute()
            ACTION_END_CALL -> {
                endCall()
                stopSelf()
            }
            ACTION_ACCEPT_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                callId = id
                startForeground(NOTIFICATION_ID, createOngoingCallNotification())
                acquireWakeLock()
                acceptCall(id)
            }
        }
        return START_STICKY
    }

    private fun startCall(callId: String) {
        scope.launch {
            try {
                val turnCreds = getTurnCredentials()
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:${turnCreds.stunHost}")
                        .createIceServer(),
                    PeerConnection.IceServer.builder("turn:${turnCreds.turnHost}")
                        .setUsername(turnCreds.username)
                        .setPassword(turnCreds.credential)
                        .createIceServer()
                )

                val pc = webRTCClient.createPeerConnection(
                    iceServers,
                    onIceCandidate = { candidate ->
                        signalingClient.sendIceCandidate(candidate)
                    },
                    onIceConnectionChange = { /* handle connection state */ },
                    onConnectionStateChange = { state ->
                        if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                            stopSelf()
                        }
                    }
                ) ?: return@launch

                val audioTrack = webRTCClient.startAudio() ?: return@launch
                pc.addTrack(audioTrack)

                webRTCClient.createOffer(object : CustomSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) return@launch
                        pc.setLocalDescription(object : CustomSdpObserver() {
                            override fun onSetSuccess() {
                                signalingClient.sendOffer(sdp)
                            }
                        }, sdp)
                    }
                })

                signalingClient.connect(callId)

                launch {
                    signalingClient.events.collect { event ->
                        when (event) {
                            is SignalingEvent.AnswerReceived -> {
                                webRTCClient.setRemoteDescription(
                                    object : CustomSdpObserver() {},
                                    event.sdp
                                )
                            }
                            is SignalingEvent.IceCandidateReceived -> {
                                webRTCClient.addIceCandidate(event.candidate)
                            }
                            is SignalingEvent.Disconnected -> {
                                stopSelf()
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                stopSelf()
            }
        }
    }

    private fun acceptCall(callId: String) {
        scope.launch {
            try {
                val turnCreds = getTurnCredentials()
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:${turnCreds.stunHost}")
                        .createIceServer(),
                    PeerConnection.IceServer.builder("turn:${turnCreds.turnHost}")
                        .setUsername(turnCreds.username)
                        .setPassword(turnCreds.credential)
                        .createIceServer()
                )

                val pc = webRTCClient.createPeerConnection(
                    iceServers,
                    onIceCandidate = { candidate ->
                        signalingClient.sendIceCandidate(candidate)
                    },
                    onIceConnectionChange = { /* handle state */ },
                    onConnectionStateChange = { state ->
                        if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                            stopSelf()
                        }
                    }
                ) ?: return@launch

                val audioTrack = webRTCClient.startAudio() ?: return@launch
                pc.addTrack(audioTrack)

                signalingClient.connect(callId)

                launch {
                    signalingClient.events.collect { event ->
                        when (event) {
                            is SignalingEvent.OfferReceived -> {
                                pc.setRemoteDescription(object : CustomSdpObserver() {}, event.sdp)
                                webRTCClient.createAnswer(object : CustomSdpObserver() {
                                    override fun onCreateSuccess(sdp: SessionDescription?) {
                                        if (sdp == null) return@launch
                                        pc.setLocalDescription(object : CustomSdpObserver() {
                                            override fun onSetSuccess() {
                                                signalingClient.sendAnswer(sdp)
                                            }
                                        }, sdp)
                                    }
                                })
                            }
                            is SignalingEvent.IceCandidateReceived -> {
                                webRTCClient.addIceCandidate(event.candidate)
                            }
                            is SignalingEvent.Disconnected -> stopSelf()
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                stopSelf()
            }
        }
    }

    private suspend fun getTurnCredentials(): TurnCreds {
        val response = api.getTurnCredentials()
        return TurnCreds(
            stunHost = "turn.agentcall.example.com:3478",
            turnHost = "turn.agentcall.example.com:5349",
            username = response.username,
            credential = response.credential
        )
    }

    private fun toggleMute() {
        isMuted = !isMuted
        webRTCClient.mute(isMuted)
        signalingClient.sendMuteState(isMuted)
        updateNotification()
    }

    private fun endCall() {
        signalingClient.sendHangup()
        signalingClient.disconnect()
        webRTCClient.dispose()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InternetCalling:CallLock"
        ).apply {
            acquire(30_000)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun createOngoingCallNotification(): Notification {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CallService::class.java).setAction(ACTION_END_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AgentCallApp.CHANNEL_ONGOING_CALL)
            .setContentTitle("AI Call in Progress")
            .setContentText(if (isMuted) "Muted" else "Connected")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isMuted) android.R.drawable.ic_lock_lock else android.R.drawable.ic_lock_lock,
                if (isMuted) "Unmute" else "Mute",
                muteIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", endIntent)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createOngoingCallNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        endCall()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_CALL = "com.internetcalling.action.START_CALL"
        const val ACTION_ACCEPT_CALL = "com.internetcalling.action.ACCEPT_CALL"
        const val ACTION_MUTE = "com.internetcalling.action.MUTE"
        const val ACTION_END_CALL = "com.internetcalling.action.END_CALL"
        const val EXTRA_CALL_ID = "call_id"
        private const val NOTIFICATION_ID = 1001
    }

    data class TurnCreds(
        val stunHost: String,
        val turnHost: String,
        val username: String,
        val credential: String
    )
}

abstract class CustomSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
