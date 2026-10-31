import Foundation

/// The 规则 page on top of `settings.routing.{proxy,direct,block}_{domain,ip}`. Port of Android `Rules`.
enum Rules {
    static let proxy = "proxy", direct = "direct", block = "block"

    struct Preset: Identifiable {
        let id: String
        let title: String
        let outbound: String
        let detail: String
        let icon: String
        let entries: [String: [String]]
    }

    static let presets: [Preset] = [
        Preset(id: "ads", title: "广告拦截", outbound: block, detail: "GeoSite 广告", icon: "nosign", entries: ["block_domain": ["geosite:category-ads-all"]]),
        Preset(id: "cn", title: "国内网站直连", outbound: direct, detail: "GeoSite CN", icon: "arrow.right", entries: ["direct_domain": ["geosite:cn"], "direct_ip": ["geoip:cn"]]),
        Preset(id: "lan", title: "局域网直连", outbound: direct, detail: "私有网段", icon: "arrow.right", entries: ["direct_domain": ["geosite:private"], "direct_ip": ["geoip:private"]]),
        Preset(id: "media", title: "流媒体解锁", outbound: proxy, detail: "Netflix / YouTube / Disney+", icon: "globe", entries: ["proxy_domain": ["geosite:netflix", "geosite:youtube", "geosite:disney"]]),
        Preset(id: "apple", title: "苹果服务直连", outbound: direct, detail: "iCloud / App Store", icon: "arrow.right", entries: ["direct_domain": ["geosite:apple"]]),
    ]

    static let listKeys = ["proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip"]
    static let types = ["DOMAIN-SUFFIX", "DOMAIN", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "GEOSITE", "IP-CIDR", "GEOIP"]

    static func outboundLabel(_ o: String) -> String { o == proxy ? "代理" : o == direct ? "直连" : "拦截" }

    static func list(_ s: JSON, _ key: String) -> [String] { s["routing"][key].arrayValue.map(\.string) }

    static func enabled(_ s: JSON, _ p: Preset) -> Bool {
        p.entries.allSatisfy { k, vs in Set(list(s, k)).isSuperset(of: vs) }
    }

    /// The new lists after switching `p` on or off.
    static func toggle(_ s: JSON, _ p: Preset, on: Bool) -> [String: [String]] {
        p.entries.reduce(into: [:]) { out, e in
            let cur = list(s, e.key)
            out[e.key] = on ? cur + e.value.filter { !cur.contains($0) } : cur.filter { !e.value.contains($0) }
        }
    }

    struct Rule: Identifiable, Hashable {
        let key: String
        let value: String
        var id: String { key + "|" + value }
        var outbound: String { String(key.split(separator: "_").first ?? "") }
        var isIp: Bool { key.hasSuffix("_ip") }
        var type: String { Rules.typeOf(value, isIp: isIp) }
        var display: String { type == "DOMAIN-SUFFIX" ? "*." + Rules.body(value) : Rules.body(value) }
    }

    static func typeOf(_ v: String, isIp: Bool) -> String {
        if isIp { return v.hasPrefix("geoip:") ? "GEOIP" : "IP-CIDR" }
        if v.hasPrefix("full:") { return "DOMAIN" }
        if v.hasPrefix("keyword:") { return "DOMAIN-KEYWORD" }
        if v.hasPrefix("regexp:") { return "DOMAIN-REGEX" }
        if v.hasPrefix("geosite:") { return "GEOSITE" }
        return "DOMAIN-SUFFIX"
    }

    private static let prefixes = ["domain:", "full:", "keyword:", "regexp:", "geosite:", "geoip:"]

    static func body(_ v: String) -> String {
        for p in prefixes where v.hasPrefix(p) { return String(v.dropFirst(p.count)) }
        return v
    }

    /// Stored form of a typed rule: (list key, value).
    static func encode(type: String, input: String, outbound: String) -> (String, String) {
        let t = input.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "。", with: ".")
        let isIp = type == "IP-CIDR" || type == "GEOIP"
        let key = outbound + (isIp ? "_ip" : "_domain")
        func strip(_ s: String, _ p: String) -> String { s.hasPrefix(p) ? String(s.dropFirst(p.count)) : s }
        switch type {
        case "DOMAIN": return (key, "full:" + t)
        case "DOMAIN-KEYWORD": return (key, "keyword:" + t)
        case "DOMAIN-REGEX": return (key, "regexp:" + t)
        case "GEOSITE": return (key, "geosite:" + strip(t, "geosite:").lowercased())
        case "GEOIP": return (key, "geoip:" + strip(t, "geoip:").lowercased())
        case "IP-CIDR": return (key, t)
        default: return (key, "domain:" + strip(strip(t, "*."), "."))
        }
    }

    /// Entries no switched-on preset owns.
    static func custom(_ s: JSON) -> [Rule] {
        var owned = Set<String>()
        for p in presets where enabled(s, p) {
            for (k, vs) in p.entries { for v in vs { owned.insert(k + "|" + v) } }
        }
        return listKeys.flatMap { k in
            list(s, k).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && !$0.hasPrefix("#") && !owned.contains(k + "|" + $0) }
                .map { Rule(key: k, value: $0) }
        }
    }

    static func ruleSetCount(_ s: JSON) -> Int {
        Set(listKeys.flatMap { list(s, $0) }.filter { $0.hasPrefix("geosite:") || $0.hasPrefix("geoip:") }).count
    }

    static func final(_ s: JSON) -> String {
        let f = s["routing"]["final"].string
        return f.isEmpty ? proxy : f
    }
}
