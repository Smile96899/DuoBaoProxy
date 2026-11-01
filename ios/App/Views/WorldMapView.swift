import SwiftUI

/// The 首页 map (port of Android `WorldMap`): every country a tile, the device's country (`from`)
/// tinted and the exit country (`to`) filled, joined by a flowing dashed arc. A beam falls from the
/// top onto each pinned country; names sit under the pins so the beams stay clear.
struct WorldMapView: View {
    @Environment(\.palette) private var c
    let from: String?
    let to: String?
    let highlight: Color

    @State private var shapes: [String: Countries.Shape] = [:]
    @State private var dropStart = Date()

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            // Ease-out fall of the beam over 0.9 s after the pinned country changes.
            let k = min(1, max(0, timeline.date.timeIntervalSince(dropStart) / 0.9))
            let drop = CGFloat(1 - pow(1 - k, 3))
            Canvas { ctx, size in
                draw(&ctx, size: size, drop: drop, pulse: CGFloat(t.truncatingRemainder(dividingBy: 1.8) / 1.8),
                     flow: CGFloat(t.truncatingRemainder(dividingBy: 1.2) / 1.2))
            }
        }
        .task {
            if shapes.isEmpty { shapes = await Task.detached { Countries.shapes() }.value }
        }
        .onChange(of: to ?? from ?? "") { _ in replayDrop() }
        .onAppear { replayDrop() }
    }

    private func replayDrop() {
        dropStart = Date()
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, drop: CGFloat, pulse: CGFloat, flow: CGFloat) {
        let a = Countries.point(for: from, in: shapes)
        let b = Countries.point(for: to, in: shapes)

        // Fit the shown countries with room for the name tags and pins.
        let pts = [a, b].compactMap { $0 }
        let minX = pts.map(\.x).min() ?? -180, maxX = pts.map(\.x).max() ?? 180
        let minY = pts.map(\.y).min() ?? Countries.latBottom, maxY = pts.map(\.y).max() ?? Countries.latTop
        let spanX = max(maxX - minX, 40), spanY = max(maxY - minY, 25)
        let base = size.height / (Countries.latTop - Countries.latBottom)
        let fit = min((size.width - 120) / (spanX * base), (size.height - 90) / (spanY * base))
        let scale = base * min(max(fit, 1), 12)
        let halfW = size.width / 2 / scale, halfH = size.height / 2 / scale
        func within(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat { lo > hi ? (lo + hi) / 2 : min(max(v, lo), hi) }
        let cx = within((minX + maxX) / 2, -180 + halfW, 180 - halfW)
        let cy = within((minY + maxY) / 2, Countries.latBottom + halfH, Countries.latTop - halfH)
        func p(_ lon: CGFloat, _ lat: CGFloat) -> CGPoint {
            CGPoint(x: size.width / 2 + (lon - cx) * scale, y: size.height / 2 - (lat - cy) * scale)
        }

        // Countries.
        for (cc, shape) in shapes {
            let fill: Color = cc == to ? highlight : cc == from ? highlight.opacity(0.38) : c.mapLand
            var path = Path()
            for ring in shape.rings where ring.count > 2 {
                path.move(to: p(ring[0].x, ring[0].y))
                for q in ring.dropFirst() { path.addLine(to: p(q.x, q.y)) }
                path.closeSubpath()
            }
            ctx.fill(path, with: .color(fill))
            ctx.stroke(path, with: .color(c.mapBg), lineWidth: 0.8)
        }

        let pin: CGFloat = 2.6
        let p0 = a.map { p($0.x, $0.y) }
        let p1 = b.map { p($0.x, $0.y) }
        let apart = p0 != nil && p1 != nil && (abs(p0!.x - p1!.x) > 2 || abs(p0!.y - p1!.y) > 2)

        func beam(_ at: CGPoint, alpha: Double) {
            let head = at.y * drop
            var line = Path()
            line.move(to: CGPoint(x: at.x, y: 0))
            line.addLine(to: CGPoint(x: at.x, y: head))
            ctx.stroke(line, with: .linearGradient(Gradient(colors: [highlight.opacity(0), highlight.opacity(alpha)]),
                                                   startPoint: .zero, endPoint: CGPoint(x: 0, y: max(head, 1))),
                       style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
        }

        func dot(_ at: CGPoint, _ r: CGFloat, _ color: Color) {
            ctx.fill(Path(ellipseIn: CGRect(x: at.x - r, y: at.y - r, width: r * 2, height: r * 2)), with: .color(color))
        }

        if apart, let p0, let p1 {
            let lift = max(hypot(p1.x - p0.x, p1.y - p0.y) * 0.3, 16)
            let ctrl = CGPoint(x: (p0.x + p1.x) / 2, y: min(p0.y, p1.y) - lift)
            var arc = Path()
            arc.move(to: p0)
            arc.addQuadCurve(to: p1, control: ctrl)
            ctx.stroke(arc, with: .color(highlight), style: StrokeStyle(lineWidth: 1.6, dash: [5, 4], dashPhase: -flow * 9))
            beam(p0, alpha: 0.55)
            dot(p0, pin * 1.4, .white)
            dot(p0, pin, highlight)
        }

        if let main = p1 ?? p0 {
            beam(main, alpha: 0.9)
            if drop < 1 {
                dot(CGPoint(x: main.x, y: main.y * drop), pin * 0.8, highlight)
            } else {
                dot(main, pin * (1.4 + 3.6 * pulse), highlight.opacity(0.35 * (1 - pulse)))
                dot(main, pin * 2.2, highlight.opacity(0.18))
                dot(main, pin * 1.4, .white)
                dot(main, pin, highlight)
            }
        }

        // Name tags under their pins.
        func tag(_ cc: String?, at: CGPoint?) {
            guard let cc, let at else { return }
            let text = ctx.resolve(Text(Countries.name(cc)).font(.system(size: 11, weight: .semibold)).foregroundColor(c.fg))
            let ts = text.measure(in: CGSize(width: 200, height: 40))
            let w = ts.width + 14, h = ts.height + 6
            let left = min(max(at.x - w / 2, 4), size.width - w - 4)
            let top = at.y + 10
            ctx.fill(Path(roundedRect: CGRect(x: left, y: top, width: w, height: h), cornerRadius: h / 2), with: .color(c.panel))
            ctx.draw(text, at: CGPoint(x: left + 7, y: top + 3), anchor: .topLeading)
        }
        if apart {
            tag(from, at: p0)
            if drop >= 1 { tag(to, at: p1) }
        } else if drop >= 1 {
            tag(to ?? from, at: p1 ?? p0)
        }
    }
}
