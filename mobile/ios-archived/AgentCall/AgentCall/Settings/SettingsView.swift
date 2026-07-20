import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var auth: AuthViewModel
    @State private var incomingCalls = true
    @State private var taskCompletions = true
    @State private var dndEnabled = false
    @State private var storeTranscripts = false

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // Profile
                    SettingsSection(title: "Profile") {
                        HStack(spacing: 14) {
                            ZStack {
                                LinearGradient(
                                    colors: [.brandGradientStart, .brandGradientEnd],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                                .frame(width: 52, height: 52)
                                .clipShape(Circle())

                                Image(systemName: "person.fill")
                                    .font(.title3)
                                    .foregroundColor(.white)
                            }

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Your Account")
                                    .font(.body)
                                    .fontWeight(.semibold)
                                    .foregroundColor(.white)
                                Text("Connected")
                                    .font(.caption)
                                    .foregroundColor(.successGreen)
                            }

                            Spacer()

                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.textTertiary)
                        }
                    }

                    // Notifications
                    SettingsSection(title: "Notifications") {
                        SettingsCard {
                            ToggleRow(
                                icon: "bell.fill",
                                iconColor: .accentLight,
                                title: "Incoming calls",
                                subtitle: "Get notified when an AI agent calls",
                                isOn: $incomingCalls
                            )
                            SettingsDivider()
                            ToggleRow(
                                icon: "checkmark.circle.fill",
                                iconColor: .successLight,
                                title: "Task completions",
                                subtitle: "Receive updates when tasks finish",
                                isOn: $taskCompletions
                            )
                        }
                    }

                    // DND
                    SettingsSection(title: "Do Not Disturb") {
                        SettingsCard {
                            ToggleRow(
                                icon: "moon.fill",
                                iconColor: .warningAmber,
                                title: "Quiet hours",
                                subtitle: "Mute incoming calls during specific hours",
                                isOn: $dndEnabled
                            )
                            if dndEnabled {
                                SettingsDivider()
                                HStack(spacing: 12) {
                                    Image(systemName: "clock")
                                        .font(.subheadline)
                                        .foregroundColor(.textTertiary)
                                    Text("22:00 — 07:00")
                                        .font(.subheadline)
                                        .foregroundColor(.white)
                                    Spacer()
                                    Text("Your timezone")
                                        .font(.caption2)
                                        .foregroundColor(.textTertiary)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                            }
                        }
                    }

                    // Privacy
                    SettingsSection(title: "Privacy") {
                        SettingsCard {
                            ToggleRow(
                                icon: "doc.text.fill",
                                iconColor: .textSecondary,
                                title: "Store transcripts",
                                subtitle: "Keep call transcripts for review",
                                isOn: $storeTranscripts
                            )
                            SettingsDivider()
                            ClickableRow(
                                icon: "trash.fill",
                                iconColor: .errorRed,
                                title: "Clear history",
                                subtitle: "Remove all call records"
                            )
                        }
                    }

                    // Connected Agents
                    SettingsSection(title: "Connected Agents") {
                        SettingsCard {
                            AgentRow(name: "OpenCode", status: "Active", color: .successGreen)
                            SettingsDivider()
                            AgentRow(name: "Claude Code", status: "Active", color: .successGreen)
                            SettingsDivider()
                            HStack(spacing: 14) {
                                ZStack {
                                    Circle()
                                        .fill(Color.accentPrimary.opacity(0.12))
                                        .frame(width: 36, height: 36)
                                    Image(systemName: "plus")
                                        .font(.subheadline)
                                        .foregroundColor(.accentLight)
                                }
                                Text("Add Agent")
                                    .font(.body)
                                    .fontWeight(.medium)
                                    .foregroundColor(.accentLight)
                                Spacer()
                            }
                            .padding(16)
                        }
                    }

                    // About
                    SettingsSection(title: "About") {
                        SettingsCard {
                            ClickableRow(icon: "info.circle.fill", iconColor: .textSecondary, title: "Version", subtitle: "1.0.0")
                            SettingsDivider()
                            ClickableRow(icon: "doc.text.fill", iconColor: .textSecondary, title: "Terms of Service")
                            SettingsDivider()
                            ClickableRow(icon: "shield.fill", iconColor: .textSecondary, title: "Privacy Policy")
                        }
                    }

                    // Sign Out
                    Button(action: { auth.logout() }) {
                        HStack(spacing: 8) {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .font(.subheadline)
                            Text("Sign Out")
                                .font(.body)
                                .fontWeight(.medium)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(
                                    LinearGradient(
                                        colors: [.errorRed.opacity(0.3), .errorRed.opacity(0.1)],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    ),
                                    lineWidth: 1
                                )
                        )
                        .foregroundColor(.errorLight)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 24)

                    Text("Version 1.0.0")
                        .font(.caption2)
                        .foregroundColor(.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 24)
                        .padding(.bottom, 32)
                }
            }
            .background(Color.backgroundDark)
            .navigationBarHidden(true)
        }
    }
}

// MARK: - Reusable Components
struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption2)
                .fontWeight(.semibold)
                .kerning(0.5)
                .foregroundColor(.textSecondary)
                .padding(.leading, 4)
                .padding(.top, 24)

            content
        }
        .padding(.horizontal, 16)
    }
}

struct SettingsCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            content
        }
        .background(Color.surfaceDark)
        .cornerRadius(16)
    }
}

struct SettingsDivider: View {
    var body: some View {
        Divider()
            .background(Color.borderSubtle)
            .padding(.leading, 54)
    }
}

struct ClickableRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    var subtitle: String? = nil

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(iconColor)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundColor(.white)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundColor(.textSecondary)
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.textTertiary)
        }
        .padding(16)
    }
}

struct AgentRow: View {
    let name: String
    let status: String
    let color: Color

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(Color.accentPrimary.opacity(0.12))
                    .frame(width: 36, height: 36)
                Image(systemName: "cpu")
                    .font(.subheadline)
                    .foregroundColor(.accentLight)
            }

            Text(name)
                .font(.body)
                .foregroundColor(.white)

            Spacer()

            Circle()
                .fill(color)
                .frame(width: 6, height: 6)

            Text(status)
                .font(.caption)
                .foregroundColor(color)
        }
        .padding(16)
    }
}
