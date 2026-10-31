import Foundation
import Combine
import NetworkExtension
import SwiftUI

/// `ip=` / `loc=` of a Cloudflare trace.
struct IpInfo: Equatable {
    let ip: String
    let cc: String

    static let traceURL = URL(string: "https://www.cloudflare.com/cdn-cgi/trace")!

    static func parse(_ body: String) -> IpInfo? {
        var kv: [String: String] = [:]
        for line in body.split(whereSeparator: \.isNewline) {
            let p = line.split(separator: "=", maxSplits: 1)
            if p.count == 2 { kv[String(p[0]).trimmingCharacters(in: .whitespaces)] = String(p[1]).trimmingCharacters(in: .whitespaces) }
        }
        guard let ip = kv["ip"], !ip.isEmpty else { return nil }
        return IpInfo(ip: ip, cc: (kv["loc"] ?? "").uppercased())
    }

    /// On iOS the app's own traffic follows the VPN: connected → exit IP, else the local IP.
    static func fetch() async -> IpInfo? {
        var req = URLRequest(url: traceURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        req.setValue("DuoBaoProxy", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: req) else { return nil }
        return parse(String(decoding: data, as: UTF8.self))
    }
}

struct Traffic: Equatable {
    var upRate: Int64 = 0
    var downRate: Int64 = 0
    var upTotal: Int64 = 0
    var downTotal: Int64 = 0
}

/// The app's controller: the iOS counterpart of Android `AppController`.
@MainActor
final class AppModel: ObservableObject {
    let store = Store()
    let tunnel = TunnelController()

    @Published private(set) var busy = false
    @Published private(set) var statusText = "未连接"
    @Published private(set) var traffic = Traffic()
    @Published private(set) var localInfo: IpInfo?
    @Published private(set) var exitInfo: IpInfo?
    @Published private(set) var online = true
    @Published var toast: String?
    @Published private(set) var cores: [Core.CoreInfo] = []
    @Published private(set) var testing: Set<Int> = []

    private var bag = Set<AnyCancellable>()
    private var pollTask: Task<Void, Never>?
    private var statsTag: String?
    private var lastStats: (up: Int64, down: Int64)?
    private var foreground = true
    private var userStopped = false

    var prefs: Prefs { Prefs(ui: store.ui) }
    var connected: Bool { tunnel.isConnected }

