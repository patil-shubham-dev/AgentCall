# Mobile App Technical Specification

## AgentCall MCP

**Version:** 1.0
**Status:** Draft

---

## 1. Overview

Native mobile applications for Android (Kotlin) and iOS (Swift) that receive and manage AI-initiated voice calls via WebRTC. Both apps follow the same architecture with platform-specific implementations for push, background calling, and system integration.

---

## 2. Shared Architecture

```
┌─────────────────────────────────────┐
│          UI Layer (Jetpack/SwiftUI) │
│  - Call Screen                      │
│  - Notification Handling            │
│  - Settings                         │
├─────────────────────────────────────┤
│       Presentation / ViewModel      │
│  - CallViewModel                    │
│  - PresenceViewModel                │
│  - AuthViewModel                    │
├─────────────────────────────────────┤
│         Service Layer               │
│  - WebRTC Service                   │
│  - Signaling Client (WebSocket)     │
│  - HTTP API Client                  │
│  - Push Token Registration          │
├─────────────────────────────────────┤
│      Platform Integration Layer     │
│  - Background Service               │
│  - Audio Manager                    │
│  - Notification Manager             │
│  - System UI Integration            │
└─────────────────────────────────────┘
```

---

## 3. Android Implementation (Kotlin)

### 3.1 Tech Stack

| Component | Library | Reason |
|-----------|---------|--------|
| UI | Jetpack Compose | Modern declarative UI |
| Navigation | Compose Navigation | Type-safe nav |
| WebRTC | Google WebRTC (org.webrtc:google-webrtc) | Official Google support |
| WebSocket | OkHttp WebSocket | Well-tested, async |
| HTTP | Retrofit + OkHttp | Industry standard |
| DI | Hilt | Official Android DI |
| Push | Firebase Cloud Messaging | Required for Android |
| Background | Foreground Service | Persistent call handling |
| Audio | AudioManager + WebRTC audio device | Platform audio routing |
| JSON | Kotlinx Serialization | First-class Kotlin support |

### 3.2 Core Components

#### CallService (Foreground Service)

```kotlin
class CallService : Service() {
    // Lifecycle: Runs as foreground service with high-priority notification
    // Manages: WebRTC peer connection, audio routing, microphone
    // Handles: Incoming calls, outgoing calls, call disconnect
    // Notification: Ongoing call notification with mute/end actions

    private val webRTCClient: WebRTCClient
    private val signalingClient: SignalingClient
    private val audioManager: CallAudioManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create ongoing notification (channel: "ongoing_calls")
        // Initialize WebRTC PeerConnectionFactory
        // Connect to signaling server
        // Start audio capture
        return START_STICKY
    }
}
```

#### WebRTCClient

```kotlin
class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection?
    private var audioTrack: AudioTrack?

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        observer: PeerConnection.Observer
    ): PeerConnection

    fun startAudio(): AudioTrack
    fun mute(muted: Boolean)
    fun setSpeakerphoneOn(on: Boolean)

    // Audio processing (all from WebRTC stack):
    // - Acoustic Echo Cancellation (AEC): enabled
    // - Noise Suppression (NS): enabled, level 2 (moderate)
    // - Automatic Gain Control (AGC): enabled, adaptive mode
    // - High-pass filter: enabled (removes low-frequency noise)
}
```

#### SignalingClient

```kotlin
class SignalingClient(
    private val baseUrl: String,
    private val tokenProvider: TokenProvider
) {
    private val webSocket: WebSocket?

    fun connect(callId: String)
    fun sendOffer(sdp: SessionDescription)
    fun sendAnswer(sdp: SessionDescription)
    fun sendIceCandidate(candidate: IceCandidate)
    fun sendMuteState(muted: Boolean)
    fun disconnect()

    // Auto-reconnect with exponential backoff (1s, 2s, 4s, max 30s)
    // Heartbeat every 15 seconds
    // Server disconnect detection via close frame or timeout
}
```

### 3.3 Android-Specific Features

#### PushKit Equivalent (FCM Data Messages)

```json
// FCM Data message for incoming call (always data, not notification)
{
  "type": "call_incoming",
  "call_id": "abc-123",
  "priority": "high",
  "caller_name": "AI Agent",
  "context_summary": "Need approval for deployment"
}
```

Upon receiving:
1. FCM data message arrives even when app is backgrounded/killed
2. `FirebaseMessagingService.onMessageReceived()` processes it
3. If message type is `call_incoming`:
   - Start `CallService` as foreground service
   - Display heads-up notification (high priority channel)
   - Acquire `WAKE_LOCK` (partial, screen on)
   - Show incoming call notification with answer/decline actions

#### Background Calling

```kotlin
// Android 14+ (API 34) foreground service types required
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.CAMERA" /> <!-- future video -->

<service
    android:name=".call.CallService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

#### Audio Routing

- **Earpiece:** Default during call (privacy)
- **Speakerphone:** Toggle button in UI
- **Bluetooth:** Auto-connect to connected BT headset (BroadcastReceiver for ACTION_CONNECTION_STATE_CHANGED)
- **Wired headset:** Auto-detect via AudioManager
- **Audio focus:** Request AUDIO_FOCUS_GAIN on call start, duck on loss

---

## 4. iOS Implementation (Swift)

### 4.1 Tech Stack

| Component | Library | Reason |
|-----------|---------|--------|
| UI | SwiftUI | Modern declarative UI |
| WebRTC | GoogleWebRTC (CocoaPod/SPM) | Google's official build |
| WebSocket | URLSessionWebSocketTask | Native, no dependency |
| HTTP | URLSession + async/await | Native, Swift concurrency |
| DI | Factory or manual DI | Lightweight |
| Push | PushKit + VoIP certificate | Required for VoIP apps |
| Background | PushKit + CallKit | Native call handling |
| Audio | AVAudioSession | Platform audio routing |
| Networking | NWPathMonitor | Network state detection |

### 4.2 Core Components

#### VoIP Push Handling

```swift
class VoIPDelegate: NSObject, PKPushRegistryDelegate {

