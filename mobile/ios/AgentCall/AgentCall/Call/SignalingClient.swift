import Foundation

enum SignalingEvent {
    case roomJoined
    case offerReceived(sdp: String, type: String)
    case answerReceived(sdp: String)
    case iceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int)
    case participantJoined(userId: String, role: String)
    case participantLeft(userId: String)
    case muteChanged(userId: String, muted: Bool)
    case error(code: String, message: String)
    case disconnected
}

actor SignalingClient {
    static let shared = SignalingClient()
    private var webSocket: URLSessionWebSocketTask?
    private let session = URLSession(configuration: .default)
    private var continuations: [AsyncStream<SignalingEvent>.Continuation] = []

    private init() {}

    var events: AsyncStream<SignalingEvent> {
        AsyncStream { continuation in
            continuations.append(continuation)
            continuation.onTermination = { [weak self] _ in
                Task { [weak self] in
                    await self?.continuations.removeAll { $0 === continuation }
                }
            }
        }
    }

    func connect(callId: String) async {
        guard let token = await TokenManager.shared.accessToken else {
            emit(.error(code: "AUTH", message: "No access token"))
            return
        }
        guard let url = URL(string: "wss://api.agentcall.example.com/ws?token=\(token)&call_id=\(callId)") else {
            emit(.error(code: "URL", message: "Invalid URL"))
            return
        }
        webSocket = session.webSocketTask(with: url)
        webSocket?.resume()
        receive()
    }

    private func receive() {
        webSocket?.receive { [weak self] result in
            Task { [weak self] in
                switch result {
                case .success(let message):
                    if case .string(let text) = message {
                        await self?.handleMessage(text)
                    }
                    await self?.receive()
                case .failure:
                    self?.emit(.disconnected)
                }
            }
        }
    }

    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String,
              let payload = json["payload"] as? [String: Any] else {
            emit(.error(code: "PARSE", message: "Invalid message"))
            return
        }

        switch type {
        case "offer":
            if let sdp = payload["sdp"] as? String, let sdpType = payload["type"] as? String {
                emit(.offerReceived(sdp: sdp, type: sdpType))
            }
        case "answer":
            if let sdp = payload["sdp"] as? String {
                emit(.answerReceived(sdp: sdp))
            }
        case "ice_candidate":
            if let candidate = payload["candidate"] as? String,
               let sdpMid = payload["sdpMid"] as? String,
               let sdpMLineIndex = payload["sdpMLineIndex"] as? Int {
                emit(.iceCandidateReceived(candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex))
            }
        case "participant_joined":
            if let userId = payload["user_id"] as? String {
                emit(.participantJoined(userId: userId, role: payload["role"] as? String ?? "unknown"))
            }
        case "participant_left":
            if let userId = payload["user_id"] as? String {
                emit(.participantLeft(userId: userId))
            }
        case "mute_changed":
            if let userId = payload["user_id"] as? String {
                emit(.muteChanged(userId: userId, muted: payload["muted"] as? Bool ?? false))
            }
        case "error":
            emit(.error(code: payload["code"] as? String ?? "UNKNOWN", message: payload["message"] as? String ?? ""))
        default:
            break
        }
    }

    func send(type: String, payload: [String: Any]) {
        var json: [String: Any] = [
            "type": type,
            "payload": payload,
            "timestamp": ISO8601DateFormatter().string(from: Date()),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let text = String(data: data, encoding: .utf8) else { return }
        webSocket?.send(.string(text)) { _ in }
    }

    func disconnect() {
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
    }

    private func emit(_ event: SignalingEvent) {
        for c in continuations { c.yield(event) }
    }
}
