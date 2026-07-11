import Foundation
import WebRTC

actor WebRTCClient {
    static let shared = WebRTCClient()
    private var factory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var audioSource: RTCAudioSource?

    // Delegate callbacks
    private var onIceCandidate: ((RTCIceCandidate) -> Void)?
    private var onIceConnectionChange: ((RTCIceConnectionState) -> Void)?
    private var onConnectionStateChange: ((RTCPeerConnectionState) -> Void)?

    private init() {}

    func initialize() {
        RTCInitializeSSL()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        factory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)
    }

    func createPeerConnection(
        turnUsername: String,
        turnCredential: String,
        stunHost: String = "turn.agentcall.example.com:3478",
        turnHost: String = "turn.agentcall.example.com:5349",
        onIceCandidate: @escaping (RTCIceCandidate) -> Void = { _ in },
        onIceConnectionChange: @escaping (RTCIceConnectionState) -> Void = { _ in },
        onConnectionStateChange: @escaping (RTCPeerConnectionState) -> Void = { _ in }
    ) -> RTCPeerConnection? {
        self.onIceCandidate = onIceCandidate
        self.onIceConnectionChange = onIceConnectionChange
        self.onConnectionStateChange = onConnectionStateChange

        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceCandidatePoolSize = 10
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.continualGatheringPolicy = .gatherContinually

        let stun = RTCIceServer(urlStrings: ["stun:\(stunHost)"])
        let turn = RTCIceServer(
            urlStrings: ["turn:\(turnHost)"],
            username: turnUsername,
            credential: turnCredential
        )
        config.iceServers = [stun, turn]

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = factory?.peerConnection(
            with: config,
            constraints: constraints,
            delegate: PeerConnectionDelegateProxy(
                onIceCandidate: { [weak self] candidate in
                    Task { await self?.onIceCandidate?(candidate) }
                },
                onIceConnectionChange: { [weak self] state in
                    Task { await self?.onIceConnectionChange?(state) }
                },
                onConnectionStateChange: { [weak self] state in
                    Task { await self?.onConnectionStateChange?(state) }
                }
            )
        )
        return peerConnection
    }

    func startAudio() -> RTCAudioTrack? {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "googEchoCancellation": "true",
                "googAutoGainControl": "true",
                "googNoiseSuppression": "true",
            ],
            optionalConstraints: nil
        )
        audioSource = factory?.audioSource(with: constraints)
        audioTrack = factory?.audioTrack(with: audioSource!, trackId: "audio0")
        peerConnection?.add(audioTrack!, streamIds: ["stream0"])
        return audioTrack
    }

    func createOffer() async throws -> RTCSessionDescription {
        return try await withCheckedThrowingContinuation { continuation in
            let constraints = RTCMediaConstraints(
                mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "false"],
                optionalConstraints: nil
            )
            peerConnection?.offer(for: constraints) { sdp, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let sdp = sdp {
                    self.peerConnection?.setLocalDescription(sdp) { error in
                        if let error = error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: sdp)
                        }
                    }
                }
            }
        }
    }

    func createAnswer() async throws -> RTCSessionDescription {
        return try await withCheckedThrowingContinuation { continuation in
            let constraints = RTCMediaConstraints(
                mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "false"],
                optionalConstraints: nil
            )
            peerConnection?.answer(for: constraints) { sdp, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let sdp = sdp {
                    self.peerConnection?.setLocalDescription(sdp) { error in
                        if let error = error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: sdp)
                        }
                    }
                }
            }
        }
    }

    func setRemoteDescription(_ sdp: RTCSessionDescription) async throws {
        return try await withCheckedThrowingContinuation { continuation in
            peerConnection?.setRemoteDescription(sdp) { error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func addIceCandidate(_ candidate: RTCIceCandidate) {
        peerConnection?.add(candidate)
    }

    func mute(_ muted: Bool) {
        audioTrack?.isEnabled = !muted
    }

    func dispose() {
        peerConnection?.close()
        peerConnection = nil
        audioTrack = nil
        audioSource = nil
    }
}

// MARK: - RTCPeerConnectionDelegate Proxy

private class PeerConnectionDelegateProxy: NSObject, RTCPeerConnectionDelegate {
    private let onIceCandidate: (RTCIceCandidate) -> Void
    private let onIceConnectionChange: (RTCIceConnectionState) -> Void
    private let onConnectionStateChange: (RTCPeerConnectionState) -> Void

    init(
        onIceCandidate: @escaping (RTCIceCandidate) -> Void,
        onIceConnectionChange: @escaping (RTCIceConnectionState) -> Void,
        onConnectionStateChange: @escaping (RTCPeerConnectionState) -> Void
    ) {
        self.onIceCandidate = onIceCandidate
        self.onIceConnectionChange = onIceConnectionChange
        self.onConnectionStateChange = onConnectionStateChange
        super.init()
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange state: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        onIceConnectionChange(newState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        onConnectionStateChange(newState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onIceCandidate(candidate)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) -> Bool { return false }

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}
}