    init() {
        // Forward the stores' changes so views observing the model redraw.
        store.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &bag)
        tunnel.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &bag)
        tunnel.$status.removeDuplicates().sink { [weak self] s in self?.onStatus(s) }.store(in: &bag)
        Task.detached(priority: .userInitiated) {
            try? Core.setup()
            let list = Core.listCores()
            await MainActor.run { self.cores = list }
        }
    }

    // MARK: app lifecycle

    func onLaunch() {
        DemoMode.seed(store)
        if DemoMode.enabled {
            // The simulator has no VPN: show the map with a sample local country.
            localInfo = IpInfo(ip: "203.0.113.7", cc: "KH")
            return
        }
        refreshIpInfo()
        if prefs.launchConnect && !connected { connect() }
        scheduleSubscriptions()
    }

    func onScene(_ phase: ScenePhase) {
        foreground = phase == .active
        if foreground && connected { startPolling() }
        if !foreground { stopPolling() }
    }

    // MARK: nodes / selection

    var currentCore: String { prefs.core }
    var selectedId: Int { prefs.selected }
    var selectedNode: NodeEntry? { store.node(selectedId) }

    func supports(_ e: NodeEntry) -> Bool {
        cores.isEmpty || Core.supports(core: currentCore, protocol: e.proto)
    }

    /// `ms` of the latest url test, -1 failed, 0 untested.
    func latency(_ e: NodeEntry) -> Int {
        let r = e.res["url"]
        if !r["err"].isNull { return -1 }
        let ms = r["ms"].int(0)
        return ms > 0 ? ms : e.lat
    }

    func select(_ id: Int) {
        guard let e = store.node(id), supports(e) else { return }
        let was = connected && selectedId != id
        store.setUi("selected", .int(Int64(id)))
        if was { reconnect() }
    }

    func setCore(_ id: String) {
        guard id != currentCore else { return }
        let was = connected
        store.setUi("core", .string(id))
        if let e = selectedNode, !supports(e) { store.setUi("selected", -1) }
        if was { reconnect() }
    }

    func setProxyMode(_ m: String) {
        store.setUi("proxy_mode", .string(m))
        if connected { reconnect() }
    }

    // MARK: connect

    func toggle() { connected || tunnel.isBusy ? disconnect() : connect() }

    func connect() {
        guard !busy, !connected else { return }
        Task {
            busy = true
            defer { busy = false }
            userStopped = false
            if prefs.pickFastest { await pickFastest() }
            guard let entry = selectedNode, !entry.node.isEmptyObject else { statusText = "先选一个节点"; return }
            guard supports(entry) else { statusText = "\(currentCore) 不支持 \(entry.proto)"; return }
            statusText = "正在连接…"
            do {
                let core = currentCore
                let settings = store.settings
                let mode = prefs.proxyMode
                let parts = store.ui["route_parts"].string
                let node = entry.node
                let rendered = try await Task.detached(priority: .userInitiated) { () throws -> Core.Rendered in
                    try Core.setup()
                    let s = try Core.prepareSettings(settings, proxyMode: mode, routeParts: parts)
                    return try Core.render(core: core, chain: [node], settings: s, mode: .full)
                }.value
                if core == Core.xray { try await GeoData.ensure(for: rendered.config) }
                statsTag = rendered.statsTags.first
                let options: [String: NSObject] = [
                    TunnelOptionKey.coreId: core as NSString,
                    TunnelOptionKey.config: rendered.config as NSString,
                    TunnelOptionKey.statsTags: JSON.strings(rendered.statsTags).encoded() as NSString,
                    TunnelOptionKey.frontConfig: rendered.frontConfig as NSString,
                    TunnelOptionKey.nodeName: entry.title as NSString,
                    TunnelOptionKey.nodeId: NSNumber(value: entry.id),
                ]
                try await tunnel.start(options: options, prefs: prefs)
            } catch {
                statusText = error.localizedDescription
                log("连接失败：" + error.localizedDescription)
            }
        }
    }

    func disconnect() {
        userStopped = true
        tunnel.stop()
    }

    func reconnect() {
        guard connected else { return }
        tunnel.stop()
        Task {
            // Wait for the old tunnel to go down before starting the new one.
            for _ in 0..<50 where tunnel.status != .disconnected { try? await Task.sleep(nanoseconds: 100_000_000) }
            connect()
        }
    }

    /// Reapplies the VPN profile (按需连接, Kill Switch, 排除本地网络) without reconnecting.
    func applyProfile() {
        Task { _ = try? await tunnel.configure(prefs: prefs) }
    }

    private func onStatus(_ s: NEVPNStatus) {
        switch s {
        case .connected:
            statusText = "已连接"
            log("已连接：" + (selectedNode?.title ?? ""))
            startPolling()
            Task {
                try? await Task.sleep(nanoseconds: 800_000_000)
                await liveLatency()
                refreshIpInfo()
            }
        case .connecting, .reasserting:
            statusText = "正在连接…"
        case .disconnecting:
            statusText = "正在断开…"
        case .disconnected, .invalid:
            stopPolling()
            traffic = Traffic()
            exitInfo = nil
            if statusText == "已连接" || statusText == "正在断开…" || statusText == "正在连接…" {
                statusText = "未连接"
                Task {
                    if let err = await tunnel.fetchLastError(), !userStopped {
                        statusText = err
                        log("已断开：" + err)
                    }
                }
            }
            refreshIpInfo()
        @unknown default:
            break
        }
    }

    // MARK: polling (stats every second while visible, latency every 5 s)

    private func startPolling() {
        stopPolling()
        lastStats = nil
        pollTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                guard self.connected, self.foreground else { continue }
                if let tag = self.statsTag, let r = await self.tunnel.send(TunnelRequest(kind: .stats, tag: tag)) {
                    let up = r.uplink ?? 0, down = r.downlink ?? 0
                    if let last = self.lastStats {
                        self.traffic = Traffic(upRate: max(0, up - last.up), downRate: max(0, down - last.down), upTotal: up, downTotal: down)
                    }
                    self.lastStats = (up, down)
                }
                tick += 1
                if tick % 5 == 0 { await self.liveLatency() }
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// `URLTest` on the running engine, saved as the node's url result.
    func liveLatency() async {
        guard connected, let id = selectedNode?.id else { return }
        let r = await tunnel.send(TunnelRequest(kind: .urlTest, url: prefs.testURL, timeoutMs: 5000))
        if let ms = r?.ms, r?.ok == true { saveResult(id, ["ms": .int(Int64(max(1, ms)))]) }
        else if r != nil { saveResult(id, ["err": "超时", "detail": .string(r?.error ?? "")]) }
    }

    func refreshIpInfo() {
        Task {
            let info = await IpInfo.fetch()
            online = info != nil || connected
            if connected { exitInfo = info } else { localInfo = info ?? localInfo }
        }
    }

    // MARK: latency tests (TCP to every node of a group)

    func testGroup(_ group: String) {
        let ids = store.nodes.filter { $0.group == group }.map(\.id)
        test(ids)
    }

    func test(_ ids: [Int]) {
        let targets = ids.compactMap { store.node($0) }.filter { !$0.server.isEmpty && $0.port > 0 }
        testing.formUnion(targets.map(\.id))
        for e in targets {
            let addr = e.server.contains(":") ? "[\(e.server)]:\(e.port)" : "\(e.server):\(e.port)"
            Task {
                let r = await Task.detached { () -> Result<Int32, Error> in Result { try Core.tcpPing(addr, timeoutMs: 3000) } }.value
                switch r {
                case .success(let ms): saveResult(e.id, ["ms": .int(Int64(max(1, ms)))])
                case .failure(let err): saveResult(e.id, ["err": "超时", "detail": .string(err.localizedDescription)])
                }
                testing.remove(e.id)
            }
        }
    }

    /// Tests the selected node through the running tunnel, else by TCP.
    func testCurrent() {
        if connected { Task { await liveLatency() } }
        else if let id = selectedNode?.id { test([id]) }
    }

    private func saveResult(_ id: Int, _ result: JSON) {
        guard let e = store.node(id) else { return }
        var res = e.res.objectValue ?? [:]
        res["url"] = result
        let lat = result["err"].isNull ? result["ms"].int(0) : -1
        store.updateNode(id, patch: ["res": .object(res), "lat": .int(Int64(lat))])
    }

    private func pickFastest() async {
        guard let cur = selectedNode else { return }
        let group = store.nodes.filter { $0.group == cur.group && supports($0) }
        var best: (Int, Int32)?
        for e in group where !e.server.isEmpty && e.port > 0 {
            let addr = "\(e.server):\(e.port)"
            if let ms = try? await Task.detached(operation: { try Core.tcpPing(addr, timeoutMs: 2000) }).value,
               best == nil || ms < best!.1 {
                best = (e.id, ms)
            }
        }
        if let b = best { store.setUi("selected", .int(Int64(b.0))) }
    }

    // MARK: import / subscriptions

    @discardableResult
    func importText(_ text: String, group: String = Defaults.group) async -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { show("没有可导入的内容"); return false }
        if Self.isSubscriptionURL(t) { return await addSubscription(t) }
        let r = await Task.detached { Core.importNodes(t) }.value
        let n = store.addNodes(r.nodes, group: group)
        show("导入 \(n) 个节点到「\(group)」" + (r.errors.isEmpty ? "" : "，\(r.errors.count) 行失败"))
        autoSelect()
        return n > 0
    }

    static func isSubscriptionURL(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return (t.hasPrefix("http://") || t.hasPrefix("https://")) && !t.contains(where: \.isWhitespace)
    }

    @discardableResult
    func addSubscription(_ url: String, name: String? = nil) async -> Bool {
        let existing = store.groups.first { $0.subUrl == url }
        let groupName: String
        if let existing {
            groupName = existing.name
        } else {
            groupName = name.flatMap { $0.isEmpty ? nil : $0 } ?? Self.groupName(for: url, taken: Set(store.groups.map(\.name)))
            store.addGroup(["name": .string(groupName), "sub_url": .string(url), "sub_interval_min": 1440])
        }
        return await updateSubscription(groupName)
    }

    static func groupName(for url: String, taken: Set<String>) -> String {
        let host = URL(string: url.trimmingCharacters(in: .whitespaces))?.host ?? "订阅"
        if !taken.contains(host) { return host }
        var i = 2
        while taken.contains("\(host) (\(i))") { i += 1 }
        return "\(host) (\(i))"
    }

    @discardableResult
    func updateSubscription(_ name: String) async -> Bool {
        guard let g = store.groups.first(where: { $0.name == name }), !g.subUrl.isEmpty else { return false }
        let ua = prefs.subUserAgent
        let proxied = prefs.subViaProxy && connected
        let url = g.subUrl
        let fetched = await Task.detached { () -> Result<Core.Fetched, Error> in
            Result {
                try Core.setup()
                return try Core.fetchSubscription(url: url, userAgent: ua, proxied: proxied)
            }
        }.value
        let r: Core.Fetched
        switch fetched {
        case .success(let v): r = v
        case .failure(let e): show("订阅 \(name) 更新失败：" + e.localizedDescription); return false
        }
        guard r.status == 200 else { show("订阅 \(name) 更新失败：HTTP \(r.status)"); return false }
        var patch: [String: JSON] = ["updated": .string(ISO8601DateFormatter().string(from: Date()))]
        for kv in r.userInfo.split(separator: ";") {
            let p = kv.trimmingCharacters(in: .whitespaces).split(separator: "=")
            if p.count == 2, let v = Double(p[1]) { patch[String(p[0])] = .int(Int64(v)) }
        }
        store.updateGroup(name, patch: .object(patch))
        let body = r.body
        let parsed = await Task.detached { Core.importNodes(body) }.value
        guard !parsed.nodes.isEmpty else { show("订阅 \(name) 没有解析出节点，保留原有节点"); return false }
        store.removeGroupNodes(name)
        let n = store.addNodes(parsed.nodes, group: name)
        if selectedNode == nil { store.setUi("selected", -1) }
        autoSelect()
        show("导入 \(n) 个节点到「\(name)」")
        return true
    }

    func updateAllSubscriptions() {
        Task { for g in store.groups where !g.subUrl.isEmpty { await updateSubscription(g.name) } }
    }

    /// 自动更新: refresh subscriptions older than the interval (checked at launch).
    private func scheduleSubscriptions() {
        guard prefs.subAuto else { return }
        let hours = prefs.subIntervalH
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        Task {
            for g in store.groups where !g.subUrl.isEmpty {
                let last = g.updated.flatMap { iso.date(from: $0) ?? plain.date(from: $0) } ?? .distantPast
                if hours == 0 || Date().timeIntervalSince(last) > Double(hours) * 3600 { await updateSubscription(g.name) }
            }
        }
    }

    private func autoSelect() {
        guard selectedNode == nil else { return }
        if let first = store.nodes.first(where: supports) ?? store.nodes.first {
            store.setUi("selected", .int(Int64(first.id)))
        }
    }

    // MARK: groups / nodes editing

    func createGroup(_ name: String) -> Bool {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, !store.groups.contains(where: { $0.name == n }) else { return false }
        store.addGroup(["name": .string(n)])
        return true
    }

    func removeNodes(_ ids: Set<Int>) { store.removeNodes(ids) }

    func shareLink(_ id: Int) -> String? {
        guard let e = store.node(id) else { return nil }
        return try? Core.shareLink(e.node)
    }

    // MARK: settings

    func setSetting(_ key: String, _ v: JSON) {
        store.setSetting(key, v)
        if connected { reconnect() }
    }

    /// Several settings at once, reconnecting only once.
    func setSettings(_ pairs: [(String, JSON)]) {
        for (k, v) in pairs { store.setSetting(k, v) }
        if connected && !pairs.isEmpty { reconnect() }
    }

    func setUi(_ key: String, _ v: JSON) { store.setUi(key, v) }

    // MARK: messages

    func show(_ text: String) {
        toast = text
        log(text)
    }

    func log(_ line: String) { SharedLog.shared.append(line) }
}
