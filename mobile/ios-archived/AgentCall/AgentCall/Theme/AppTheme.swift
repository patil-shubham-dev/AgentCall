import SwiftUI

// MARK: - Colors
extension Color {
    // Brand
    static let accentPrimary = Color(hex: "6366F1")
    static let accentDark = Color(hex: "4F46E5")
    static let accentLight = Color(hex: "818CF8")

    // Gradients
    static let brandGradientStart = Color(hex: "6366F1")
    static let brandGradientEnd = Color(hex: "8B5CF6")

    // Surfaces
    static let surfaceDark = Color(hex: "1E293B")
    static let surfaceElevated = Color(hex: "263040")
    static let surfaceDeep = Color(hex: "172033")
    static let backgroundDark = Color(hex: "0F172A")

    // Text
    static let textPrimary = Color(hex: "F8FAFC")
    static let textSecondary = Color(hex: "94A3B8")
    static let textTertiary = Color(hex: "64748B")

    // Status
    static let successGreen = Color(hex: "22C55E")
    static let successLight = Color(hex: "4ADE80")
    static let errorRed = Color(hex: "EF4444")
    static let errorLight = Color(hex: "F87171")
    static let warningAmber = Color(hex: "F59E0B")
    static let warningLight = Color(hex: "FBBF24")

    // Glass
    static let glassWhite = Color.white.opacity(0.08)
    static let glassWhiteMedium = Color.white.opacity(0.12)
    static let glassIndigo = Color(hex: "6366F1").opacity(0.12)
    static let glassGreen = Color(hex: "22C55E").opacity(0.12)
    static let glassRed = Color(hex: "EF4444").opacity(0.12)
    static let glassAmber = Color(hex: "F59E0B").opacity(0.12)

    // Waveform
    static let waveformActive = Color(hex: "6366F1")
    static let waveformIdle = Color(hex: "334155")
    static let waveformMuted = Color(hex: "475569")

    // Borders
    static let borderSubtle = Color(hex: "334155")
}

// MARK: - Typography
struct AppTypography {
    static let largeTitle = Font.system(size: 34, weight: .bold, design: .default)
    static let title1 = Font.system(size: 28, weight: .semibold)
    static let title2 = Font.system(size: 22, weight: .semibold)
    static let title3 = Font.system(size: 20, weight: .semibold)
    static let headline = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 17, weight: .regular)
    static let callout = Font.system(size: 16, weight: .regular)
    static let subheadline = Font.system(size: 15, weight: .regular)
    static let footnote = Font.system(size: 13, weight: .regular)
    static let caption = Font.system(size: 12, weight: .regular)
    static let caption2 = Font.system(size: 11, weight: .medium)
    static let timer = Font.system(size: 64, weight: .light, design: .monospaced)
}

// MARK: - View Modifiers

struct GlassCardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(Color.surfaceDark)
            .cornerRadius(16)
    }
}

struct ElevatedCardStyle: ViewModifier {
    var color: Color = .surfaceDark

    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(color)
            .cornerRadius(16)
            .shadow(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
    }
}

extension View {
    func glassCard() -> some View {
        modifier(GlassCardStyle())
    }

    func elevatedCard(color: Color = .surfaceDark) -> some View {
        modifier(ElevatedCardStyle(color: color))
    }
}

// MARK: - Priority Badge
struct PriorityBadge: View {
    let priority: String

    private var label: String { priority.uppercased() }
    private var color: Color {
        switch priority {
        case "urgent": return .errorRed
        case "high": return .warningAmber
        case "normal": return .accentPrimary
        default: return .textSecondary
        }
    }

    var body: some View {
        Text(label)
            .font(.system(size: 10, weight: .bold, design: .default).monospacedDigit())
            .kerning(1)
            .foregroundColor(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .cornerRadius(4)
    }
}

// MARK: - Status Indicator
struct StatusIndicator: View {
    let status: String

    private var color: Color {
        switch status {
        case "online": return .successGreen
        case "away": return .warningAmber
        case "busy": return .errorRed
        default: return .textSecondary
        }
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text(status.uppercased())
                .font(.caption2)
                .foregroundColor(color)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(color.opacity(0.12))
        .cornerRadius(100)
    }
}

// MARK: - Primary Button
struct PrimaryButton: View {
    let title: String
    let isLoading: Bool
    let disabled: Bool
    let gradient: LinearGradient?
    let action: () -> Void

    init(
        title: String,
        isLoading: Bool = false,
        disabled: Bool = false,
        gradient: LinearGradient? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.isLoading = isLoading
        self.disabled = disabled
        self.gradient = gradient
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        .scaleEffect(0.8)
                } else {
                    Text(title)
                        .font(.headline)
                        .fontWeight(.semibold)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                gradient ?? LinearGradient(
                    colors: [.accentPrimary, Color(hex: "8B5CF6")],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
            .foregroundColor(.white)
            .cornerRadius(16)
            .opacity(disabled ? 0.5 : 1)
        }
        .disabled(disabled || isLoading)
    }
}

// MARK: - Pulse Animation Modifier
struct PulseAnimation: ViewModifier {
    @State private var isPulsing = false

    func body(content: Content) -> some View {
        content
            .scaleEffect(isPulsing ? 1.04 : 1)
            .animation(
                Animation.easeInOut(duration: 1.5).repeatForever(autoreverses: true),
                value: isPulsing
            )
            .onAppear { isPulsing = true }
    }
}

extension View {
    func pulse() -> some View {
        modifier(PulseAnimation())
    }
}

// MARK: - Toggle Row
struct ToggleRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(iconColor)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundColor(.textPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.textSecondary)
            }

            Spacer()

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(.accentPrimary)
        }
    }
}
