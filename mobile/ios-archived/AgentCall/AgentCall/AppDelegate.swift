import UIKit
import PushKit

class AppDelegate: NSObject, UIApplicationDelegate {
    private let pushHandler = PushHandler()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        registerForVoIPPush()
        return true
    }

    private func registerForVoIPPush() {
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = pushHandler
        registry.desiredPushTypes = [.voIP]
    }
}
