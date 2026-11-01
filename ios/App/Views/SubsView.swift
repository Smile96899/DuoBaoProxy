import SwiftUI
import UIKit

/// 订阅: every group with its subscription info; add, update, edit, delete.
struct SubsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c
    @State private var adding = false
    @State private var updating: Set<String> = []

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                PageTitle(title: "订阅") {
                    RoundIconButton(systemImage: "arrow.clockwise") { updateAll() }
                    RoundIconButton(systemImage: "plus") { adding = true }
                }
                let subs = model.store.groups.filter { !$0.subUrl.isEmpty }
                if subs.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "cloud").font(.system(size: 40)).foregroundStyle(c.fg3)
                        Text("还没有订阅").font(.system(size: 16, weight: .medium)).foregroundStyle(c.fg2)
                        Text("点右上角 + 添加订阅地址").font(.system(size: 13)).foregroundStyle(c.fg3)
                    }
                    .padding(.top, 80)
                }
                ForEach(subs, id: \.name) { g in subCard(g) }
            }
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $adding) { SubEditView(group: nil) }
    }

    private func updateAll() {
        for g in model.store.groups where !g.subUrl.isEmpty { update(g.name) }
    }

    private func update(_ name: String) {
        updating.insert(name)
        Task {
            await model.updateSubscription(name)
            updating.remove(name)
        }
    }

    private func subCard(_ g: NodeGroup) -> some View {
        let count = model.store.nodes.filter { $0.group == g.name }.count
        return NavigationLink { SubEditView(group: g) } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(g.name).font(.system(size: 17, weight: .semibold)).foregroundStyle(c.fg)
                    Text("\(count) 个节点").font(.system(size: 13)).foregroundStyle(c.fg3)
                    Spacer()
                    if updating.contains(g.name) {
                        ProgressView()
                    } else {
                        Button { update(g.name) } label: {
                            Image(systemName: "arrow.clockwise").foregroundStyle(c.accent)
                        }
                        .buttonStyle(.plain)
                    }
                }
                if let total = g.total, total > 0 {
                    let used = (g.upload ?? 0) + (g.download ?? 0)
                    ProgressView(value: min(1, Double(used) / Double(total))).tint(c.accent)
                    HStack {
                        Text("已用 \(formatBytes(used)) / \(formatBytes(total))")
                        Spacer()
                        if let e = g.expire, e > 0 { Text("到期 " + Self.dateText(e)) }
                    }
                    .font(.system(size: 12)).foregroundStyle(c.fg2)
                }
                Text(updatedText(g)).font(.system(size: 12)).foregroundStyle(c.fg3)
            }
            .padding(16)
            .background(c.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(c.line, lineWidth: 1))
            .padding(.horizontal, 20)
        }
        .buttonStyle(.plain)
    }

    private func updatedText(_ g: NodeGroup) -> String {
        guard let u = g.updated, let d = ISO8601DateFormatter.lenient(u) else { return "从未更新" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "zh_CN")
        return "更新于 " + f.localizedString(for: d, relativeTo: Date())
    }

    static func dateText(_ unix: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(unix)))
    }
}

extension ISO8601DateFormatter {
    static func lenient(_ s: String) -> Date? {
        let a = ISO8601DateFormatter()
        a.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return a.date(from: s) ?? ISO8601DateFormatter().date(from: s)
    }
}

/// 添加订阅 / 编辑订阅.
struct SubEditView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var c
    let group: NodeGroup?
    @State private var name = ""
    @State private var url = ""
    @State private var saving = false
    @State private var confirmDelete = false

    var body: some View {
        let form = Form {
            Section("名称") { TextField("留空则使用网址的域名", text: $name) }
            Section("订阅地址") {
                TextField("https://", text: $url, axis: .vertical)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("从剪贴板粘贴") { url = UIPasteboard.general.string ?? url }
            }
            if let group {
                Section {
                    Button("复制订阅地址") { UIPasteboard.general.string = group.subUrl; model.show("已复制") }
                    Button("删除订阅", role: .destructive) { confirmDelete = true }
                }
            }
        }
        .navigationTitle(group == nil ? "添加订阅" : "编辑订阅")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if group == nil { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            ToolbarItem(placement: .confirmationAction) {
                if saving { ProgressView() } else {
                    Button("保存") { save() }.disabled(!AppModel.isSubscriptionURL(url))
                }
            }
        }
        .onAppear {
            name = group?.name ?? ""
            url = group?.subUrl ?? ""
        }
        .confirmationDialog("删除「\(group?.name ?? "")」和它的全部节点？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let group { model.store.removeGroup(group.name) }
                dismiss()
            }
        }

        if group == nil { NavigationStack { form } } else { form }
    }

    private func save() {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let n = name.trimmingCharacters(in: .whitespaces)
        saving = true
        Task {
            if let group {
                if !n.isEmpty, n != group.name, !model.store.renameGroup(group.name, to: n) {
                    model.show("名称已被使用")
                    saving = false
                    return
                }
                let current = n.isEmpty ? group.name : n
                model.store.updateGroup(current, patch: ["sub_url": .string(u)])
                await model.updateSubscription(current)
            } else {
                await model.addSubscription(u, name: n)
            }
            saving = false
            dismiss()
        }
    }
}

/// 管理分组: reorder, rename, add, delete groups.
struct GroupsManageView: View {
    @EnvironmentObject private var model: AppModel
    @State private var adding = false
    @State private var renaming: NodeGroup?

    var body: some View {
        List {
            ForEach(model.store.groups, id: \.name) { g in
                HStack {
                    Text(g.name)
                    if g.name == model.prefs.homeGroup { Text("默认显示").font(.caption).foregroundStyle(.secondary) }
                    Spacer()
                    Text("\(model.store.nodes.filter { $0.group == g.name }.count)").foregroundStyle(.secondary)
                }
                .swipeActions {
                    Button("删除", role: .destructive) { model.store.removeGroup(g.name) }
                    Button("重命名") { renaming = g }.tint(.orange)
                }
                .contextMenu {
                    Button("设为默认分组") { model.setUi(Prefs.homeGroupKey, .string(g.name)) }
                    Button("测试延迟") { model.testGroup(g.name) }
                }
            }
            .onMove { model.store.moveGroups(from: $0, to: $1) }
        }
        .navigationTitle("管理分组")
        .toolbar {
            EditButton()
            Button { adding = true } label: { Image(systemName: "plus") }
        }
        .sheet(isPresented: $adding) {
            PromptSheet(title: "新建分组", placeholder: "分组名称", text: "") { n in
                if !model.createGroup(n) { model.show("名称为空或已存在") }
            }
        }
        .sheet(item: $renaming) { g in
            PromptSheet(title: "重命名", placeholder: "分组名称", text: g.name) { n in
                if !model.store.renameGroup(g.name, to: n.trimmingCharacters(in: .whitespaces)) { model.show("名称为空或已存在") }
            }
        }
    }
}
