import Foundation

/// A JSON value that keeps data.json round-trips lossless enough for desktop compatibility
/// (unknown keys survive; integers stay integers).
enum JSON: Equatable, Hashable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    static let emptyObject = JSON.object([:])

    // MARK: parse / encode

    static func parse(_ text: String) throws -> JSON {
        guard let data = text.data(using: .utf8) else { throw CocoaError(.fileReadInapplicableStringEncoding) }
        return try parse(data)
    }

    static func parse(_ data: Data) throws -> JSON {
        let any = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return JSON(any: any)
    }

    init(any: Any?) {
        switch any {
        case nil, is NSNull: self = .null
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { self = .bool(n.boolValue) }
            else if CFNumberIsFloatType(n), n.doubleValue.rounded() != n.doubleValue { self = .double(n.doubleValue) }
            else if CFNumberIsFloatType(n) { self = .int(Int64(n.doubleValue)) }
            else { self = .int(n.int64Value) }
        case let s as String: self = .string(s)
        case let a as [Any]: self = .array(a.map { JSON(any: $0) })
        case let o as [String: Any]: self = .object(o.mapValues { JSON(any: $0) })
        case let b as Bool: self = .bool(b)
        case let i as Int: self = .int(Int64(i))
        case let i as Int32: self = .int(Int64(i))
        case let d as Double: self = .double(d)
        case let j as JSON: self = j
        default: self = .null
        }
    }

    var any: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .int(let i): return i
        case .double(let d): return d
        case .string(let s): return s
        case .array(let a): return a.map(\.any)
        case .object(let o): return o.mapValues(\.any)
        }
    }

    func encoded(pretty: Bool = false) -> String {
        var opts: JSONSerialization.WritingOptions = [.fragmentsAllowed, .withoutEscapingSlashes, .sortedKeys]
        if pretty { opts.insert(.prettyPrinted) }
        guard let data = try? JSONSerialization.data(withJSONObject: any, options: opts) else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: access

    subscript(key: String) -> JSON {
        get { if case .object(let o) = self { return o[key] ?? .null }; return .null }
        set {
            var o = objectValue ?? [:]
            o[key] = newValue
            self = .object(o)
        }
    }

    subscript(index: Int) -> JSON {
        if case .array(let a) = self, a.indices.contains(index) { return a[index] }
        return .null
    }

    var isNull: Bool { self == .null }
    var objectValue: [String: JSON]? { if case .object(let o) = self { return o }; return nil }
    var arrayValue: [JSON] { if case .array(let a) = self { return a }; return [] }

    var string: String {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        case .double(let d): return d.rounded() == d ? String(Int64(d)) : String(d)
        case .bool(let b): return b ? "true" : "false"
        default: return ""
        }
    }

    func int(_ fallback: Int = 0) -> Int {
        switch self {
        case .int(let i): return Int(clamping: i)
        case .double(let d) where d.rounded() == d && abs(d) < 9e15: return Int(d)
        default: return fallback
        }
    }

    var int64: Int64? {
        switch self {
        case .int(let i): return i
        case .double(let d) where d.isFinite: return Int64(d)
        default: return nil
        }
    }

    func bool(_ fallback: Bool = false) -> Bool { if case .bool(let b) = self { return b }; return fallback }

    var isEmptyObject: Bool { objectValue?.isEmpty ?? true }

    /// Shallow merge of an object patch.
    func merged(_ patch: JSON) -> JSON {
        guard var o = objectValue, let p = patch.objectValue else { return self }
        for (k, v) in p { o[k] = v }
        return .object(o)
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral, ExpressibleByFloatLiteral {
    init(stringLiteral v: String) { self = .string(v) }
    init(integerLiteral v: Int) { self = .int(Int64(v)) }
    init(booleanLiteral v: Bool) { self = .bool(v) }
    init(floatLiteral v: Double) { self = .double(v) }
    init(arrayLiteral e: JSON...) { self = .array(e) }
    init(dictionaryLiteral e: (String, JSON)...) { self = .object(Dictionary(e, uniquingKeysWith: { $1 })) }
    init(nilLiteral: ()) { self = .null }
}

extension JSON {
    static func from(_ v: Any?) -> JSON { JSON(any: v) }
    static func strings(_ a: [String]) -> JSON { .array(a.map { .string($0) }) }
}
