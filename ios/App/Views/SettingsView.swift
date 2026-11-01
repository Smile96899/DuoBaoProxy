import SwiftUI

/// One settings switch: its row, its 说明 page and how it reads / writes.
struct SettingSwitch: Identifiable, Hashable {
    let title: String
    let note: String
    let get: @MainActor (AppModel) -> Bool
    let set: @MainActor (AppModel, Bool) -> Void
    var id: String { title }

    static func == (a: Self, b: Self) -> Bool { a.title == b.title }
    func hash(into h: inout Hasher) { h.combine(title) }

    /// A `ui.*` boolean.
    static func ui(_ title: String, _ key: String, _ def: Bool, _ note: String, profile: Bool = false) -> SettingSwitch {
        SettingSwitch(title: title, note: note, get: { $0.store.ui[key].bool(def) }) { m, v in
            m.setUi(key, .bool(v))
            if profile { m.applyProfile() }
        }
    }

    /// A `settings.a.b` boolean (reconnects when connected).
    static func setting(_ title: String, _ path: String, _ note: String, def: Bool = false, invert: Bool = false) -> SettingSwitch {
        SettingSwitch(title: title, note: note, get: { m in
            let p = path.split(separator: ".").map(String.init)
            let v = p.count == 1 ? m.store.settings[p[0]] : m.store.settings[p[0]][p[1]]
            return v.bool(def) != invert
        }) { m, v in m.setSetting(path, .bool(v != invert)) }
    }
}

/// Every switch of 设置 and its sub pages, keyed by title.
enum Switches {
    static let onDemand = SettingSwitch.ui("按需连接", Prefs.onDemandKey, false, "开启后网络变化时系统自动连接，关闭则只手动连接", profile: true)
    static let launchConnect = SettingSwitch.ui("启动后自动连接", Prefs.launchConnectKey, false, "开启后打开应用即连接，关闭则手动连接")
    static let pickFastest = SettingSwitch.ui("连接前测速选最快节点", Prefs.pickFastestKey, false, "开启后连接前自动选最快节点，关闭则用所选节点")
    static let excludeLocal = SettingSwitch.ui("排除本地网络", Prefs.excludeLocalKey, true, "开启后局域网设备直连，关闭则局域网也走隧道", profile: true)
    static let killSwitch = SettingSwitch.ui("Kill Switch", Prefs.includeAllKey, false, "开启后 VPN 断开即断网，关闭则断开后直连", profile: true)
    static let ipv6 = SettingSwitch.setting("IPv6", "tun.ipv6", "开启后 IPv6 走代理，关闭则拦截 IPv6 防泄露")
    static let bypassCN = SettingSwitch(title: "绕过中国大陆", note: "开启后国内网站直连，关闭则按其他规则处理",
        get: { Rules.enabled($0.store.settings, Rules.presets[1]) },
        set: { m, on in m.setSettings(Rules.toggle(m.store.settings, Rules.presets[1], on: on).map { ("routing." + $0.key, JSON.strings($0.value)) }) })
    static let autoSwitch = SettingSwitch.ui("自动切换最快节点", Prefs.autoSwitchKey, false, "开启后节点变慢自动切换，关闭则固定所选节点")
    static let dnsRouting = SettingSwitch.setting("DNS 分流", "routing.dns_routing", "开启后国内外分别解析，关闭则统一解析", def: true)
    static let dohFirst = SettingSwitch.setting("DoH 优先", "routing.doh_first", "开启后优先用加密 DNS，关闭则按列表顺序")
    static let fakeIP = SettingSwitch.setting("Fake-IP", "tun.fake_dns", "开启后连接更快但少数应用不兼容，关闭则兼容最好")
    static let mux = SettingSwitch.setting("多路复用", "mux.default_on", "开启后减少握手更快，关闭则兼容所有节点")
    static let tfo = SettingSwitch.setting("TCP Fast Open", "tcp_fast_open", "开启后首包更快，关闭则兼容性最好")
    static let subAuto = SettingSwitch.ui("自动更新", Prefs.subAutoKey, true, "开启后按间隔自动更新，关闭则只手动更新")
    static let subWifi = SettingSwitch.ui("仅 Wi-Fi 下更新", Prefs.subWifiOnlyKey, false, "开启后流量下不自动更新，关闭则随时更新")
    static let subProxy = SettingSwitch.ui("通过代理更新", Prefs.subViaProxyKey, false, "开启后经代理下载订阅，关闭则直接下载")
    static let clip = SettingSwitch.ui("自动检测剪贴板", Prefs.clipDetectKey, true, "开启后发现链接提示导入，关闭则不读剪贴板")
    static let scheme = SettingSwitch.ui("允许 URL Scheme 导入", Prefs.urlSchemeKey, true, "开启后点击节点链接可导入，关闭则不响应")
    static let notifyState = SettingSwitch.ui("连接状态变化", Prefs.notifyStateKey, true, "开启后断线或重连时提醒，关闭则不提醒")
    static let notifyExpire = SettingSwitch.ui("订阅到期提醒", Prefs.notifyExpireKey, true, "开启后到期前提醒续费，关闭则不提醒")
    static let notifyTraffic = SettingSwitch.ui("流量不足提醒", Prefs.notifyTrafficKey, true, "开启后流量快用完时提醒，关闭则不提醒")
    static let backupNodes = SettingSwitch.ui("订阅与节点", Prefs.backupNodesKey, true, "开启后备份包含订阅和节点，关闭则不包含")
    static let backupRules = SettingSwitch.ui("规则", Prefs.backupRulesKey, true, "开启后备份包含分流规则，关闭则不包含")
    static let backupSettings = SettingSwitch.ui("设置", Prefs.backupSettingsKey, true, "开启后备份包含各项设置，关闭则不包含")