    private let callManager: CallManager

    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        // 1. Extract call_id from payload
        // 2. Report incoming call to CallKit
        // 3. Configure audio session
        // 4. Connect to signaling server
        // 5. Call completion() after handling

        let callId = payload.dictionaryPayload["call_id"] as! String
        let callerName = payload.dictionaryPayload["caller_name"] as! String

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerName)
        update.hasVideo = false
        update.supportsGrouping = false
        update.supportsHolding = true
        update.supportsDTMF = false

        callManager.provider.reportNewIncomingCall(
            with: UUID(uuidString: callId)!,
            update: update
        ) { error in
            if error == nil {
                self.connectToCall(callId: callId)
            }
            completion()
        }
    }
}
```

#### CallKit Integration

```swift
class CallManager: NSObject, CXProviderDelegate {

    let provider: CXProvider
    let callController: CXCallController

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.includesCallsInRecents = true
        config.supportedHandleTypes = [.generic]

        provider = CXProvider(configuration: config)
        callController = CXCallController()
        super.init()
        provider.setDelegate(self, queue: .main)
    }

    // CXProviderDelegate methods:
    // provider:didActivateAudioSession - start WebRTC audio
    // provider:didDeactivateAudioSession - stop WebRTC audio
    // providerDidReset - reset call state
    // provider:performAnswerCallAction - user answered
    // provider:performEndCallAction - user ended
}
```

#### WebRTC Setup (iOS)

```swift
class WebRTCClient {
    private let factory: RTCPeerConnectionFactory
    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?

    init() {
        // Initialize SSL
        RTCInitializeSSL()

        // Set up encoder/decoder factory
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        factory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
    }

    func createAudioTrack() -> RTCAudioTrack {
        let audioConstrains = RTCMediaConstraints(
            mandatoryConstraints: [:],
            optionalConstraints: nil
        )
        let audioSource = factory.audioSource(with: audioConstrains)
        let audioTrack = factory.audioTrack(with: audioSource, trackId: "audio0")

        // Enable audio processing:
        audioSource?.audioProcessing?.echoCancellation = true
        audioSource?.audioProcessing?.noiseSuppression = true
        audioSource?.audioProcessing?.gainControlEnabled = true

        return audioTrack
    }
}
```

### 4.3 iOS-Specific Features

#### Background Audio

```swift
// Configure AVAudioSession for VoIP
try? audioSession.setCategory(
    .playAndRecord,
    mode: .voiceChat,
    options: [.allowBluetooth, .allowBluetoothA2DP, .duckOthers]
)
try? audioSession.setActive(true)
```

#### Call Screen UI

- SwiftUI view presented via `UIWindowScene` for call overlay
- Shows: caller (AI Agent name), context summary, timer, mute/speaker/end buttons
- Integrates with `UIScene` lifecycle for app-in-background handling

---

## 5. Shared Mobile Features

### 5.1 Push Token Registration

```typescript
// On app startup (both platforms):
POST /api/v1/devices/register
{
  "platform": "android" | "ios",
  "push_token": "fcm-or-apns-token",
  "device_name": "Device Model",
  "app_version": "1.0.0"
}
```

### 5.2 Presence Heartbeat

```typescript
// Every 15 seconds while app is in foreground:
WebSocket → { "type": "heartbeat" }
// (Server refreshes Redis presence TTL)

// On app background:
// Android: heartbeat stops, server marks "away" after 30s
// iOS: heartbeat stops, server marks "away" after 30s
// VoIP push still works regardless of presence status
```

### 5.3 Call Flow State Machine

```
IDLE → INCOMING (push received) → RINGING (user notified)
  → CONNECTING (user answered, WebRTC connecting)
  → CONNECTED (WebRTC established, audio flowing)
  → ENDING (user/peer hung up)
  → IDLE (cleanup)

IDLE → RINGING → MISSED (no answer in 30s)
IDLE → RINGING → REJECTED (user declined)
```

### 5.4 Error Handling

| Scenario | Behavior |
|----------|----------|
| WebSocket disconnect during call | Auto-reconnect, ICE restart, show "reconnecting..." UI |
| Network lost completely | Show "no connection" toast, attempt reconnect for 30s |
| WebRTC connection failure | Fallback to TURN relay, retry ICE restart |
| Push delivery failure | Call queued, retried 3 times with 5s interval |
| Token expired | Refresh token silently, retry original request |
| Call timeout | Auto-cancel, notify agent via MCP |

---

## 6. Build & Distribution

### Android
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Build:** Gradle (Kotlin DSL)
- **Distribution:** Beta via Firebase App Distribution, production via Google Play
- **ProGuard:** Enabled with WebRTC keep rules

### iOS
- **Min iOS:** 16.0
- **Build:** Xcode 16+ with Swift 6
- **Distribution:** Beta via TestFlight, production via App Store
- **Entitlements:** VoIP push certificate (production), PushKit, CallKit
- **Note:** VoIP push requires entitlement from Apple (limited, apply early)
