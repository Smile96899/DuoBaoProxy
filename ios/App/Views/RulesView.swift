import SwiftUI

/// 规则: proxy mode, the preset switches, custom rules and the final outbound.
struct RulesView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var c
    @State private var adding = false

    var body: some View {
        let s = model.store.settings
        ScrollView {
            VStack(spacing: 0) {
                PageTitle(title: "规则") { RoundIconButton(systemImage: "plus") { adding = true } }

                SectionLabel("代理模式")
                Picker("代理模式", selection: Binding(get: { model.prefs.proxyMode }, set: { model.setProxyMode($0) })) {
                    ForEach(proxyModes, id: \.0) { m, l in Text(l).tag(m) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 20)

                SectionLabel("分流策略")
                Card {
                    ForEach(Array(Rules.presets.enumerated()), id: \.1.id) { i, p in
                        CardRow(title: p.title, subtitle: p.detail, divider: i > 0) {
                            OutboundBadge(out: p.outbound)
                            DbpToggle(isOn: Rules.enabled(s, p)) { on in
                                model.setSettings(Rules.toggle(s, p, on: on).map { ("routing." + $0.key, JSON.strings($0.value)) })
                            }
                        }
                    }
                }

                SectionLabel("自定义规则")
                let custom = Rules.custom(s)
                Card {
                    if custom.isEmpty {
                        Text("还没有自定义规则").font(.system(size: 14)).foregroundStyle(c.fg3).frame(height: 56)
                    }
                    ForEach(Array(custom.enumerated()), id: \.1.id) { i, r in
                        CardRow(title: r.display, subtitle: r.type, divider: i > 0) {
                            OutboundBadge(out: r.outbound)
                            Button { remove(r) } label: { Image(systemName: "minus.circle").foregroundStyle(c.bad) }
                                .buttonStyle(.plain)
                        }
                    }
                }

                SectionLabel("其余流量")
                Card {
                    CheckRow(title: "走代理", checked: Rules.final(s) == Rules.proxy) { model.setSetting("routing.final", .string(Rules.proxy)) }
                    CheckRow(title: "直连", checked: Rules.final(s) == Rules.direct, divider: true) { model.setSetting("routing.final", .string(Rules.direct)) }
                }
                Footnote("规则模式下生效：先按上面的规则匹配，没有匹配到的流量按这里处理。")
            }
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $adding) { AddRuleView() }
    }

    private func remove(_ r: Rules.Rule) {
        let list = Rules.list(model.store.settings, r.key).filter { $0 != r.value }
        model.setSetting("routing.\(r.key)", .strings(list))
    }
}

struct OutboundBadge: View {
    @Environment(\.palette) private var c
    let out: String

    var body: some View {
        let (fg, bg): (Color, Color) = out == Rules.proxy ? (c.accent, c.accentSoft) : out == Rules.block ? (c.bad, c.badSoft) : (c.fg2, c.panel2)
        Text(Rules.outboundLabel(out)).font(.system(size: 12, weight: .semibold)).foregroundStyle(fg)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(bg, in: Capsule())
    }
}

struct AddRuleView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var type = "DOMAIN-SUFFIX"
    @State private var value = ""
    @State private var outbound = Rules.proxy

    var body: some View {
        NavigationStack {
            Form {
                Picker("类型", selection: $type) { ForEach(Rules.types, id: \.self) { Text($0).tag($0) } }
                TextField(placeholder, text: $value)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Picker("策略", selection: $outbound) {
                    Text("代理").tag(Rules.proxy)
                    Text("直连").tag(Rules.direct)
                    Text("拦截").tag(Rules.block)
                }
                .pickerStyle(.segmented)
            }
            .navigationTitle("添加规则")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加") { add() }.disabled(value.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private var placeholder: String {
        switch type {
        case "IP-CIDR": return "1.2.3.0/24"
        case "GEOIP": return "cn"
        case "GEOSITE": return "google"
        case "DOMAIN-KEYWORD": return "google"
        default: return "google.com"
        }
    }

    private func add() {
        let (key, v) = Rules.encode(type: type, input: value, outbound: outbound)
        var list = Rules.list(model.store.settings, key)
        if !list.contains(v) { list.append(v) }
        model.setSetting("routing.\(key)", .strings(list))
        dismiss()
    }
}