    static let all: [SettingSwitch] = [
        onDemand, launchConnect, pickFastest, excludeLocal, killSwitch, ipv6, bypassCN, autoSwitch, dnsRouting, dohFirst,
        fakeIP, mux, tfo, subAuto, subWifi, subProxy, clip, scheme, notifyState, notifyExpire, notifyTraffic,
        backupNodes, backupRules, backupSettings,
    ]
}

/// A switch row without a note; tapping the title opens the 说明 page.
struct SwitchRow: View {
    @EnvironmentObject private var model: AppModel
    let sw: SettingSwitch
    var divider = false

    @Environment(\.palette) private var c

    var body: some View {
        VStack(spacing: 0) {
            if divider { Rectangle().fill(c.line).frame(height: 1) }
            HStack(spacing: 12) {
                // Only the title opens the 说明 page; the switch stays a switch.
                NavigationLink(value: sw) {
                    Text(sw.title).font(.system(size: 16, weight: .medium)).foregroundStyle(c.fg).lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                DbpToggle(isOn: sw.get(model)) { sw.set(model, $0) }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(minHeight: 56)
        }
    }
}

/// The 说明 page: the switch on top, one line of grey text under it.
struct SwitchInfoView: View {
    @EnvironmentObject private var model: AppModel
    let sw: SettingSwitch

    var body: some View {
        SubPage(title: sw.title) {
            Card {
                CardRow(title: sw.title) { DbpToggle(isOn: sw.get(model)) { sw.set(model, $0) } }
            }
            Footnote(sw.note)
        }
    }
}

enum SettingsPage: Hashable { case dns, core, subUpdate, backup, logs, about }

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c

    var body: some View {
        let s = model.store.settings
        ScrollView {
            VStack(spacing: 0) {
                PageTitle(title: "设置") {
                    Image("logo").resizable().scaledToFit().frame(width: 40, height: 40).clipShape(Circle())
                }

                SectionLabel("连接")
                Card {
                    SwitchRow(sw: Switches.onDemand)
                    SwitchRow(sw: Switches.launchConnect, divider: true)
                    SwitchRow(sw: Switches.pickFastest, divider: true)
                }

                SectionLabel("网络")
                Card {
                    link("DNS", .dns, value: dnsLabel(s))
                    link("内核", .core, value: coreLabel, divider: true)
                    SwitchRow(sw: Switches.excludeLocal, divider: true)
                    SwitchRow(sw: Switches.ipv6, divider: true)
                    SwitchRow(sw: Switches.killSwitch, divider: true)
                }

                SectionLabel("分流")
                Card {
                    MenuRow(title: "代理模式", options: proxyModes, selected: model.prefs.proxyMode) { model.setProxyMode($0) }
                    CardRow(title: "规则集", divider: true) { ValueChevron(value: "\(Rules.ruleSetCount(s)) 个") }
                    SwitchRow(sw: Switches.bypassCN, divider: true)
                }

                SectionLabel("订阅与节点")
                Card {
                    link("订阅更新", .subUpdate, value: subUpdateLabel)
                    MenuRow(title: "延迟测试地址", options: latencyTargets, selected: model.prefs.testURL, divider: true) {
                        model.setUi("test_url", .string($0))
                    }
                    SwitchRow(sw: Switches.autoSwitch, divider: true)
                }

                SectionLabel("备份与恢复")
                Card { link("备份与恢复", .backup, value: lastBackupLabel) }

                SectionLabel("外观与关于")
                Card {
                    MenuRow(title: "深色模式", options: ThemeMode.allCases.map { ($0.rawValue, $0.label) }, selected: model.prefs.theme) {
                        model.setUi("theme", .string($0))
                    }
                    link("连接日志", .logs, divider: true)
                    link("关于", .about, value: "v" + appVersion, divider: true)
                }
                Spacer(minLength: 24)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(for: SettingSwitch.self) { SwitchInfoView(sw: $0) }
        .navigationDestination(for: SettingsPage.self) { page in
            switch page {
            case .dns: DnsView()
            case .core: CoreView()
            case .subUpdate: SubUpdateView()
            case .backup: BackupView()
            case .logs: LogsView()
            case .about: AboutView()
            }
        }
    }

    private func link(_ title: String, _ page: SettingsPage, value: String = "", divider: Bool = false) -> some View {
        NavigationLink(value: page) {
            CardRow(title: title, divider: divider) { ValueChevron(value: value) }
        }
        .buttonStyle(.plain)
    }

    private func dnsLabel(_ s: JSON) -> String { s["routing"]["dns_routing"].bool(true) ? "分流 DNS" : "统一 DNS" }

    private var coreLabel: String {
        let id = model.currentCore
        let v = model.cores.first { $0.id == id }?.version ?? ""
        return v.isEmpty ? id : "\(id) \(v)"
    }

    private var subUpdateLabel: String {
        let p = model.prefs
        if !p.subAuto { return "已关闭" }
        return p.subIntervalH == 0 ? "每次启动时" : "每 \(p.subIntervalH) 小时"
    }

    private var lastBackupLabel: String {
        guard let d = ISO8601DateFormatter.lenient(model.prefs.lastBackup) else { return "" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "zh_CN")
        return f.localizedString(for: d, relativeTo: Date())
    }
}

let latencyTargets: [(String, String)] = [
    ("http://cp.cloudflare.com/", "Cloudflare"),
    ("https://www.gstatic.com/generate_204", "Google"),
    ("http://www.apple.com/library/test/success.html", "Apple"),
]

var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0" }
