import Foundation

/// Options the app passes to `startTunnel(options:)`; everything is pre-rendered in the app.
enum TunnelOptionKey {
    static let coreId = "core_id"
    static let config = "config"
    static let statsTags = "stats_tags"   // JSON array string
    static let frontConfig = "front_config"
    static let nodeName = "node_name"
    static let nodeId = "node_id"
}

/// App → extension `sendProviderMessage` requests (JSON).
struct TunnelRequest: Codable {
    enum Kind: String, Codable { case status, stats, urlTest, tcpPing }
    var kind: Kind
    var tag: String? = nil
    var url: String? = nil
    var address: String? = nil
    var timeoutMs: Int32? = nil
}

/// Extension → app reply.
struct TunnelReply: Codable {
    var ok: Bool = true
    var error: String? = nil
    var coreId: String? = nil
    var uplink: Int64? = nil
    var downlink: Int64? = nil
    var ms: Int32? = nil
}

enum TunnelCodec {
    static func encode<T: Encodable>(_ v: T) -> Data { (try? JSONEncoder().encode(v)) ?? Data() }
    static func decode<T: Decodable>(_ t: T.Type, _ d: Data?) -> T? { d.flatMap { try? JSONDecoder().decode(t, from: $0) } }
}
