import SwiftUI

struct ActiveCallView: View {
    let callId: String
    let onEndCall: () -> Void

    @State private var elapsedSeconds = 0
    @State private var isMuted = false
    @State private var isSpeakerOn = false
    @State private var connectionQuality: Double = 0.85
    @State private var timer: Timer?

    private var timerText: String {
        let m = elapsedSeconds / 60
        let s = elapsedSeconds % 60
        return String(format: "%02d:%02d", m, s)
    }

    @State private var waveformLevels: [Double] = Array(repeating: 0.08, count: 40)

    var body: some View {
        ZStack {
            // Background
            Color.backgroundDark.ignoresSafeArea()

            // Gradient orbs
            Canvas { context, size in
                let rect1 = CGRect(
                    x: size.width * 0.3, y: -size.height * 0.1,
                    width: size.width * 0.6, height: size.width * 0.6
                )
                context.fill(
                    Ellipse().path(in: rect1),
                    with: .color(Color.accentPrimary.opacity(0.08))
                )
                let rect2 = CGRect(
                    x: size.width * 0.1, y: size.height * 0.4,
                    width: size.width * 0.7, height: size.width * 0.7
                )
                context.fill(
                    Ellipse().path(in: rect2),
                    with: .color(Color(hex: "8B5CF6").opacity(0.05))
                )
            }

            VStack(spacing: 0) {
                Spacer()

                // Connection status
                HStack(spacing: 8) {
                    Circle()
                        .fill(Color.successGreen)
                        .frame(width: 8, height: 8)
                    Text("Connected")
                        .font(.caption)
                        .foregroundColor(.successGreen)
                }

                Spacer().frame(height: 32)

                // Timer
                Text(timerText)
                    .font(.system(size: 64, weight: .light, design: .monospaced))
                    .foregroundColor(.white)
                    .kerning(2)

                Spacer().frame(height: 32)

                // AI Avatar (breathing animation)
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [.brandGradientStart, .brandGradientEnd],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 100, height: 100)
                        .pulse()

                    Image(systemName: "radar")
                        .font(.system(size: 48))
                        .foregroundColor(.white)
                }

                Text("AI Agent")
                    .font(.title2)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)
                    .padding(.top, 16)

                Text("Waiting for your response")
                    .font(.subheadline)
                    .foregroundColor(.textSecondary)
                    .padding(.top, 4)

                Spacer().frame(height: 40)

                // Waveform
                WaveformView(levels: waveformLevels, isMuted: isMuted)
                    .frame(height: 60)
                    .padding(.horizontal, 32)

                Spacer().frame(height: 16)

                // Quality indicator
                HStack(spacing: 4) {
                    let bars = Int(connectionQuality * 5)
                    let qualityText: String = {
                        if connectionQuality > 0.7 { return "Excellent" }
                        if connectionQuality > 0.4 { return "Good" }
                        return "Poor"
                    }()
                    let qualityColor: Color = {
                        if connectionQuality > 0.7 { return .successLight }
                        if connectionQuality > 0.4 { return .warningAmber }
                        return .errorLight
                    }()

                    ForEach(0..<5) { i in
                        RoundedRectangle(cornerRadius: 3)
                            .fill(i < bars ? qualityColor : Color.surfaceElevated)
                            .frame(width: 8, height: i < bars ? 16 : 8)
                    }

                    Text(qualityText)
                        .font(.caption2)
                        .foregroundColor(qualityColor)
                        .padding(.leading, 8)
                }

                Spacer()

                // Call controls
                HStack(spacing: 28) {
                    CallControlButton(
                        icon: isMuted ? "mic.slash.fill" : "mic.fill",
                        label: isMuted ? "Unmute" : "Mute",
                        color: isMuted ? .warningAmber : .white,
                        background: isMuted ? .glassAmber : .glassWhite
                    ) { isMuted.toggle() }

                    CallControlButton(
                        icon: "speaker.wave.2.fill",
                        label: "Speaker",
                        color: isSpeakerOn ? .accentLight : .white,
                        background: isSpeakerOn ? .glassIndigo : .glassWhite
                    ) { isSpeakerOn.toggle() }

                    CallControlButton(
                        icon: "keyboard.fill",
                        label: "Keypad",
                        color: .white,
                        background: .glassWhite
                    ) {}
                }

                Spacer().frame(height: 32)

                // End call
                VStack(spacing: 8) {
                    Button(action: onEndCall) {
                        ZStack {
                            Circle()
                                .fill(Color.errorRed)
                                .frame(width: 72, height: 72)
                                .shadow(color: .errorRed.opacity(0.3), radius: 12, x: 0, y: 6)

                            Image(systemName: "phone.down.fill")
                                .font(.system(size: 26))
                                .foregroundColor(.white)
                        }
                    }

                    Text("End")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.errorLight)
                }

                Spacer().frame(height: 48)
            }
        }
        .onAppear {
            startTimer()
        }
        .onDisappear {
            timer?.invalidate()
        }
    }

    private func startTimer() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { _ in
            let t = Double(elapsedSeconds)
            elapsedSeconds += 1

            // Update waveform
            if isMuted {
                waveformLevels = (0..<40).map { i in
                    0.04 + 0.02 * abs(sin(t * 0.3 + Double(i) * 0.5))
                }
            } else {
                waveformLevels = (0..<40).map { i in
                    let freq = 2.0 + 3.0 * abs(sin(Double(i) * 0.1))
                    let envelope = 1.0 - Double(i - 20) * Double(i - 20) / 400.0
                    let signal = abs(sin(t * freq * 0.5 + Double(i) * 0.3) * 0.7
                        + sin(t * 1.3 + Double(i) * 0.7) * 0.3)
                    return min(0.95, max(0.02, 0.08 + signal * envelope * 0.5))
                }
            }
        }
    }
}

// MARK: - Waveform View
struct WaveformView: View {
    let levels: [Double]
    let isMuted: Bool

    var body: some View {
        GeometryReader { geo in
            let barWidth = geo.size.width / CGFloat(levels.count * 2 - 1)
            let centerY = geo.size.height / 2

            Canvas { context, size in
                for (i, level) in levels.enumerated() {
                    let barHeight = level * size.height * 0.8
                    let x = CGFloat(i) * barWidth * 2
                    let color = isMuted ? Color.waveformMuted : Color.waveformActive

                    let rect = CGRect(
                        x: x,
                        y: centerY - barHeight / 2,
                        width: barWidth,
                        height: barHeight
                    )
                    context.fill(
                        Path(roundedRect: rect, cornerRadius: barWidth / 2),
                        with: .color(color.opacity(0.4 + level * 0.6))
                    )
                }
            }
        }
    }
}

// MARK: - Call Control Button
struct CallControlButton: View {
    let icon: String
    let label: String
    let color: Color
    let background: Color
    let action: () -> Void
    @State private var pressed = false

    var body: some View {
        VStack(spacing: 8) {
            Button(action: {
                withAnimation(.spring(response: 0.2)) { pressed = true }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                    pressed = false
                    action()
                }
            }) {
                ZStack {
                    Circle()
                        .fill(background)
                        .frame(width: 60, height: 60)

                    Image(systemName: icon)
                        .font(.system(size: 22))
                        .foregroundColor(color)
                }
            }
            .scaleEffect(pressed ? 0.92 : 1)

            Text(label)
                .font(.caption2)
                .foregroundColor(.textSecondary)
        }
    }
}
