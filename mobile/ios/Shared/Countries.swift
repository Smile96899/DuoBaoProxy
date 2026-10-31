import Foundation
import CoreGraphics

/// Country helpers for the 首页 map, the flags and the exit chip. Port of Android `Countries`.
enum Countries {
    /// Approximate centre (lon, lat) of the countries nodes usually sit in.
    static let centre: [String: CGPoint] = [
        "CN": .init(x: 116, y: 35), "HK": .init(x: 114, y: 22), "MO": .init(x: 113.5, y: 22), "TW": .init(x: 121, y: 24),
        "JP": .init(x: 139, y: 36), "KR": .init(x: 127, y: 37), "SG": .init(x: 104, y: 1.3), "MY": .init(x: 102, y: 4),
        "TH": .init(x: 101, y: 15), "VN": .init(x: 106, y: 16), "PH": .init(x: 122, y: 12), "ID": .init(x: 113, y: -2),
        "IN": .init(x: 78, y: 22), "AE": .init(x: 55, y: 24), "TR": .init(x: 35, y: 39), "IL": .init(x: 35, y: 31),
        "RU": .init(x: 38, y: 56), "UA": .init(x: 31, y: 49), "DE": .init(x: 10, y: 51), "FR": .init(x: 2, y: 47),
        "GB": .init(x: -1, y: 53), "NL": .init(x: 5, y: 52), "BE": .init(x: 4, y: 50.5), "CH": .init(x: 8, y: 47),
        "IT": .init(x: 12, y: 43), "ES": .init(x: -4, y: 40), "PT": .init(x: -8, y: 39.5), "SE": .init(x: 16, y: 61),
        "NO": .init(x: 9, y: 61), "FI": .init(x: 26, y: 63), "DK": .init(x: 10, y: 56), "PL": .init(x: 19, y: 52),
        "AT": .init(x: 14, y: 47.5), "IE": .init(x: -8, y: 53), "US": .init(x: -97, y: 39), "CA": .init(x: -100, y: 56),
        "MX": .init(x: -102, y: 23), "BR": .init(x: -50, y: -12), "AR": .init(x: -64, y: -35), "CL": .init(x: -71, y: -33),
        "AU": .init(x: 134, y: -25), "NZ": .init(x: 172, y: -41), "ZA": .init(x: 24, y: -29), "EG": .init(x: 30, y: 27),
        "NG": .init(x: 8, y: 9), "KH": .init(x: 105, y: 12.5), "LA": .init(x: 103, y: 18), "MM": .init(x: 96, y: 21),
        "BD": .init(x: 90, y: 24), "NP": .init(x: 84, y: 28), "LK": .init(x: 81, y: 7.5), "MN": .init(x: 104, y: 47),
        "QA": .init(x: 51, y: 25.3), "GR": .init(x: 22, y: 39), "CZ": .init(x: 15, y: 49.8), "RO": .init(x: 25, y: 46),
        "HU": .init(x: 19, y: 47), "BG": .init(x: 25, y: 42.7), "CO": .init(x: -74, y: 4), "PE": .init(x: -76, y: -10),
        "KZ": .init(x: 67, y: 48), "PK": .init(x: 70, y: 30), "SA": .init(x: 45, y: 24), "IR": .init(x: 53, y: 32),
        "LU": .init(x: 6, y: 49.7),
    ]

    /// Name keywords → country code, checked in order (more specific first).
    private static let keywords: [([String], String)] = [
        (["香港", "hong kong", "hongkong", "🇭🇰"], "HK"),
        (["澳门", "macau", "macao"], "MO"),
        (["台湾", "臺灣", "taiwan", "台北", "🇹🇼"], "TW"),
        (["新加坡", "singapore", "狮城", "🇸🇬"], "SG"),
        (["日本", "japan", "东京", "東京", "tokyo", "大阪", "osaka", "🇯🇵"], "JP"),
        (["韩国", "韓國", "korea", "首尔", "seoul", "🇰🇷"], "KR"),
        (["美国", "美國", "united states", "usa", "洛杉矶", "纽约", "硅谷", "西雅图", "芝加哥", "达拉斯", "圣何塞", "los angeles", "new york", "🇺🇸"], "US"),
        (["加拿大", "canada", "🇨🇦"], "CA"),
        (["英国", "英國", "united kingdom", "london", "伦敦", "🇬🇧"], "GB"),
        (["德国", "德國", "germany", "法兰克福", "frankfurt", "🇩🇪"], "DE"),
        (["法国", "法國", "france", "巴黎", "paris", "🇫🇷"], "FR"),
        (["荷兰", "netherlands", "阿姆斯特丹", "amsterdam", "🇳🇱"], "NL"),
        (["俄罗斯", "俄羅斯", "russia", "莫斯科", "🇷🇺"], "RU"),
        (["印度尼西亚", "印尼", "indonesia", "🇮🇩"], "ID"),
        (["印度", "india", "孟买", "🇮🇳"], "IN"),
        (["土耳其", "turkey", "türkiye", "🇹🇷"], "TR"),
        (["澳大利亚", "澳洲", "australia", "悉尼", "sydney", "🇦🇺"], "AU"),
        (["新西兰", "new zealand", "🇳🇿"], "NZ"),
        (["马来西亚", "malaysia", "吉隆坡", "🇲🇾"], "MY"),
        (["泰国", "thailand", "曼谷", "🇹🇭"], "TH"),
        (["越南", "vietnam", "🇻🇳"], "VN"),
        (["菲律宾", "philippines", "🇵🇭"], "PH"),
        (["柬埔寨", "cambodia", "🇰🇭"], "KH"),
        (["罗马尼亚", "romania", "🇷🇴"], "RO"),
        (["阿联酋", "迪拜", "dubai", "uae", "🇦🇪"], "AE"),
        (["以色列", "israel", "🇮🇱"], "IL"),
        (["乌克兰", "ukraine", "🇺🇦"], "UA"),
        (["瑞士", "switzerland", "🇨🇭"], "CH"),
        (["瑞典", "sweden", "🇸🇪"], "SE"),
        (["挪威", "norway", "🇳🇴"], "NO"),
        (["芬兰", "finland", "🇫🇮"], "FI"),
        (["丹麦", "denmark", "🇩🇰"], "DK"),
        (["波兰", "poland", "🇵🇱"], "PL"),
        (["意大利", "italy", "米兰", "🇮🇹"], "IT"),
        (["西班牙", "spain", "🇪🇸"], "ES"),
        (["葡萄牙", "portugal", "🇵🇹"], "PT"),
        (["爱尔兰", "ireland", "🇮🇪"], "IE"),
        (["奥地利", "austria", "🇦🇹"], "AT"),
        (["比利时", "belgium", "🇧🇪"], "BE"),
        (["巴西", "brazil", "🇧🇷"], "BR"),
        (["阿根廷", "argentina", "🇦🇷"], "AR"),
        (["智利", "chile", "🇨🇱"], "CL"),
        (["墨西哥", "mexico", "🇲🇽"], "MX"),
        (["南非", "south africa", "🇿🇦"], "ZA"),
        (["埃及", "egypt", "🇪🇬"], "EG"),
        (["哈萨克", "kazakhstan", "🇰🇿"], "KZ"),
        (["中国", "china", "上海", "北京", "深圳", "广州", "🇨🇳"], "CN"),
    ]

