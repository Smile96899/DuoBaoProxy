import SwiftUI
import UniformTypeIdentifiers

// MARK: - DNS

let dnsRemoteDefault = "https://dns.google/dns-query"
let dnsDirectDefault = "https://doh.pub/dns-query"

func dnsKind(_ addr: String) -> String {
    let a = addr.lowercased()
    if a == "local" || a == "localhost" || a.isEmpty { return "系统" }
    if a.hasPrefix("https://") || a.hasPrefix("http://") { return "DoH" }
    if a.hasPrefix("h3://") { return "DoH3" }
    if a.hasPrefix("tls://") { return "DoT" }
    if a.hasPrefix("quic://") { return "DoQ" }
    if a.hasPrefix("tcp://") { return "TCP" }
    return "UDP"
}

func dnsServers(_ v: String) -> [String] {
    var seen = Set<String>()
    return v.split(whereSeparator: { $0 == "\n" || $0 == "," }).map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty && seen.insert($0).inserted }
}

struct DnsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c
    @State private var custom = false
    @State private var adding: String?

    private var remote: String { model.store.settings["routing"]["remote_dns"].string.nilIfEmpty ?? dnsRemoteDefault }
    private var direct: String { model.store.settings["routing"]["direct_dns"].string.nilIfEmpty ?? dnsDirectDefault }

    private var mode: String {
        if custom { return "custom" }
        if remote == dnsRemoteDefault && direct == dnsDirectDefault { return "auto" }
        if remote == dnsRemoteDefault && direct == "local" { return "system" }
        return "custom"
    }

    var body: some View {
        SubPage(title: "DNS 设置") {
            Picker("", selection: Binding(get: { mode }, set: setMode)) {
                Text("自动").tag("auto")
                Text("系统").tag("system")
                Text("自定义").tag("custom")
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 20).padding(.top, 8)

            ForEach([("direct_dns", "国内 DNS", direct), ("remote_dns", "国外 DNS", remote)], id: \.0) { key, title, value in
                let list = dnsServers(value)
                SectionLabel(title)
                Card {
                    ForEach(Array(list.enumerated()), id: \.1) { i, addr in
                        CardRow(title: addr == "local" ? "系统 DNS" : addr, divider: i > 0) {
                            Text(dnsKind(addr)).font(.system(size: 14)).foregroundStyle(c.fg2)
                            if mode == "custom" && list.count > 1 {
                                Button { model.setSetting("routing.\(key)", .string(list.filter { $0 != addr }.joined(separator: "\n"))) } label: {
                                    Image(systemName: "xmark").foregroundStyle(c.fg3)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    if mode == "custom" {
                        Rectangle().fill(c.line).frame(height: 1)
                        Button { adding = key } label: {
                            Label("添加服务器", systemImage: "plus").font(.system(size: 16, weight: .semibold)).foregroundStyle(c.accent)
                                .frame(maxWidth: .infinity, minHeight: 50)
                        }
                    }
                }
            }

            SectionLabel("高级")
            Card {
                SwitchRow(sw: Switches.dnsRouting)
                SwitchRow(sw: Switches.dohFirst, divider: true)
                SwitchRow(sw: Switches.fakeIP, divider: true)
            }
            Footnote("国内域名用国内 DNS 解析，其余走国外 DNS，避免 DNS 污染和泄露。支持 223.5.5.5、tls://、https://、quic:// 等写法，填 local 使用系统 DNS。")
        }
        .onAppear { custom = mode == "custom" }
        .sheet(item: Binding(get: { adding.map(IdentifiedString.init) }, set: { adding = $0?.value })) { k in
            PromptSheet(title: "添加服务器", placeholder: "223.5.5.5 或 https://…", confirm: "添加", text: "") { addr in
                let cur = k.value == "direct_dns" ? direct : remote
                model.setSetting("routing.\(k.value)", .string((dnsServers(cur) + [addr.trimmingCharacters(in: .whitespaces)]).joined(separator: "\n")))
            }
        }
    }

    private func setMode(_ m: String) {
        custom = m == "custom"
        switch m {
        case "auto": model.setSettings([("routing.remote_dns", .string(dnsRemoteDefault)), ("routing.direct_dns", .string(dnsDirectDefault))])
        case "system": model.setSettings([("routing.remote_dns", .string(dnsRemoteDefault)), ("routing.direct_dns", "local")])
        default: break
        }
    }
}

struct IdentifiedString: Identifiable {
    let value: String
    var id: String { value }
}

// MARK: - 内核

struct CoreView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c

    var body: some View {
        let info = model.cores.first { $0.id == model.currentCore }
        SubPage(title: "内核") {
            Card {
                HStack(spacing: 14) {
                    IconTile(systemImage: "cpu", bg: c.accent, tint: c.accentFg, size: 48)
                    VStack(alignment: .leading, spacing: 3) {
                        Text([model.currentCore, info?.version ?? ""].filter { !$0.isEmpty }.joined(separator: " "))
                            .font(.system(size: 17, weight: .semibold)).foregroundStyle(c.fg)
                        Text((model.connected ? "运行中" : "未运行") + " · 支持 \(info?.protocols.count ?? 0) 种协议")
                            .font(.system(size: 13)).foregroundStyle(c.fg2)
                    }
                    Spacer()
                }
                .padding(16)
            }
            SectionLabel("切换内核")
            Card {
                let ids = model.cores.isEmpty ? [Core.singbox, Core.xray] : model.cores.map(\.id)
                ForEach(Array(ids.enumerated()), id: \.1) { i, id in
                    CheckRow(title: id == Core.xray ? "Xray" : id, checked: id == model.currentCore, divider: i > 0) { model.setCore(id) }
                }
            }
            SectionLabel("选项")
            Card {
                SwitchRow(sw: Switches.mux)
                SwitchRow(sw: Switches.tfo, divider: true)
                MenuRow(title: "日志级别", options: [("debug", "调试"), ("info", "信息"), ("warn", "警告"), ("error", "错误")],
                        selected: model.store.settings["log_level"].string.nilIfEmpty ?? "info", divider: true) {
                    model.setSetting("log_level", .string($0))
                }
            }
            Footnote("切换内核后会自动重新连接，节点配置无需修改。当前内核不支持的协议会在首页变灰。")
        }
    }
}

// MARK: - 订阅更新

struct SubUpdateView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let p = model.prefs
        SubPage(title: "订阅更新") {
            Card { SwitchRow(sw: Switches.subAuto) }
            if p.subAuto {
                SectionLabel("更新间隔")
                Card {
                    ForEach(Array([(6, "每 6 小时"), (12, "每 12 小时"), (24, "每 24 小时"), (0, "每次启动时")].enumerated()), id: \.1.0) { i, e in
                        CheckRow(title: e.1, checked: p.subIntervalH == e.0, divider: i > 0) { model.setUi(Prefs.subIntervalKey, .int(Int64(e.0))) }
                    }
                }
            }
            SectionLabel("更新方式")
            Card {
                SwitchRow(sw: Switches.subWifi)
                SwitchRow(sw: Switches.subProxy, divider: true)
            }
            SectionLabel("导入")
            Card {
                SwitchRow(sw: Switches.clip)
                SwitchRow(sw: Switches.scheme, divider: true)
            }
            Card {
                CardRow(title: "立即更新全部", titleColor: nil, action: { model.updateAllSubscriptions() }) { ValueChevron() }
            }
            .padding(.top, 16)
        }
    }
}

