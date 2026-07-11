import Foundation
import Security

actor TokenManager {
    static let shared = TokenManager()

    private let service = "com.agentcall.app"
    private let accessTokenKey = "access_token"
    private let refreshTokenKey = "refresh_token"
    private let userIdKey = "user_id"
    private let deviceIdKey = "device_id"

    private init() {}

    var accessToken: String? {
        get { read(key: accessTokenKey) }
        set { write(key: accessTokenKey, value: newValue) }
    }

    var refreshToken: String? {
        get { read(key: refreshTokenKey) }
        set { write(key: refreshTokenKey, value: newValue) }
    }

    var userId: String? {
        get { read(key: userIdKey) }
        set { write(key: userIdKey, value: newValue) }
    }

    var deviceId: String? {
        get { read(key: deviceIdKey) }
        set { write(key: deviceIdKey, value: newValue) }
    }

    var isLoggedIn: Bool {
        accessToken != nil
    }

    func clear() {
        delete(key: accessTokenKey)
        delete(key: refreshTokenKey)
        delete(key: userIdKey)
        delete(key: deviceIdKey)
    }

    private func write(key: String, value: String?) {
        guard let value = value else { return delete(key: key) }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: value.data(using: .utf8)!,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    private func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
