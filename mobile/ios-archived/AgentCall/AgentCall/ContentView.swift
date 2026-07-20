import SwiftUI

struct ContentView: View {
    @EnvironmentObject var auth: AuthViewModel

    var body: some View {
        Group {
            if auth.isLoggedIn {
                MainTabView()
                    .transition(.opacity.animation(.easeInOut(duration: 0.3)))
            } else {
                AuthView()
                    .transition(.opacity.animation(.easeInOut(duration: 0.3)))
            }
        }
    }
}

struct MainTabView: View {
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem {
                    Label("Home", systemImage: selectedTab == 0 ? "house.fill" : "house")
                }
                .tag(0)
                .toolbarBackground(Color.surfaceDark.opacity(0.95), for: .tabBar)
                .toolbarBackground(.visible, for: .tabBar)

            ActiveCallView(
                callId: "active",
                onEndCall: {}
            )
                .tabItem {
                    Label("Call", systemImage: selectedTab == 1 ? "phone.fill" : "phone")
                }
                .tag(1)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: selectedTab == 2 ? "gearshape.fill" : "gearshape")
                }
                .tag(2)
        }
        .tint(.accentPrimary)
    }
}
