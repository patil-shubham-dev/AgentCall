import SwiftUI

struct HomeView: View {
    @StateObject private var viewModel = HomeViewModel()
    @State private var timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.backgroundDark.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // Header
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("AgentCall")
                                .font(.largeTitle)
                                .fontWeight(.bold)
                                .foregroundColor(.white)

                            Text("Connected & ready")
                                .font(.caption)
                                .foregroundColor(.textSecondary)
                        }

                        Spacer()

                        if let presence = viewModel.presence {
                            StatusIndicator(status: presence.status)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 16)

                    // Active call banner
                    if let activeCall = viewModel.activeCall {
                        Button(action: { viewModel.selectedCallId = activeCall }) {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle()
                                        .fill(Color.successGreen.opacity(0.2))
                                        .frame(width: 40, height: 40)
                                    Circle()
                                        .fill(Color.successGreen)
                                        .frame(width: 10, height: 10)
                                }

                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Active Call — AI Agent")
                                        .font(.subheadline)
                                        .fontWeight(.semibold)
                                        .foregroundColor(.white)
                                    Text("Tap to open")
                                        .font(.caption2)
                                        .foregroundColor(.successLight)
                                }

                                Spacer()

                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundColor(.textSecondary)
                            }
                            .padding(16)
                            .background(Color.surfaceDark)
                            .cornerRadius(16)
                            .padding(.horizontal, 16)
                            .padding(.top, 20)
                        }
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    // Section header
                    HStack {
                        Text("Recent Calls")
                            .font(.title2)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)

                        Spacer()

                        Text("See all")
                            .font(.caption)
                            .foregroundColor(.accentLight)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 24)
                    .padding(.bottom, 12)

                    // Content
                    if viewModel.isLoading {
                        Spacer().frame(height: 80)
                        ProgressView()
                            .tint(.accentPrimary)
                            .frame(maxWidth: .infinity)
                    } else if let error = viewModel.error {
                        Spacer().frame(height: 60)
                        VStack(spacing: 16) {
                            ZStack {
                                Circle()
                                    .fill(Color.errorRed.opacity(0.12))
                                    .frame(width: 64, height: 64)
                                Image(systemName: "exclamationmark.triangle")
                                    .font(.title2)
                                    .foregroundColor(.errorLight)
                            }
                            Text(error)
                                .font(.subheadline)
                                .foregroundColor(.textSecondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)

                            Button("Retry") { viewModel.loadCallHistory() }
                                .buttonStyle(.bordered)
                                .tint(.accentPrimary)
                        }
                        .frame(maxWidth: .infinity)
                    } else if viewModel.calls.isEmpty {
                        Spacer().frame(height: 60)
                        VStack(spacing: 16) {
                            ZStack {
                                Circle()
                                    .fill(Color.accentPrimary.opacity(0.12))
                                    .frame(width: 80, height: 80)
                                Image(systemName: "phone.arrow.down.left")
                                    .font(.title)
                                    .foregroundColor(.accentLight)
                            }
                            Text("No calls yet")
                                .font(.title3)
                                .fontWeight(.semibold)
                                .foregroundColor(.white)
                            Text("When an AI agent needs your input,\nyou'll receive a call here")
                                .font(.subheadline)
                                .foregroundColor(.textSecondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        LazyVStack(spacing: 10) {
                            ForEach(viewModel.calls) { call in
                                CallHistoryCard(call: call)
                                    .padding(.horizontal, 16)
                            }
                        }
                        .padding(.bottom, 16)
                    }
                }
            }
            .refreshable { viewModel.loadCallHistory() }
            .onReceive(timer) { _ in viewModel.refreshPresence() }
            .onAppear {
                viewModel.loadCallHistory()
                viewModel.refreshPresence()
            }
        }
    }
}

// MARK: - Call History Card
struct CallHistoryCard: View {
    let call: CallResponse

    private var isSuccess: Bool { call.status == "ended" }
    private var isMissed: Bool { call.status == "timed_out" || call.status == "failed" }
    private var isCancelled: Bool { call.status == "cancelled" }

    private var statusColor: Color {
        isSuccess ? .successGreen : isMissed ? .errorLight : isCancelled ? .warningAmber : .textSecondary
    }

    private var statusIcon: String {
        isSuccess ? "checkmark.circle.fill" : isMissed ? "xmark.circle.fill" : isCancelled ? "minus.circle.fill" : "circle.fill"
    }

    private var statusLabel: String {
        isSuccess ? "Completed" : isMissed ? "Missed" : isCancelled ? "Cancelled" : call.status.capitalized
    }

    @State private var pressed = false

    var body: some View {
        Button(action: {}) {
            HStack(alignment: .top, spacing: 14) {
                // Status icon
                ZStack {
                    Circle()
                        .fill(statusColor.opacity(0.12))
                        .frame(width: 44, height: 44)
                    Image(systemName: statusIcon)
                        .font(.system(size: 18))
                        .foregroundColor(statusColor)
                }

                // Info
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text("AI Agent")
                            .font(.body)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                        if let priority = call.priority {
                            PriorityBadge(priority: priority)
                        }
                    }

                    if let summary = call.contextSummary {
                        Text(summary)
                            .font(.subheadline)
                            .foregroundColor(.textSecondary)
                            .lineLimit(2)
                    }

                    HStack(spacing: 4) {
                        Text(statusLabel)
                            .font(.caption)
                            .foregroundColor(statusColor)
                        if let duration = call.durationSeconds, isSuccess {
                            Text("· \(formatDuration(duration))")
                                .font(.caption)
                                .foregroundColor(.textTertiary)
                        }
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.textTertiary)
                    .padding(.top, 4)
            }
            .padding(16)
            .background(pressed ? Color.surfaceElevated : Color.surfaceDark)
            .cornerRadius(16)
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.borderSubtle, lineWidth: 0.5)
            )
            .shadow(color: .black.opacity(pressed ? 0.1 : 0.2), radius: 8, x: 0, y: 4)
            .scaleEffect(pressed ? 0.98 : 1)
        }
        .buttonStyle(ScaleButtonStyle())
    }

    private func formatDuration(_ seconds: Int) -> String {
        let m = seconds / 60
        let s = seconds % 60
        return m > 0 ? "\(m)m \(s)s" : "\(s)s"
    }
}

struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.spring(response: 0.3), value: configuration.isPressed)
    }
}

// MARK: - Call Response Extensions
extension CallResponse: Identifiable {
    var id: String { callId }
}

extension CallResponse {
    // Computed property for priority since it may come from server
    var displayPriority: String { priority ?? "normal" }
}