// MARK: - 备份与恢复

struct DataFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String

    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

struct BackupView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c
    @State private var exporting: DataFile?
    @State private var importing = false
    @State private var confirmReset = false

    var body: some View {
        SubPage(title: "备份与恢复") {
            HStack(spacing: 12) {
                bigButton("导出配置", "square.and.arrow.up") {
                    let p = model.prefs
                    exporting = DataFile(text: model.store.exportJSON(nodes: p.backupNodes, rules: p.backupRules, settings: p.backupSettings))
                }
                bigButton("导入配置", "square.and.arrow.down") { importing = true }
            }
            .padding(.horizontal, 20).padding(.top, 8)

            SectionLabel("备份内容")
            Card {
                SwitchRow(sw: Switches.backupNodes)
                SwitchRow(sw: Switches.backupRules, divider: true)
                SwitchRow(sw: Switches.backupSettings, divider: true)
            }
            Footnote("备份文件就是桌面版的 data.json，可以在电脑和手机之间互相导入。")

            Button { confirmReset = true } label: {
                Text("恢复默认设置").font(.system(size: 16, weight: .semibold)).foregroundStyle(c.bad)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(c.panel, in: RoundedRectangle(cornerRadius: 18))
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(c.line, lineWidth: 1))
            }
            .padding(.horizontal, 20).padding(.top, 12)
        }
        .fileExporter(isPresented: Binding(get: { exporting != nil }, set: { if !$0 { exporting = nil } }),
                      document: exporting, contentType: .json, defaultFilename: "data.json") { r in
            if case .success = r {
                model.setUi(Prefs.lastBackupKey, .string(ISO8601DateFormatter().string(from: Date())))
                model.show("已导出")
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { r in
            guard case .success(let url) = r else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            do {
                try model.store.importReplace(String(contentsOf: url, encoding: .utf8))
                model.show("已导入")
            } catch {
                model.show("导入失败：" + error.localizedDescription)
            }
        }
        .confirmationDialog("恢复默认设置？节点和分组会保留。", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("恢复", role: .destructive) { model.store.resetSettings(); model.show("已恢复默认设置") }
        }
    }

    private func bigButton(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 22)).foregroundStyle(c.accent)
                Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(c.fg)
            }
            .frame(maxWidth: .infinity, minHeight: 92)
            .background(c.panel, in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(c.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 连接日志

struct LogsView: View {
    @Environment(\.palette) private var c
    @State private var lines: [String] = []
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(lines.enumerated()), id: \.0) { i, l in
                        Text(l).font(.system(size: 12, design: .monospaced)).foregroundStyle(c.fg).textSelection(.enabled).id(i)
                    }
                }
                .padding(16)
            }
            .onChange(of: lines.count) { n in if n > 0 { proxy.scrollTo(n - 1, anchor: .bottom) } }
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("连接日志")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ShareLink(item: AppGroup.logFile) { Image(systemName: "square.and.arrow.up") }
            Button("清空") {
                try? Data().write(to: AppGroup.logFile)
                lines = []
            }
        }
        .onAppear(perform: reload)
        .onReceive(timer) { _ in reload() }
    }

    private func reload() {
        let text = (try? String(contentsOf: AppGroup.logFile, encoding: .utf8)) ?? ""
        let all = text.split(whereSeparator: \.isNewline).map(String.init)
        let tail = Array(all.suffix(500))
        if tail != lines { lines = tail }
    }
}

// MARK: - 关于

struct AboutView: View {
    @Environment(\.palette) private var c
    @Environment(\.openURL) private var openURL

    var body: some View {
        SubPage(title: "关于") {
            VStack(spacing: 10) {
                Image("logo").resizable().scaledToFit().frame(width: 88, height: 88).clipShape(Circle())
                Text("DuoBao Proxy").font(.system(size: 22, weight: .bold)).foregroundStyle(c.fg)
                Text("版本 \(appVersion) · 核心 \(Core.version)").font(.system(size: 13)).foregroundStyle(c.fg2)
            }
            .padding(.vertical, 24)
            Card {
                CardRow(title: "关于开发者", action: { openURL(URL(string: "https://t.me/hyk857")!) }) { ValueChevron() }
                CardRow(title: "检查更新", divider: true, action: { openURL(URL(string: "https://github.com/Smile96899/DuoBaoProxy/releases")!) }) { ValueChevron() }
            }
        }
    }
}
