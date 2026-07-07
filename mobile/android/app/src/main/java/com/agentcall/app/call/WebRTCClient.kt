package com.agentcall.app.call

import android.content.Context
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRTCClient @Inject constructor(
    private val context: Context
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var eglBase: EglBase? = null

    private var onIceCandidate: ((IceCandidate) -> Unit)? = null
    private var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    private var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val options = PeerConnectionFactory.Options()
        options.networkIgnoreMask = 0

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        onIceCandidate: (IceCandidate) -> Unit,
        onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit,
        onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit
    ): PeerConnection? {
        this.onIceCandidate = onIceCandidate
        this.onIceConnectionChange = onIceConnectionChange
        this.onConnectionStateChange = onConnectionStateChange

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 10
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceConnectionState = PeerConnection.IceConnectionState.CHECKING
            keyType = PeerConnection.KeyType.ECDSA
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onIceConnectionChange(state)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onAddTrack(track: RtpReceiver, streams: Array<MediaStream>) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(channel: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onIceConnectionChange(state)
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                onConnectionStateChange(state)
            }
        })

        return peerConnection
    }

    fun startAudio(): AudioTrack? {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(constraints)
        audioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        return audioTrack
    }

    fun createOffer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(sdpObserver, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createAnswer(sdpObserver, constraints)
    }

    fun setRemoteDescription(sdpObserver: SdpObserver, sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(sdpObserver, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun mute(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    fun dispose() {
        peerConnection?.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        peerConnection = null
        audioTrack = null
        audioSource = null
        peerConnectionFactory = null
        eglBase = null
    }
}
