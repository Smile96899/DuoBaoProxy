import SwiftUI
import UIKit

@main
struct DuoBaoProxyApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .modifier(Themed())
                .preferredColorScheme(ThemeMode(rawValue: model.prefs.theme)?.scheme)
                .onAppear { model.onLaunch() }
                .onOpenURL { url in
                    guard model.prefs.urlScheme else { return }
                    Task { await model.importText(url.absoluteString) }
                }
        }
        .onChange(of: phase) { p in model.onScene(p) }
    }
}

enum Tab: Hashable { case home, subs, rules, settings }

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c
    @Environment(\.scenePhase) private var phase
    @State private var tab = DemoMode.tab
    @State private var settingsPath = NavigationPath()
    @State private var clipOffer: String?

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView() }
                .tabItem { Label("首页", systemImage: "house") }.tag(Tab.home)
            NavigationStack { SubsView() }
                .tabItem { Label("订阅", systemImage: "cloud") }.tag(Tab.subs)
            NavigationStack { RulesView() }
                .tabItem { Label("规则", systemImage: "list.bullet.indent") }.tag(Tab.rules)
            NavigationStack(path: $settingsPath) { SettingsView() }
                .tabItem { Label("设置", systemImage: "gearshape") }.tag(Tab.settings)
        }
        .tint(c.accent)
        .overlay(alignment: .bottom) {
            if let t = model.toast {
                ToastView(text: t)
                    .padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        withAnimation { model.toast = nil }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.toast)
        .onChange(of: phase) { p in if p == .active && !DemoMode.enabled { checkClipboard() } }
        .onAppear {
            // Screenshot routing (CI): open a 设置 sub page or a switch's 说明 page.
            if let p = DemoMode.page { settingsPath.append(p) }
            if let s = DemoMode.info { settingsPath.append(s) }
        }
        .alert("发现分享链接", isPresented: Binding(get: { clipOffer != nil }, set: { if !$0 { clipOffer = nil } })) {
            Button("导入") { if let t = clipOffer { Task { await model.importText(t) } }; clipOffer = nil }
            Button("取消", role: .cancel) { clipOffer = nil }
        } message: {
            Text("剪贴板里有节点或订阅链接，要导入吗？")
        }
    }

    /// 自动检测剪贴板: only looks when the pasteboard says it holds a URL-ish string.
    private func checkClipboard() {
        guard model.prefs.clipDetect, UIPasteboard.general.hasStrings else { return }
        guard let s = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return }
        let schemes = ["vmess://", "vless://", "ss://", "ssr://", "trojan://", "hysteria://", "hysteria2://", "hy2://", "tuic://", "anytls://", "wireguard://", "socks://"]
        let lower = s.lowercased()
        let looks = schemes.contains(where: lower.hasPrefix) || (AppModel.isSubscriptionURL(s) && !model.store.groups.contains { $0.subUrl == s })
        if looks, s != UserDefaults.standard.string(forKey: "last_clip") {
            UserDefaults.standard.set(s, forKey: "last_clip")
            clipOffer = s
        }
    }
}
