import Foundation
import Combine

/// One `nodes[]` entry: `{id, group, lat, res, node}`.
struct NodeEntry: Identifiable, Equatable {
    let json: JSON
    var id: Int { json["id"].int(-1) }
    var group: String { json["group"].string }
    /// Legacy latency: 0 untested, -1 failed.
    var lat: Int { json["lat"].int(0) }
    var res: JSON { json["res"] }
    var node: JSON { json["node"] }
    var name: String { node["name"].string }
    var proto: String { node["protocol"].string }
    var server: String { node["server"].string }
    var port: Int { node["port"].int(0) }
    var title: String { name.isEmpty ? "\(server):\(port)" : name }
}

/// One `groups[]` entry; unknown keys (extra subscription-userinfo fields) are kept.
struct NodeGroup: Identifiable, Equatable {
    let json: JSON
    var id: String { name }
    var name: String { json["name"].string }
    var subUrl: String { json["sub_url"].string }
    var subIntervalMin: Int { json["sub_interval_min"].int(0) }
    var updated: String? { json["updated"].isNull ? nil : json["updated"].string }
    var upload: Int64? { json["upload"].int64 }
    var download: Int64? { json["download"].int64 }
    var total: Int64? { json["total"].int64 }
    /// Unix seconds.
    var expire: Int64? { json["expire"].int64 }
}

enum Defaults {
    static let group = "默认"

    /// Filled per key on every load when the key is absent.
    static let ui: [String: JSON] = [
        "layout": "landscape", "theme": "system", "core": "sing-box", "selected": -1,
        "proxy_mode": "global", "mode": "system", "test_url": "http://cp.cloudflare.com/",
    ]

    /// Used only when the whole `settings` object is empty.
    static let settings: JSON = [
        "log_level": "info",
        "inbound": ["address": "127.0.0.1", "port": 0, "sniff": 1],
        "routing": [
            "direct_domain": ["geosite:cn", "geosite:private"],
            "direct_ip": ["geoip:cn", "geoip:private"],
            "block_domain": ["geosite:category-ads-all"],
            "final": "proxy",
            "dns_routing": true,
            "remote_dns": "https://dns.google/dns-query",
            "direct_dns": "https://doh.pub/dns-query",
        ],
        "tun": ["enabled": true, "stack": "mixed", "mtu": 4064],
        "mux": ["protocol": "h2mux", "concurrency": 8, "default_on": false],
    ]
}

/// Port of the Android `Store` (itself `app/src/store.cpp`): everything in one `data.json`
/// inside the App Group, saved after every change. Main-actor only.
@MainActor
final class Store: ObservableObject {
    @Published private(set) var nodes: [NodeEntry] = []
    @Published private(set) var groups: [NodeGroup] = []
    @Published private(set) var settings: JSON = Defaults.settings
    @Published private(set) var ui: JSON = .emptyObject

    private var nodeList: [JSON] = []
    private var groupList: [JSON] = []
    private var nextId = 1

    init() { load() }

    // MARK: load / save

    func load() {
        let text = (try? String(contentsOf: AppGroup.dataFile, encoding: .utf8)) ?? ""
        let root = (try? JSON.parse(text)) ?? .emptyObject
        apply(root)
    }

    private func apply(_ root: JSON) {
        nodeList = root["nodes"].arrayValue.filter { $0.objectValue != nil }
        groupList = root["groups"].arrayValue.filter { $0.objectValue != nil }
        if groupList.isEmpty { groupList = [["name": .string(Defaults.group)]] }
        var u = root["ui"].objectValue ?? [:]
        for (k, v) in Defaults.ui where u[k] == nil { u[k] = v }
        ui = .object(u)
        settings = root["settings"].isEmptyObject ? Defaults.settings : root["settings"]
        nextId = root["next_id"].int(1)
        publish()
    }

    private func publish() {
        nodes = nodeList.map(NodeEntry.init)
        groups = groupList.map(NodeGroup.init)
    }

    var root: JSON {
        ["nodes": .array(nodeList), "groups": .array(groupList), "settings": settings, "ui": ui, "next_id": .int(Int64(nextId))]
    }

