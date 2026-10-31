import Foundation

/// Typed view of the mobile-only `ui.*` keys (same keys as Android, plus the iOS-only ones).
/// Missing keys read as the design's defaults.
struct Prefs {
    let ui: JSON

    private func bool(_ k: String, _ d: Bool) -> Bool { ui[k].bool(d) }
    private func int(_ k: String, _ d: Int) -> Int { ui[k].isNull ? d : ui[k].int(d) }
    private func str(_ k: String, _ d: String) -> String { let s = ui[k].string; return s.isEmpty ? d : s }

    // 连接
    /// iOS 按需连接: NEOnDemandRuleConnect, the system reconnects on any network.
    var onDemand: Bool { bool(Self.onDemandKey, false) }
    var launchConnect: Bool { bool(Self.launchConnectKey, false) }
    var pickFastest: Bool { bool(Self.pickFastestKey, false) }
    /// iOS: excludeLocalNetworks on the tunnel protocol.
    var excludeLocal: Bool { bool(Self.excludeLocalKey, true) }
    /// Kill Switch on iOS: includeAllNetworks.
    var includeAll: Bool { bool(Self.includeAllKey, false) }
    var autoSwitch: Bool { bool(Self.autoSwitchKey, false) }

    // 订阅
    var subAuto: Bool { bool(Self.subAutoKey, true) }
    var subIntervalH: Int { int(Self.subIntervalKey, 24) }
    var subWifiOnly: Bool { bool(Self.subWifiOnlyKey, false) }
    var subViaProxy: Bool { bool(Self.subViaProxyKey, false) }
    var subUserAgent: String { ui[Self.subUaKey].string }
    var clipDetect: Bool { bool(Self.clipDetectKey, true) }
    var urlScheme: Bool { bool(Self.urlSchemeKey, true) }

    // 通知
    var notifyState: Bool { bool(Self.notifyStateKey, true) }
    var notifyExpire: Bool { bool(Self.notifyExpireKey, true) }
    var notifyExpireDays: Int { int(Self.notifyExpireDaysKey, 3) }
    var notifyTraffic: Bool { bool(Self.notifyTrafficKey, true) }

    // 备份
    var backupNodes: Bool { bool(Self.backupNodesKey, true) }
    var backupRules: Bool { bool(Self.backupRulesKey, true) }
    var backupSettings: Bool { bool(Self.backupSettingsKey, true) }
    var lastBackup: String { ui[Self.lastBackupKey].string }

    // 其他
    var proxyMode: String { str("proxy_mode", "global") }
    var core: String { str("core", Core.singbox) }
    var selected: Int { ui["selected"].int(-1) }
    var theme: String { str("theme", "system") }
    var testURL: String { str("test_url", "http://cp.cloudflare.com/") }
    var homeGroup: String { ui[Self.homeGroupKey].string }

    static let onDemandKey = "on_demand"
    static let launchConnectKey = "launch_connect"
    static let pickFastestKey = "pick_fastest"
    static let excludeLocalKey = "exclude_local"
    static let includeAllKey = "include_all"
    static let autoSwitchKey = "auto_switch"
    static let subAutoKey = "sub_auto"
    static let subIntervalKey = "sub_interval_h"
    static let subWifiOnlyKey = "sub_wifi_only"
    static let subViaProxyKey = "sub_via_proxy"
    static let subUaKey = "sub_ua"
    static let clipDetectKey = "clip_detect"
    static let urlSchemeKey = "url_scheme"
    static let notifyStateKey = "notify_state"
    static let notifyExpireKey = "notify_expire"
    static let notifyExpireDaysKey = "notify_expire_days"
    static let notifyTrafficKey = "notify_traffic"
    static let backupNodesKey = "backup_nodes"
    static let backupRulesKey = "backup_rules"
    static let backupSettingsKey = "backup_settings"
    static let lastBackupKey = "last_backup"
    static let homeGroupKey = "home_group"
}

/// The 代理模式 choices (`ui.proxy_mode`).
let proxyModes: [(String, String)] = [("global", "全局"), ("rule", "规则"), ("direct", "直连")]
