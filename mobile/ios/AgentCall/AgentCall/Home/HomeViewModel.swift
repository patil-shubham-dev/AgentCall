import Foundation

@MainActor
class HomeViewModel: ObservableObject {
    @Published var calls: [CallResponse] = []
    @Published var presence: PresenceResponse?
    @Published var isLoading = false
    @Published var error: String?

    private let api = ApiClient.shared
    private let tokenManager = TokenManager.shared

    func loadCallHistory() {
        Task {
            isLoading = true
            error = nil
            do {
                let response = try await api.getCallHistory()
                calls = response.calls
            } catch {
                self.error = error.localizedDescription
            }
            isLoading = false
        }
    }

    func refreshPresence() {
        Task {
            guard let userId = await tokenManager.userId else { return }
            do {
                presence = try await api.getUserPresence(userId)
            } catch {}
        }
    }
}
