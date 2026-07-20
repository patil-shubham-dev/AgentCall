import Foundation

enum ApiError: LocalizedError {
    case invalidURL
    case unauthorized
    case notFound
    case rateLimited
    case serverError(String)
    case decodingError(Error)
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .unauthorized: return "Invalid credentials"
        case .notFound: return "Resource not found"
        case .rateLimited: return "Too many requests"
        case .serverError(let msg): return msg
        case .decodingError(let e): return "Data error: \(e.localizedDescription)"
        case .networkError(let e): return e.localizedDescription
        case .invalidURL: return "Invalid URL"
        }
    }
}

actor ApiClient {
    static let shared = ApiClient()
    private let session: URLSession
    private let baseURL = "https://api.agentcall.example.com/api/v1"
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        self.session = URLSession(configuration: config)
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    private func request<T: Decodable>(
        _ method: String,
        _ path: String,
        body: (any Encodable)? = nil,
        authenticated: Bool = true
    ) async throws -> T {
        guard let url = URL(string: "\(baseURL)\(path)") else { throw ApiError.invalidURL }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if authenticated, let token = await TokenManager.shared.accessToken {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            req.httpBody = try encoder.encode(AnyEncodable(body))
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw ApiError.networkError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.serverError("Invalid response")
        }

        switch httpResponse.statusCode {
        case 200...299:
            do {
                return try decoder.decode(T.self, from: data)
            } catch {
                throw ApiError.decodingError(error)
            }
        case 401: throw ApiError.unauthorized
        case 404: throw ApiError.notFound
        case 429: throw ApiError.rateLimited
        default:
            let body = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw ApiError.serverError(body)
        }
    }

    // MARK: - Auth
    func login(email: String) async throws -> AuthResponse {
        try await request("POST", "/auth/login", body: LoginRequest(email: email, display_name: nil))
    }

    func refresh(token: String) async throws -> AuthResponse {
        try await request("POST", "/auth/refresh", body: RefreshRequest(refresh_token: token))
    }

    // MARK: - Devices
    func registerDevice(pushToken: String?, deviceName: String?) async throws -> DeviceRegisterResponse {
        try await request("POST", "/devices/register",
            body: DeviceRegisterRequest(platform: "ios", push_token: pushToken, device_name: deviceName))
    }

    func removeDevice(_ deviceId: String) async throws {
        let _: EmptyResponse = try await request("DELETE", "/devices/\(deviceId)")
    }

    // MARK: - Presence
    func getUserPresence(_ userId: String) async throws -> PresenceResponse {
        try await request("GET", "/users/\(userId)/presence")
    }

    func sendHeartbeat(deviceId: String) async throws {
        let _: EmptyResponse = try await request("POST", "/presence/heartbeat",
            body: HeartbeatRequest(device_id: deviceId, platform: "ios"))
    }

    // MARK: - Calls
    func getCallHistory() async throws -> CallHistoryResponse {
        try await request("GET", "/calls")
    }

    func getCall(_ callId: String) async throws -> CallResponse {
        try await request("GET", "/calls/\(callId)")
    }

    // MARK: - TURN
    func getTurnCredentials() async throws -> TurnCredentialsResponse {
        try await request("GET", "/turn/credentials")
    }
}

private struct EmptyResponse: Decodable {}

// Helper to box any Encodable
private struct AnyEncodable: Encodable {
    private let value: any Encodable
    init(_ value: any Encodable) { self.value = value }
    func encode(to encoder: any Encoder) throws {
        try value.encode(to: encoder)
    }
}
