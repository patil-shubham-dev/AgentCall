import SwiftUI

struct IncomingCallView: View {
    let callerName: String
    let contextSummary: String
    let priority: String
    let onAnswer: () -> Void
    let onDecline: () -> Void
    let onSnooze: () -> Void

    @State private var pulseRing = false

    var body: some View {
        ZStack {
            // Background
            Color.backgroundDark.ignoresSafeArea()

            // Animated glow orbs
            Canvas { context, size in
                let pulseRadius = size.width * (0.15 + (pulseRing ? 0.1 : 0))
                // Outer glow
                context.fill(
                    Ellipse().path(in: CGRect(
                        x: size.width / 2 - pulseRadius * 3,
                        y: size.height * 0.3 - pulseRadius * 3,
                        width: pulseRadius * 6,
                        height: pulseRadius * 6
                    )),
                    with: .color(Color.accentPrimary.opacity(0.12 * (pulseRing ? 1 : 0.85)))
                )
                context.fill(
                    Ellipse().path(in: CGRect(
                        x: size.width * 0.4,
                        y: size.height * 0.4,
                        width: size.width * 0.45,
                        height: size.width * 0.45
                    )),
                    with: .color(Color(hex: "8B5CF6").opacity(0.06))
                )
            }

            VStack(spacing: 0) {
                Spacer().frame(height: 60)

                // Incoming chip
                HStack(spacing: 8) {
                    Circle()
                        .fill(Color.accentPrimary.opacity(pulseRing ? 0.8 : 0.4))
                        .frame(width: 8, height: 8)
                    Text("INCOMING AI CALL")
                        .font(.caption)
                        .fontWeight(.semibold)
                        .kerning(1)
                        .foregroundColor(.accentLight)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.accentPrimary.opacity(0.12))
                .cornerRadius(100)

                Spacer()

                // Animated avatar
                ZStack {
                    // Pulse rings
                    Circle()
                        .stroke(Color.accentPrimary.opacity(0.15 * (pulseRing ? 1 : 0.6)), lineWidth: 2)
                        .frame(width: 160, height: 160)
                        .scaleEffect(pulseRing ? 1.05 : 0.95)

                    Circle()
                        .stroke(Color.accentLight.opacity(0.08), lineWidth: 1)
                        .frame(width: 130, height: 130)
                        .scaleEffect(pulseRing ? 1.08 : 1)

                    // Avatar
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [.brandGradientStart, .brandGradientEnd],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 100, height: 100)

                    Image(systemName: "radar")
                        .font(.system(size: 52))
                        .foregroundColor(.white)
                }

                Text(callerName)
                    .font(.title)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
                    .padding(.top, 20)

                // Priority badge
                PriorityBadge(priority: priority)
                    .padding(.top, 12)

                Spacer().frame(height: 16)

                // Context card
                if !contextSummary.isEmpty {
                    HStack(spacing: 10) {
                        Image(systemName: "info.circle.fill")
                            .font(.caption)
                            .foregroundColor(.textSecondary)

                        Text(contextSummary)
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(16)
                    .background(Color.surfaceDark.opacity(0.7))
                    .cornerRadius(16)
                    .padding(.horizontal, 48)
                }

                Spacer()

                // Action buttons
                HStack(spacing: 32) {
                    // Decline
                    VStack(spacing: 8) {
                        Button(action: onDecline) {
                            ZStack {
                                Circle()
                                    .fill(Color.errorRed.opacity(0.12))
                                    .frame(width: 64, height: 64)

                                Image(systemName: "phone.down.fill")
                                    .font(.system(size: 24))
                                    .foregroundColor(.errorLight)
                            }
                        }

                        Text("Decline")
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(.errorLight)
                    }

                    // Answer (prominent)
                    VStack(spacing: 8) {
                        Button(action: onAnswer) {
                            ZStack {
                                Circle()
                                    .fill(Color.successGreen)
                                    .frame(width: 76, height: 76)
                                    .shadow(color: .successGreen.opacity(0.3), radius: 12, x: 0, y: 6)

                                Image(systemName: "phone.fill")
                                    .font(.system(size: 30))
                                    .foregroundColor(.white)
                            }
                        }

                        Text("Answer")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundColor(.successLight)
                    }

                    // Snooze
                    VStack(spacing: 8) {
                        Button(action: onSnooze) {
                            ZStack {
                                Circle()
                                    .fill(Color.white.opacity(0.08))
                                    .frame(width: 64, height: 64)

                                Image(systemName: "bell.slash.fill")
                                    .font(.system(size: 24))
                                    .foregroundColor(.textSecondary)
                            }
                        }

                        Text("Snooze")
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(.textSecondary)
                    }
                }

                Spacer().frame(height: 16)

                Text("Snooze to return the call\nback to the agent queue")
                    .font(.caption2)
                    .foregroundColor(.textTertiary)
                    .multilineTextAlignment(.center)

                Spacer().frame(height: 48)
            }
        }
        .onAppear {
            withAnimation(Animation.easeInOut(duration: 2).repeatForever(autoreverses: true)) {
                pulseRing = true
            }
        }
    }
}
