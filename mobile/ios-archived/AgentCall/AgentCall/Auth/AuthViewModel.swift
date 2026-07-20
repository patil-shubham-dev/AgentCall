import Foundation
import Combine

@MainActor
class AuthViewModel: ObservableObject {
    @Published var isLoggedIn = false
    @Published var isLoading = false
    @Published var error: String?

    private let api = ApiClient.shared
    private let tokenManager = TokenManager.shared

    init() {
        isLoggedIn = tokenManager.isLoggedIn
    }

    func login(email: String) async {
        isLoading = true
        error = nil
        do {
            let response = try await api.login(email: email)
            await tokenManager.accessToken = response.access_token
            await tokenManager.refreshToken = response.refresh_token
            await tokenManager.userId = response.user_id
            isLoggedIn = true
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func logout() {
        Task { await tokenManager.clear() }
        isLoggedIn = false
    }
}