    @discardableResult
    func save() -> Bool {
        do {
            try Data(root.encoded(pretty: true).utf8).write(to: AppGroup.dataFile, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    // MARK: nodes

    func node(_ id: Int) -> NodeEntry? { nodes.first { $0.id == id } }

    @discardableResult
    func addNodes(_ list: [JSON], group: String) -> Int {
        var n = 0
        for node in list where !node.isEmptyObject {
            nodeList.append(["id": .int(Int64(nextId)), "group": .string(group), "lat": 0, "node": node])
            nextId += 1
            n += 1
        }
        if n > 0 { commitNodes() }
        return n
    }

    @discardableResult
    func addNode(_ node: JSON, group: String) -> Int {
        guard !node.isEmptyObject else { return -1 }
        let id = nextId
        addNodes([node], group: group)
        return id
    }

    /// Shallow merge of `patch` into the entry (not into `node`).
    func updateNode(_ id: Int, patch: JSON) {
        guard let i = nodeList.firstIndex(where: { $0["id"].int(-1) == id }) else { return }
        nodeList[i] = nodeList[i].merged(patch)
        commitNodes()
    }

    func removeNodes(_ ids: Set<Int>) {
        if ids.contains(ui["selected"].int(-1)) { setUi("selected", -1) }
        nodeList.removeAll { ids.contains($0["id"].int(-1)) }
        commitNodes()
    }

    func removeGroupNodes(_ group: String) {
        nodeList.removeAll { $0["group"].string == group }
        commitNodes()
    }

    func setNodesGroup(_ ids: Set<Int>, group: String) {
        for i in nodeList.indices where ids.contains(nodeList[i]["id"].int(-1)) {
            nodeList[i]["group"] = .string(group)
        }
        commitNodes()
    }

    // MARK: groups

    func addGroup(_ g: JSON) {
        guard !groupList.contains(where: { $0["name"] == g["name"] }) else { return }
        groupList.append(g)
        commitGroups()
    }

    func updateGroup(_ name: String, patch: JSON) {
        guard let i = groupList.firstIndex(where: { $0["name"].string == name }) else { return }
        groupList[i] = groupList[i].merged(patch)
        commitGroups()
    }

    /// Renames a group and moves its nodes along. False when `to` is empty or taken.
    @discardableResult
    func renameGroup(_ from: String, to: String) -> Bool {
        if from == to { return true }
        guard !to.isEmpty, !groupList.contains(where: { $0["name"].string == to }),
              let i = groupList.firstIndex(where: { $0["name"].string == from }) else { return false }
        groupList[i]["name"] = .string(to)
        for j in nodeList.indices where nodeList[j]["group"].string == from { nodeList[j]["group"] = .string(to) }
        publish()
        save()
        return true
    }

    func removeGroup(_ name: String) {
        nodeList.removeAll { $0["group"].string == name }
        groupList.removeAll { $0["name"].string == name }
        if groupList.isEmpty { groupList = [["name": .string(Defaults.group)]] }
        publish()
        save()
    }

    func moveGroups(from: IndexSet, to: Int) {
        groupList.move(fromOffsets: from, toOffset: to)
        commitGroups()
    }

    // MARK: settings / ui

    /// `key` is `"a"` or `"a.b"`; always saves.
    func setSetting(_ key: String, _ value: JSON) {
        let parts = key.split(separator: ".").map(String.init)
        if parts.count == 1 {
            settings[key] = value
        } else {
            var sub = settings[parts[0]].objectValue.map(JSON.object) ?? .emptyObject
            sub[parts[1]] = value
            settings[parts[0]] = sub
        }
        save()
    }

    /// No-op when unchanged.
    func setUi(_ key: String, _ value: JSON) {
        guard ui[key] != value else { return }
        ui[key] = value
        save()
    }

    // MARK: backup

    /// 备份内容: data.json with only the chosen parts.
    func exportJSON(nodes withNodes: Bool, rules: Bool, settings withSettings: Bool) -> String {
        var set = settings.objectValue ?? [:]
        let ruleKeys: Set<String> = ["routing", "apps"]
        if !rules { set = set.filter { !ruleKeys.contains($0.key) } }
        if !withSettings { set = set.filter { ruleKeys.contains($0.key) } }
        let r: JSON = [
            "nodes": withNodes ? .array(nodeList) : [],
            "groups": withNodes ? .array(groupList) : [["name": .string(Defaults.group)]],
            "settings": .object(set),
            "ui": withSettings ? ui : .emptyObject,
            "next_id": .int(Int64(nextId)),
        ]
        return r.encoded(pretty: true)
    }

    /// Replaces everything with an imported data.json.
    func importReplace(_ text: String) throws {
        let r = try JSON.parse(text)
        guard ["nodes", "groups", "settings", "ui", "next_id"].contains(where: { !r[$0].isNull }) else {
            throw Core.CoreError(message: "不是 data.json")
        }
        apply(r)
        save()
    }

    /// 恢复默认设置: settings back to the first-run defaults.
    func resetSettings() {
        settings = Defaults.settings
        save()
    }

    // MARK: private

    private func commitNodes() {
        nodes = nodeList.map(NodeEntry.init)
        save()
    }

    private func commitGroups() {
        groups = groupList.map(NodeGroup.init)
        save()
    }
}
