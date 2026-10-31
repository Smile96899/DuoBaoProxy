import SwiftUI
import UIKit

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c
    @State private var collapsed: Set<String> = []
    @State private var showAdd = false
    @State private var showScan = false
    @State private var showPaste = false
    @State private var actionNode: NodeEntry?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                mapCard
                switchCard
                groupsHeader
                ForEach(groups, id: \.name) { g in groupSection(g) }
                Spacer(minLength: 24)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { model.testCurrent() }
        .confirmationDialog("添加节点", isPresented: $showAdd, titleVisibility: .visible) {
            Button("扫一扫") { showScan = true }
            Button("剪贴板导入") {
                let text = UIPasteboard.general.string ?? ""
                Task { await model.importText(text) }
            }
            Button("手动添加") { showPaste = true }
            Button("取消", role: .cancel) {}
        }
        .sheet(isPresented: $showScan) {
            QRScanView { text in
                showScan = false
                Task { await model.importText(text) }
            }
        }
        .sheet(isPresented: $showPaste) {
            PromptSheet(title: "手动添加", placeholder: "粘贴分享链接或订阅地址", confirm: "导入", text: "") { t in
                Task { await model.importText(t) }
            }
        }
        .confirmationDialog(actionNode?.title ?? "", isPresented: Binding(get: { actionNode != nil }, set: { if !$0 { actionNode = nil } }), titleVisibility: .visible) {
            if let n = actionNode {
                Button("测试延迟") { model.test([n.id]) }
                Button("复制分享链接") { UIPasteboard.general.string = model.shareLink(n.id); model.show("已复制") }
                Button("删除", role: .destructive) { model.removeNodes([n.id]) }
            }
            Button("取消", role: .cancel) {}
        }
    }

    private var groups: [NodeGroup] {
        let home = model.prefs.homeGroup
        return model.store.groups.sorted { a, b in a.name == home && b.name != home }
    }

    // MARK: header

    private var header: some View {
        HStack(spacing: 10) {
            Image("logo").resizable().scaledToFit().frame(width: 40, height: 40).clipShape(Circle())
            Text("DuoBao Proxy").font(.system(size: 21, weight: .bold)).foregroundStyle(c.fg)
                .lineLimit(1).minimumScaleFactor(0.7).layoutPriority(1)
            Spacer()
            Menu {
                ForEach(proxyModes, id: \.0) { m, l in
                    Button { model.setProxyMode(m) } label: {
                        if m == model.prefs.proxyMode { Label(l, systemImage: "checkmark") } else { Text(l) }
                    }
                }
            } label: {
                HStack(spacing: 8) {
                    Circle().fill(statusColor).frame(width: 8, height: 8)
                    Text(proxyModes.first { $0.0 == model.prefs.proxyMode }?.1 ?? "全局")
                        .font(.system(size: 15, weight: .semibold)).foregroundStyle(c.fg)
                    Image(systemName: "chevron.down").font(.system(size: 12, weight: .semibold)).foregroundStyle(c.fg2)
                }
                .padding(.horizontal, 14).frame(height: 44)
                .background(c.panel, in: Capsule())
                .overlay(Capsule().stroke(c.line, lineWidth: 1))
            }
            RoundIconButton(systemImage: "plus") { showAdd = true }
        }
        .padding(.horizontal, 20).padding(.top, 8)
    }

    private var statusColor: Color {
        if !model.online { return c.bad }
        return model.connected ? c.accent : c.fg3
    }

    // MARK: map

    private var mapCard: some View {
        let local = model.localInfo?.cc.nilIfEmpty ?? "CN"
        let exit = model.connected ? (model.exitInfo?.cc.nilIfEmpty ?? model.selectedNode.flatMap { Countries.guess($0.title) }) : nil
        let ip = model.connected ? model.exitInfo?.ip : model.localInfo?.ip
        return ZStack(alignment: .bottomLeading) {
            WorldMapView(from: local, to: exit, highlight: model.online ? c.accent : c.bad)
            if let ip {
                HStack(spacing: 8) {
                    Circle().fill(model.online ? c.accent : c.bad).frame(width: 7, height: 7)
                    Text(ip).font(.system(size: 14, weight: .medium).monospacedDigit()).foregroundStyle(c.fg)
                }
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(c.panel, in: Capsule())
                .padding(12)
            }
        }
        .frame(height: 180)
        .background(c.mapBg)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, 20)
    }

    // MARK: switch card

    private var switchCard: some View {
        Card {
            HStack(spacing: 14) {
                IconTile(systemImage: "power", bg: c.accentSoft, tint: model.online ? c.accent : c.bad, size: 52)
                VStack(alignment: .leading, spacing: 3) {
                    Text("代理服务").font(.system(size: 17, weight: .semibold)).foregroundStyle(c.fg)
                    Text(model.connected ? (model.selectedNode?.title ?? model.statusText) : model.statusText)
                        .font(.system(size: 13)).foregroundStyle(c.fg2).lineLimit(1)
                }
                Spacer()
                if model.busy || model.tunnel.isBusy {
                    ProgressView().tint(c.accent)
                }
                DbpToggle(isOn: model.connected || model.tunnel.status == .connecting) { _ in model.toggle() }
            }
            .padding(16)
            Rectangle().fill(c.line).frame(height: 1)
            HStack(spacing: 14) {
                IconTile(systemImage: "wifi", bg: c.accentSoft, tint: c.accent, size: 52)
                VStack(alignment: .leading, spacing: 3) {
                    Text("延迟测试").font(.system(size: 17, weight: .semibold)).foregroundStyle(c.fg)
                    Text(currentLatencyText).font(.system(size: 13)).foregroundStyle(c.fg2)
                }
                Spacer()
                Button { model.testCurrent() } label: {
                    Label("测试", systemImage: "bolt").font(.system(size: 15, weight: .medium)).foregroundStyle(c.accent)
                        .padding(.horizontal, 16).frame(height: 40)
                        .overlay(Capsule().stroke(c.accent.opacity(0.5), lineWidth: 1))
                }
            }
            .padding(16)
        }
    }

    private var currentLatencyText: String {
        guard let n = model.selectedNode else { return "未选择节点" }
        let ms = model.latency(n)
        return ms > 0 ? "当前节点 \(ms) ms" : ms < 0 ? "当前节点 超时" : "当前节点 未测试"
    }

    // MARK: groups

    private var groupsHeader: some View {
        HStack {
            Text("分组").font(.system(size: 20, weight: .bold)).foregroundStyle(c.fg)
            Spacer()
            NavigationLink { GroupsManageView() } label: {
                HStack(spacing: 2) {
                    Text("管理分组")
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold))
                }
                .font(.system(size: 16)).foregroundStyle(c.accent)
            }
        }
        .padding(.horizontal, 24).padding(.top, 8)
    }

    private func groupSection(_ g: NodeGroup) -> some View {
        let nodes = model.store.nodes.filter { $0.group == g.name }
        let open = !collapsed.contains(g.name)
        return Card {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if open { collapsed.insert(g.name) } else { collapsed.remove(g.name) }
                }
            } label: {
                HStack(spacing: 8) {
                    Text(g.name).font(.system(size: 17, weight: .semibold)).foregroundStyle(c.fg)
                    Text("\(nodes.count)").font(.system(size: 14)).foregroundStyle(c.fg3)
                    Spacer()
                    Image(systemName: open ? "chevron.up" : "chevron.down").font(.system(size: 14, weight: .semibold)).foregroundStyle(c.fg2)
                }
                .padding(.horizontal, 18).frame(height: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .contextMenu {
                Button("测试本组延迟") { model.testGroup(g.name) }
                if !g.subUrl.isEmpty { Button("更新订阅") { Task { await model.updateSubscription(g.name) } } }
                Button("设为默认分组") { model.setUi(Prefs.homeGroupKey, .string(g.name)) }
            }
            if open {
                ForEach(nodes) { n in nodeRow(n) }
                if nodes.isEmpty {
                    Rectangle().fill(c.line).frame(height: 1)
                    Text("暂无节点").font(.system(size: 14)).foregroundStyle(c.fg3).frame(height: 52)
                }
            }
        }
    }

    private func nodeRow(_ n: NodeEntry) -> some View {
        let selected = n.id == model.selectedId
        let supported = model.supports(n)
        let ms = model.latency(n)
        return VStack(spacing: 0) {
            Rectangle().fill(c.line).frame(height: 1)
            HStack(spacing: 12) {
                FlagView(name: n.title)
                VStack(alignment: .leading, spacing: 3) {
                    Text(n.title).font(.system(size: 16, weight: .medium)).foregroundStyle(c.fg).lineLimit(1)
                    Text(subtitle(n, selected: selected, supported: supported))
                        .font(.system(size: 13)).foregroundStyle(c.fg2).lineLimit(1)
                }
                Spacer()
                if model.testing.contains(n.id) {
                    ProgressView().scaleEffect(0.8)
                } else if ms != 0 {
                    Text(ms > 0 ? "\(ms) ms" : "超时")
                        .font(.system(size: 15, weight: .medium).monospacedDigit())
                        .foregroundStyle(latencyColor(ms))
                }
                Circle().fill(selected ? (model.online ? c.accent : c.bad) : .clear).frame(width: 8, height: 8)
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
            .background(selected ? c.accentSoft.opacity(0.6) : .clear)
            .opacity(supported ? 1 : 0.45)
            .contentShape(Rectangle())
            .onTapGesture { model.select(n.id) }
            .onLongPressGesture { actionNode = n }
        }
    }

    private func subtitle(_ n: NodeEntry, selected: Bool, supported: Bool) -> String {
        let proto = protocolLabel(n.proto)
        if !supported { return "\(model.currentCore) 不支持" }
        if selected && model.connected {
            return "\(proto)  ↑ \(formatRate(model.traffic.upRate))  ↓ \(formatRate(model.traffic.downRate))"
        }
        return proto
    }

    private func latencyColor(_ ms: Int) -> Color {
        if ms < 0 { return c.bad }
        if ms < 300 { return c.good }
        if ms < 800 { return c.warn }
        return c.bad
    }
}

func protocolLabel(_ p: String) -> String {
    let names = ["vmess": "VMess", "vless": "VLESS", "trojan": "Trojan", "shadowsocks": "Shadowsocks", "ss": "Shadowsocks",
                 "ssr": "ShadowsocksR", "socks": "SOCKS5", "http": "HTTP", "hysteria": "Hysteria", "hysteria2": "Hysteria2",
                 "tuic": "TUIC", "wireguard": "WireGuard", "anytls": "AnyTLS", "naive": "Naive"]
    return names[p] ?? p.uppercased()
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
