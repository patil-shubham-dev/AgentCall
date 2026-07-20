import Foundation
import CallKit
import AVFoundation

class CallService: NSObject {
    static let shared = CallService()
    private let callController = CXCallController()
    private let webRTC = WebRTCClient.shared
    private let signaling = SignalingClient.shared

    private var currentCallId: String?
    private var audioSessionConfigured = false

    private override init() {
        super.init()
    }

    // MARK: - Outgoing Call (Agent → Human)
    func startCall(callId: String) {
        currentCallId = callId
        configureAudioSession()
        webRTC.initialize()

        Task {
            do {
                let creds = try await ApiClient.shared.getTurnCredentials()
                guard let pc = await webRTC.createPeerConnection(
                    turnUsername: creds.username,
                    turnCredential: creds.credential
                ) else { return }
                _ = await webRTC.startAudio()
                let offer = try await webRTC.createOffer()
                signaling.send(type: "offer", payload: ["sdp": offer.sdp.description, "type": "offer"])

                await signaling.connect(callId: callId)

                for await event in await signaling.events {
                    switch event {
                    case .answerReceived(let sdp):
                        let session = RTCSessionDescription(type: .answer, sdp: sdp)
                        try await webRTC.setRemoteDescription(session)
                    case .iceCandidateReceived(let candidate, let sdpMid, let sdpMLineIndex):
                        let ice = RTCIceCandidate(sdp: candidate, sdpMLineIndex: Int32(sdpMLineIndex), sdpMid: sdpMid)
                        await webRTC.addIceCandidate(ice)
                    case .disconnected:
                        endCall()
                    default: break
                    }
                }
            } catch {
                endCall()
            }
        }
    }

    // MARK: - Incoming Call (Human receives)
    func acceptCall(callId: String) {
        currentCallId = callId
        configureAudioSession()
        webRTC.initialize()

        Task {
            do {
                let creds = try await ApiClient.shared.getTurnCredentials()
                guard let pc = await webRTC.createPeerConnection(
                    turnUsername: creds.username,
                    turnCredential: creds.credential
                ) else { return }
                _ = await webRTC.startAudio()

                await signaling.connect(callId: callId)

                for await event in await signaling.events {
                    switch event {
                    case .offerReceived(let sdp, let type):
                        let session = RTCSessionDescription(type: .offer, sdp: sdp)
                        try await webRTC.setRemoteDescription(session)
                        let answer = try await webRTC.createAnswer()
                        signaling.send(type: "answer", payload: ["sdp": answer.sdp.description, "type": "answer"])
                    case .iceCandidateReceived(let candidate, let sdpMid, let sdpMLineIndex):
                        let ice = RTCIceCandidate(sdp: candidate, sdpMLineIndex: Int32(sdpMLineIndex), sdpMid: sdpMid)
                        await webRTC.addIceCandidate(ice)
                    case .disconnected:
                        endCall()
                    default: break
                    }
                }
            } catch {
                endCall()
            }
        }
    }

    func endCall() {
        Task {
            await webRTC.dispose()
            await signaling.disconnect()
        }
        currentCallId = nil
    }

    func toggleMute() {
        Task { await webRTC.mute(!(await UncheckedSendableBox(webRTC).value.audioTrack?.isEnabled ?? true)) }
        // Simplified: real impl would track mute state
    }

    private func configureAudioSession() {
        guard !audioSessionConfigured else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
            try session.setActive(true)
            audioSessionConfigured = true
        } catch {
            print("Failed to configure audio: \(error)")
        }
    }
}

// Helper for actor property access
private struct UncheckedSendableBox<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
