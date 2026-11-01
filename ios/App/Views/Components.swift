import SwiftUI

/// A bordered rounded card holding rows; rows draw their own dividers.
struct Card<Content: View>: View {
    @Environment(\.palette) private var c
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .background(c.panel)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(c.line, lineWidth: 1))
            .padding(.horizontal, 20)
    }
}

/// One card row: title (+ optional subtitle), trailing control; tappable when `action` is set.
struct CardRow<Trailing: View>: View {
    @Environment(\.palette) private var c
    let title: String
    var subtitle: String? = nil
    var divider = false
    var enabled = true
    var titleColor: Color? = nil
    var action: (() -> Void)? = nil
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(spacing: 0) {
            if divider { Rectangle().fill(c.line).frame(height: 1) }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16, weight: .medium)).foregroundStyle(titleColor ?? c.fg).lineLimit(1)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle).font(.system(size: 13)).foregroundStyle(c.fg2).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { if enabled { action?() } }
                trailing
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(minHeight: 56)
            .opacity(enabled ? 1 : 0.5)
        }
    }
}

extension CardRow where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil, divider: Bool = false, action: (() -> Void)? = nil) {
        self.init(title: title, subtitle: subtitle, divider: divider, action: action) { EmptyView() }
    }
}

/// `value ›` at the end of a navigation row.
struct ValueChevron: View {
    @Environment(\.palette) private var c
    var value = ""

    var body: some View {
        HStack(spacing: 4) {
            if !value.isEmpty { Text(value).font(.system(size: 14)).foregroundStyle(c.fg2).lineLimit(1) }
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(c.fg3)
        }
    }
}

/// The design's switch: accent track when on.
struct DbpToggle: View {
    @Environment(\.palette) private var c
    let isOn: Bool
    var enabled = true
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle("", isOn: Binding(get: { isOn }, set: onChange))
            .labelsHidden()
            .tint(c.accent)
            .disabled(!enabled)
    }
}

/// Small grey section label above a card.
struct SectionLabel: View {
    @Environment(\.palette) private var c
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.system(size: 13)).foregroundStyle(c.fg2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 26).padding(.top, 18).padding(.bottom, 8)
    }
}

/// Grey note under a card.
struct Footnote: View {
    @Environment(\.palette) private var c
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.system(size: 12)).foregroundStyle(c.fg2).lineSpacing(4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 26).padding(.vertical, 10)
    }
}

/// A ✓ option row.
struct CheckRow: View {
    @Environment(\.palette) private var c
    let title: String
    let checked: Bool
    var divider = false
    var subtitle: String? = nil
    let action: () -> Void

    var body: some View {
        CardRow(title: title, subtitle: subtitle, divider: divider, action: action) {
            if checked { Image(systemName: "checkmark").font(.system(size: 16, weight: .semibold)).foregroundStyle(c.accent) }
        }
    }
}

/// A row whose value opens a menu of options anchored at the right (like Android's fixed MenuRow).
struct MenuRow<T: Hashable>: View {
    @Environment(\.palette) private var c
    let title: String
    let options: [(T, String)]
    let selected: T
    var divider = false
    let onSelect: (T) -> Void

    var body: some View {
        let label = options.first { $0.0 == selected }?.1 ?? "\(selected)"
        CardRow(title: title, divider: divider) {
            Menu {
                ForEach(options, id: \.0) { v, l in
                    Button { onSelect(v) } label: {
                        if v == selected { Label(l, systemImage: "checkmark") } else { Text(l) }
                    }
                }
            } label: {
                ValueChevron(value: label)
            }
        }
    }
}

/// Large page title with optional trailing content.
struct PageTitle<Trailing: View>: View {
    @Environment(\.palette) private var c
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack {
            Text(title).font(.system(size: 30, weight: .bold)).foregroundStyle(c.fg)
            Spacer()
            trailing
        }
        .padding(.horizontal, 24).padding(.top, 12).padding(.bottom, 4)
    }
}

/// The scrolling page used by every sub page (navigation title + back from NavigationStack).
struct SubPage<Content: View>: View {
    @Environment(\.palette) private var c
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                content
                Spacer(minLength: 24)
            }
            .padding(.top, 8)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A round bordered icon button (首页 +).
struct RoundIconButton: View {
    @Environment(\.palette) private var c
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 18, weight: .medium)).foregroundStyle(c.fg)
                .frame(width: 44, height: 44)
                .background(c.panel, in: Circle())
                .overlay(Circle().stroke(c.line, lineWidth: 1))
        }
    }
}

/// A rounded icon tile.
struct IconTile: View {
    let systemImage: String
    let bg: Color
    let tint: Color
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: systemImage).font(.system(size: size * 0.42, weight: .medium)).foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(bg, in: Circle())
    }
}

/// The country flag of a node, from its name.
struct FlagView: View {
    @Environment(\.palette) private var c
    let name: String

    var body: some View {
        let flag = Countries.flag(Countries.guess(name))
        Text(flag.isEmpty ? "🌐" : flag).font(.system(size: 22))
            .frame(width: 40, height: 30)
            .background(c.panel2, in: RoundedRectangle(cornerRadius: 6))
    }
}

/// The toast shown at the bottom of the screen.
struct ToastView: View {
    @Environment(\.palette) private var c
    let text: String

    var body: some View {
        Text(text).font(.system(size: 14)).foregroundStyle(c.panel)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(c.fg, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 24)
    }
}

/// A one-line text prompt (rename, add group …).
struct PromptSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var c
    let title: String
    var placeholder = ""
    var confirm = "确定"
    @State var text: String
    let onConfirm: (String) -> Void

    var body: some View {
        NavigationStack {
            Form {
                TextField(placeholder, text: $text, axis: .vertical)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirm) { onConfirm(text); dismiss() }.disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

func formatBytes(_ b: Int64) -> String {
    let f = ByteCountFormatter()
    f.countStyle = .binary
    f.allowedUnits = [.useBytes, .useKB, .useMB, .useGB, .useTB]
    return f.string(fromByteCount: b)
}

func formatRate(_ b: Int64) -> String { formatBytes(b) + "/s" }