    private static let codeRegex = try! NSRegularExpression(
        pattern: "(?:^|[^a-z])(hk|tw|mo|sg|jp|kr|us|uk|gb|de|fr|nl|ru|au|nz|ca|th|vn|tr|ro|pl|it|es|se|ch|cz|fi|dk|ie|pt|gr|hu|bg|ua|il|ae|sa|za|br|ar|mx|cl|co|pe|ph|kh|mm|bd|np|lk|pk|kz|mn|lu|ee|lv|lt|rs|hr|si|sk|md|ge|az|qa|kw|bh|om|eg|ng|ke)(?:[^a-z]|$)")

    /// The country a node name points at (`🇺🇸 美国 01`, `HK-03`, `ro-1.2.3.4`), or nil.
    static func guess(_ name: String) -> String? {
        let n = name.lowercased()
        if let hit = keywords.first(where: { $0.0.contains(where: n.contains) }) { return hit.1 }
        let r = NSRange(n.startIndex..., in: n)
        if let m = codeRegex.firstMatch(in: n, range: r), let range = Range(m.range(at: 1), in: n) {
            let cc = n[range].uppercased()
            return cc == "UK" ? "GB" : cc
        }
        return nil
    }

    /// The regional-indicator flag emoji of `cc`.
    static func flag(_ cc: String?) -> String {
        guard let cc, cc.count == 2 else { return "" }
        let base: UInt32 = 0x1F1E6
        var s = ""
        for u in cc.uppercased().unicodeScalars {
            guard u.value >= 65, u.value <= 90, let f = UnicodeScalar(base + u.value - 65) else { return "" }
            s.unicodeScalars.append(f)
        }
        return s
    }

    /// 中文 country name from the system (罗马尼亚, 柬埔寨 …).
    static func name(_ cc: String?) -> String {
        guard let cc, !cc.isEmpty else { return "未知地区" }
        return Locale(identifier: "zh_CN").localizedString(forRegionCode: cc) ?? cc
    }

    // MARK: shapes (countries.txt, Natural Earth 1:110m, public domain)

    struct Shape {
        let label: CGPoint
        let rings: [[CGPoint]]
    }

    static let latTop: CGFloat = 84
    static let latBottom: CGFloat = -57

    private static var cache: [String: Shape]?
    private static let lock = NSLock()

    /// Shapes from the bundled countries.txt, parsed once (call off the main thread the first time).
    static func shapes() -> [String: Shape] {
        lock.lock(); defer { lock.unlock() }
        if let cache { return cache }
        guard let url = Bundle.main.url(forResource: "countries", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [:] }
        let parsed = parse(text)
        cache = parsed
        return parsed
    }

    /// Lines `CC|labelLon,labelLat|lon,lat lon,lat …;ring2…` in tenths of a degree.
    static func parse(_ text: String) -> [String: Shape] {
        var out: [String: Shape] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "|", maxSplits: 2)
            guard parts.count == 3 else { continue }
            let cc = String(parts[0]).trimmingCharacters(in: .whitespaces)
            let label = point(parts[1]) ?? .zero
            let rings: [[CGPoint]] = parts[2].split(separator: ";").map { ring in
                ring.split(separator: " ").compactMap(point)
            }
            if let prev = out[cc] { out[cc] = Shape(label: prev.label, rings: prev.rings + rings) }
            else { out[cc] = Shape(label: label, rings: rings) }
        }
        return out
    }

    private static func point(_ s: Substring) -> CGPoint? {
        let xy = s.split(separator: ",")
        guard xy.count == 2, let x = Int(xy[0]), let y = Int(xy[1]) else { return nil }
        return CGPoint(x: CGFloat(x) / 10, y: CGFloat(y) / 10)
    }

    /// Where a pin for `cc` goes: the centre table, else the shape's label point.
    static func point(for cc: String?, in shapes: [String: Shape]) -> CGPoint? {
        guard let cc else { return nil }
        return centre[cc] ?? shapes[cc]?.label
    }
}
