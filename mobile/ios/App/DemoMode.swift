import Foundation

/// Launch arguments for screenshots on the simulator (used by the CI job):
///
///   -demo              fill an empty store with sample groups and nodes
///   -tab home|subs|rules|settings
///   -page dns|core|subUpdate|backup|logs|about   (a 设置 sub page)
///   -info <switch title>                         (a switch's 说明 page)
enum DemoMode {
    static var args: [String] { ProcessInfo.processInfo.arguments }

    static func value(_ key: String) -> String? {
        guard let i = args.firstIndex(of: "-" + key), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    static var enabled: Bool { args.contains("-demo") }

    static var tab: Tab {
        switch value("tab") {
        case "subs": return .subs
        case "rules": return .rules
        case "settings": return .settings
        default: return .home
        }
    }

    static var page: SettingsPage? {
        switch value("page") {
        case "dns": return .dns
        case "core": return .core
        case "subUpdate": return .subUpdate
        case "backup": return .backup
        case "logs": return .logs
        case "about": return .about
        default: return nil
        }
    }

    static var info: SettingSwitch? {
        guard let t = value("info") else { return nil }
        return Switches.all.first { $0.title == t }
    }

    /// Sample data, only when there are no nodes yet.
    @MainActor
    static func seed(_ store: Store) {
        guard enabled, store.nodes.isEmpty else { return }
        store.addGroup(["name": "极速云 Pro", "sub_url": "https://example.com/sub", "sub_interval_min": 1440,
                        "upload": 3_221_225_472, "download": 21_474_836_480, "total": 107_374_182_400, "expire": 1_830_000_000,
                        "updated": .string(ISO8601DateFormatter().string(from: Date().addingTimeInterval(-3600)))])
        store.addGroup(["name": "备用机场"])
        let sample: [(String, String, String, Int)] = [
            (Defaults.group, "美国 · 纽约", "vless", 24),
            (Defaults.group, "新加坡 · 01", "vmess", 38),
            (Defaults.group, "日本 · 东京", "shadowsocks", 52),
            ("极速云 Pro", "香港 · 03", "trojan", 18),
            ("极速云 Pro", "台湾 · 01", "hysteria2", 46),
            ("极速云 Pro", "德国 · 法兰克福", "vless", 186),
            ("备用机场", "英国 · 伦敦", "trojan", -1),
            ("备用机场", "罗马尼亚", "socks", 1629),
        ]
        for (i, s) in sample.enumerated() {
            store.addNodes([["name": .string(s.1), "protocol": .string(s.2), "server": .string("198.51.100.\(10 + i)"), "port": 443]], group: s.0)
        }
        for e in store.nodes {
            guard let s = sample.first(where: { $0.1 == e.name }) else { continue }
            let r: JSON = s.3 > 0 ? ["ms": .int(Int64(s.3))] : ["err": "超时"]
            store.updateNode(e.id, patch: ["res": ["url": r], "lat": .int(Int64(s.3))])
        }
        store.setUi("selected", .int(Int64(store.nodes.first?.id ?? -1)))
        store.setSetting("routing.proxy_domain", ["domain:openai.com", "keyword:google"])
        SharedLog.shared.append("演示数据已载入")
    }
}
