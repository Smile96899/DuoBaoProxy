import Foundation
import Dbcore

/// Swift face of the gomobile `Dbcore.xcframework` (Go package `go/mobile`, ObjC prefix `DBC`).
/// Every call blocks: never call these on the main thread.
///
/// Not compiled yet — the gomobile ObjC → Swift import names below follow gomobile's rules
/// (`DBCMobile<Func>`, NSError** → `NSErrorPointer`, extra int returns → `ret0_`); fix any that
/// differ once the framework is generated.
enum Core {
    static let singbox = "sing-box"
    static let xray = "xray"

    enum Mode: Int32 { case full = 0, test = 1, export = 2 }

    struct CoreError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private static func check(_ ok: Bool, _ err: NSError?) throws {
        if !ok || err != nil { throw CoreError(message: err?.localizedDescription ?? "未知错误") }
    }

    private static func value<T>(_ v: T?, _ err: NSError?) throws -> T {
        if let err { throw CoreError(message: err.localizedDescription) }
        guard let v else { throw CoreError(message: "核心没有返回结果") }
        return v
    }

    // MARK: lifecycle

    private static var didSetup = false
    private static let setupLock = NSLock()

    /// Idempotent. The app and the extension each call it once per process.
    static func setup(debug: Bool = false) throws {
        setupLock.lock(); defer { setupLock.unlock() }
        if didSetup { return }
        let o = DBCMobileSetupOptions()
        o.basePath = AppGroup.container.path
        o.tempPath = AppGroup.tempDir.path
        o.assetPath = AppGroup.container.path
        o.platform = "ios"
        o.ownPackage = ""
        o.debug = debug
        var err: NSError?
        try check(DBCMobileSetup(o, &err), err)
        didSetup = true
    }

    static var version: String { DBCMobileVersion() }

    struct CoreInfo: Decodable, Identifiable {
        let id: String
        let version: String
        let protocols: [String]
        let running: Bool?
    }

    static func listCores() -> [CoreInfo] {
        (try? JSONDecoder().decode([CoreInfo].self, from: Data(DBCMobileListCoresJSON().utf8))) ?? []
    }

    static func supports(core: String, protocol p: String) -> Bool { DBCMobileSupports(core, p) }

    // MARK: nodes

    struct Imported { let nodes: [JSON]; let errors: [String] }

    static func importNodes(_ text: String) -> Imported {
        guard let r = DBCMobileImportNodes(text) else { return Imported(nodes: [], errors: []) }
        let nodes = ((try? JSON.parse(r.nodesJSON)) ?? .array([])).arrayValue.filter { $0.objectValue != nil }
        let errors = ((try? JSON.parse(r.errorsJSON)) ?? .array([])).arrayValue.map(\.string)
        return Imported(nodes: nodes, errors: errors)
    }

    static func shareLink(_ node: JSON, v2rayN: Bool = false) throws -> String {
        var err: NSError?
        return try value(DBCMobileShareLink(node.encoded(), v2rayN, &err), err)
    }

    static func normalize(_ node: JSON) throws -> JSON {
        var err: NSError?
        return try JSON.parse(try value(DBCMobileNormalizeNode(node.encoded(), &err), err))
    }

    // MARK: subscriptions

    struct Fetched { let body: String; let userInfo: String; let status: Int }

    static func fetchSubscription(url: String, userAgent: String, timeoutMs: Int32 = 30_000, proxied: Bool = false) throws -> Fetched {
        var err: NSError?
        let r = proxied
            ? DBCMobileFetchSubscriptionProxied(url, userAgent, timeoutMs, &err)
            : DBCMobileFetchSubscription(url, userAgent, timeoutMs, &err)
        let v = try value(r, err)
        return Fetched(body: v.body, userInfo: v.userInfo, status: Int(v.status))
    }

    // MARK: render

    static func prepareSettings(_ settings: JSON, proxyMode: String, routeParts: String, tun: Bool = true) throws -> String {
        var err: NSError?
        return try value(DBCMobilePrepareSettings(settings.encoded(), proxyMode, routeParts, tun, &err), err)
    }

    struct Rendered { let config: String; let statsTags: [String]; let frontConfig: String }

    static func render(core: String, chain: [JSON], settings: String, mode: Mode) throws -> Rendered {
        var err: NSError?
        let r = try value(DBCMobileRenderConfig(core, JSON.array(chain).encoded(), settings, mode.rawValue, &err), err)
        let tags = ((try? JSON.parse(r.statsTagsJSON)) ?? .array([])).arrayValue.map(\.string)
        return Rendered(config: r.config, statsTags: tags, frontConfig: r.frontConfig)
    }

    // MARK: run (extension only)

    static func start(core: String, config: String, statsTagsJSON: String, frontConfig: String, host: DBCMobileHostProtocol) throws {
        let o = DBCMobileStartOptions()
        o.coreID = core
        o.config = config
        o.statsTagsJSON = statsTagsJSON
        o.frontConfig = frontConfig
        var err: NSError?
        try check(DBCMobileStart(o, host, &err), err)
    }

    static func stop() {
        var err: NSError?
        _ = DBCMobileStop(&err)
    }

    static var status: String { DBCMobileStatus() }

    static func stats(tag: String) -> (up: Int64, down: Int64) {
        guard let t = DBCMobileQueryStats(tag) else { return (0, 0) }
        return (t.uplink, t.downlink)
    }

    // MARK: tests

    static func tcpPing(_ address: String, timeoutMs: Int32 = 3000) throws -> Int32 {
        var ms: Int32 = 0
        var err: NSError?
        try check(DBCMobileTCPPing(address, timeoutMs, &ms, &err), err)
        return ms
    }

    /// `config` "" = the running core (inside the extension only).
    static func urlTest(core: String, config: String, url: String, timeoutMs: Int32 = 5000) throws -> Int32 {
        var ms: Int32 = 0
        var err: NSError?
        try check(DBCMobileURLTest(core, config, url, timeoutMs, &ms, &err), err)
        return ms
    }

    // MARK: QR

    static func decodeQR(_ image: Data) throws -> String {
        var err: NSError?
        return try value(DBCMobileDecodeQR(image, &err), err)
    }

    static func encodeQR(_ text: String, size: Int32 = 480) throws -> Data {
        var err: NSError?
        return try value(DBCMobileEncodeQR(text, size, &err), err)
    }
}

/// Appends core log lines to the shared log file the app shows on 连接日志.
final class SharedLog: NSObject, DBCMobileLogHandlerProtocol {
    static let shared = SharedLog()
    private let queue = DispatchQueue(label: "duobao.log")
    private let maxBytes = 512 * 1024

    func writeLog(_ level: Int32, line: String?) {
        guard let line, !line.isEmpty else { return }
        append(line)
    }

    func append(_ line: String) {
        queue.async {
            let url = AppGroup.logFile
            let stamp = SharedLog.formatter.string(from: Date())
            let data = Data("\(stamp) \(line)\n".utf8)
            if let h = try? FileHandle(forWritingTo: url) {
                h.seekToEndOfFile()
                h.write(data)
                let size = h.offsetInFile
                try? h.close()
                if size > self.maxBytes { self.trim(url) }
            } else {
                try? data.write(to: url)
            }
        }
    }

    private func trim(_ url: URL) {
        guard let d = try? Data(contentsOf: url) else { return }
        let keep = d.suffix(maxBytes / 2)
        try? keep.write(to: url, options: .atomic)
    }

    static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()
}
