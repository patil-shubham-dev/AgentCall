import SwiftUI

struct AuthView: View {
    @EnvironmentObject var viewModel: AuthViewModel

    var body: some View {
        ZStack {
            // Background
            Color.backgroundDark.ignoresSafeArea()

            // Gradient orbs (using safe Circle+blur instead of Canvas symbols)
            Circle()
                .fill(Color.accentPrimary)
                .frame(width: 280)
                .blur(radius: 120)
                .opacity(0.12)
                .offset(x: -80, y: -180)

            Circle()
                .fill(Color(hex: "8B5CF6"))
                .frame(width: 200)
                .blur(radius: 100)
                .opacity(0.08)
                .offset(x: 120, y: -80)

            VStack(spacing: 0) {
                Spacer()

                // Brand
                VStack(spacing: 16) {
                    // Avatar
                    ZStack {
                        Circle()
                            .fill(
                                LinearGradient(
                                    colors: [.brandGradientStart, .brandGradientEnd],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 88, height: 88)

                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 40, weight: .medium))
                            .foregroundColor(.white)
                    }
                    .pulse()

                    Text("AgentCall")
                        .font(.largeTitle)
                        .fontWeight(.bold)
                        .foregroundColor(.white)

                    Text("Your AI agents call you.\nYou stay in control.")
                        .font(.body)
                        .foregroundColor(.textSecondary)
                        .multilineTextAlignment(.center)
                }

                Spacer().frame(height: 48)

                // Login card
                VStack(spacing: 0) {
                    VStack(spacing: 16) {
                        Text("Get Started")
                            .font(.title2)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)

                        Text("Connect your account to enable\nAI agent calling")
                            .font(.subheadline)
                            .foregroundColor(.textSecondary)
                            .multilineTextAlignment(.center)

                        Spacer().frame(height: 8)

                        // Google
                        OAuthButton(
                            icon: "person.crop.circle",
                            label: "Continue with Google",
                            iconColor: Color(hex: "4285F4"),
                            action: { Task { await viewModel.login(email: "") } }
                        )

                        // GitHub
                        OAuthButton(
                            icon: "chevron.left.forwardslash.chevron.right",
                            label: "Continue with GitHub",
                            iconColor: .white,
                            action: { Task { await viewModel.login(email: "") } }
                        )

                        // Apple
                        OAuthButton(
                            icon: "applelogo",
                            label: "Continue with Apple",
                            iconColor: .white,
                            action: { Task { await viewModel.login(email: "") } }
                        )

                        if let error = viewModel.error {
                            Text(error)
                                .font(.caption)
                                .foregroundColor(.errorLight)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(.red.opacity(0.12))
                                .cornerRadius(12)
                        }
                    }
                }
                .padding(24)
                .background(Color.surfaceDark.opacity(0.7))
                .cornerRadius(24)
                .padding(.horizontal, 24)

                Spacer()

                // Footer
                Text("By continuing, you agree to our\nTerms of Service and Privacy Policy")
                    .font(.caption2)
                    .foregroundColor(.textTertiary)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 32)
            }
        }
    }
}

struct OAuthButton: View {
    let icon: String
    let label: String
    let iconColor: Color
    let action: () -> Void
    @State private var pressed = false

    var body: some View {
        Button(action: {
            withAnimation(.easeInOut(duration: 0.1)) {
                pressed = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                pressed = false
                action()
            }
        }) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundColor(iconColor)
                    .frame(width: 24)

                Text(label)
                    .font(.body)
                    .fontWeight(.medium)
                    .foregroundColor(Color(hex: "E2E8F0"))

                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(pressed ? Color.surfaceElevated : Color.surfaceDeep)
            .cornerRadius(16)
        }
        .scaleEffect(pressed ? 0.97 : 1)
        .animation(.spring(response: 0.3), value: pressed)
    }
}
