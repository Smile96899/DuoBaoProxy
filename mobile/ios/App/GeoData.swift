import Foundation

/// geoip.dat / geosite.dat for xray, downloaded on first use into the App Group
/// (the Go side reads them from AssetPath). sing-box uses its own rule sets.
enum GeoData {
    static let baseURL = URL(string: "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/")!

    static func needed(_ config: String) -> [String] {
        var out: [String] = []
        if config.contains("geosite:") { out.append("geosite.dat") }
        if config.contains("geoip:") { out.append("geoip.dat") }
        return out
    }

    static func has(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: AppGroup.container.appendingPathComponent(name).path)
    }

    /// Downloads the missing files before an xray start.
    static func ensure(for config: String) async throws {
        for name in needed(config) where !has(name) {
            let (tmp, resp) = try await URLSession.shared.download(from: baseURL.appendingPathComponent(name))
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
                throw Core.CoreError(message: "规则数据下载失败：\(name)")
            }
            let dest = AppGroup.container.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
        }
    }

    /// 更新规则数据: drop and download again.
    static func refresh() async throws {
        for name in ["geosite.dat", "geoip.dat"] {
            try? FileManager.default.removeItem(at: AppGroup.container.appendingPathComponent(name))
        }
        try await ensure(for: "geosite: geoip:")
    }
}
