import Foundation
import PushKit
import UIKit
import CallKit
import AVFoundation

class PushHandler: NSObject, PKPushRegistryDelegate {
    private let callService = CallService.shared
    private let tokenManager = TokenManager.shared

    // Called when the device registers for VoIP push
    func pushRegistry(_ registry: PKPushRegistry, didUpdate credentials: PKPushCredentials, for type: PKPushType) {
        let token = credentials.token.map { String(format: "%02x", $0) }.joined()
        Task {
            // Store token for later registration with server
            await savePushToken(token)
        }
    }

    // Called when an incoming VoIP push is received
    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        defer { completion() }

        let data = payload.dictionaryPayload
        guard let callId = data["call_id"] as? String else { return }
        let callerName = data["caller_name"] as? String ?? "AI Agent"
        let contextSummary = data["context_summary"] as? String ?? ""

        // Report to CallKit so the system shows the incoming call UI
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerName)
        update.localizedCallerName = callerName
        update.hasVideo = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsHolding = false
        update.supportsDTMF = false

        let provider = CXProvider(configuration: providerConfiguration())
        provider.setDelegate(self, queue: nil)

        let uuid = UUID(uuidString: callId) ?? UUID()
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("CallKit error: \(error)")
            }
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        Task { await clearPushToken() }
    }

    private func providerConfiguration() -> CXProviderConfiguration {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        config.iconTemplateImageData = UIImage(systemName: "phone.fill")?.pngData()
        config.ringtoneSound = "call.caf"
        return config
    }

    private func savePushToken(_ token: String) async {
        UserDefaults.standard.set(token, forKey: "voip_push_token")
        // Register with backend if logged in
        if await tokenManager.isLoggedIn {
            let deviceName = UIDevice.current.name
            _ = try? await ApiClient.shared.registerDevice(pushToken: token, deviceName: deviceName)
        }
    }

    private func clearPushToken() async {
        UserDefaults.standard.removeObject(forKey: "voip_push_token")
    }
}

extension PushHandler: CXProviderDelegate {
    func providerDidReset(_ provider: CXProvider) {}

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        // User answered — start WebRTC
        callService.acceptCall(callId: action.callUUID.uuidString)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        // User declined or ended
        callService.endCall()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        callService.toggleMute()
        action.fulfill()
    }
}
