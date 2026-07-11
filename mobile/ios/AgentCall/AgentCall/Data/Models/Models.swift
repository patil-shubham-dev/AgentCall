import Foundation

// MARK: - Auth
struct LoginRequest: Encodable {
    let email: String
    let display_name: String?
}

struct AuthResponse: Decodable {
    let access_token: String
    let refresh_token: String
    let user_id: String
}

struct RefreshRequest: Encodable {
    let refresh_token: String
}

// MARK: - Devices
struct DeviceRegisterRequest: Encodable {
    let platform: String
    let push_token: String?
    let device_name: String?
}

struct DeviceRegisterResponse: Decodable {
    let device_id: String
    let status: String
}

// MARK: - Presence
struct PresenceResponse: Decodable {
    let user_id: String
    let status: String
    let last_seen: String?
    let dnd: Bool
    let devices: [DeviceInfo]
}

struct DeviceInfo: Decodable {
    let platform: String
    let push_enabled: Bool
}

struct HeartbeatRequest: Encodable {
    let device_id: String
    let platform: String
}

// MARK: - Calls
struct CreateCallRequest: Encodable {
    let user_id: String
    let agent_id: String
    let context: CallContext
    let priority: String
    let timeout_seconds: Int
}

struct CallContext: Encodable {
    let task_id: String?
    let reason: String
    let summary: String
    let options: [String]?
}

struct CreateCallResponse: Decodable {
    let call_id: String
    let status: String
    let expires_at: String?
}

struct CallResponse: Decodable, Identifiable {
    var id: String { call_id }
    let call_id: String
    let status: String
    let user_id: String?
    let agent_id: String?
    let priority: String?
    let context_summary: String?
    let created_at: String?
    let connected_at: String?
    let ended_at: String?
    let duration_seconds: Int?
    let result: CallResult?
}

struct CallResult: Decodable {
    let transcript_summary: String?
    let user_response: String?
    let decision: String?
    let selected_option: String?
    let sentiment: String?
    let action_items: [String]?
}

struct CallHistoryResponse: Decodable {
    let calls: [CallResponse]
}

// MARK: - TURN
struct TurnCredentialsResponse: Decodable {
    let username: String
    let credential: String
    let ttl: Int
}

// MARK: - API Keys
struct ApiKeyInfo: Decodable, Identifiable {
    var id: String { self.id }
    let name: String
    let key_prefix: String
}

struct ApiKeyListResponse: Decodable {
    let api_keys: [ApiKeyInfo]
}

struct CreateApiKeyResponse: Decodable {
    let api_key: String
    let name: String
    let key_prefix: String
}

// MARK: - Push
struct PushPayload: Encodable {
    let type: String
    let call_id: String?
    let caller_name: String?
    let context_summary: String?
    let priority: String?
}
