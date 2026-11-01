import Foundation

/// Paths and ids shared by the app and the PacketTunnel extension.
enum AppGroup {
    static let id = "group.com.duobao.proxy"
    static let tunnelBundleId = "com.duobao.proxy.tunnel"

    /// The App Group container: data.json, cache.db, rule sets and the shared log live here.
    static var container: URL {
        if let u = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) { return u }
        // Simulator builds without the entitlement still get a usable directory.
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static var dataFile: URL { container.appendingPathComponent("data.json") }
    static var logFile: URL { container.appendingPathComponent("tunnel.log") }
    static var tempDir: URL {
        let u = container.appendingPathComponent("tmp", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static let userAgentDefault = "DuoBaoProxy"
}
