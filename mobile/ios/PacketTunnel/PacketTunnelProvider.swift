import NetworkExtension
import Network
import Dbcore

/// The VPN side: runs the Go engine (sing-box / xray) with this provider as its `Host`.
/// The app renders the config and passes it in the start options (DESIGN.md §5).
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var host: TunnelHost?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let opts = options ?? [:]
        guard let core = opts[TunnelOptionKey.coreId] as? String,
              let config = opts[TunnelOptionKey.config] as? String, !config.isEmpty else {
            // Started from iOS Settings or on-demand without options: nothing rendered to run.
            completionHandler(Core.CoreError(message: "请在应用内连接"))
            return
        }
        let tags = opts[TunnelOptionKey.statsTags] as? String ?? "[]"
        let front = opts[TunnelOptionKey.frontConfig] as? String ?? ""
        let name = opts[TunnelOptionKey.nodeName] as? String ?? ""

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try Core.setup()
                DBCMobileSetLogHandler(SharedLog.shared)
                let host = TunnelHost(provider: self)
                self.host = host
                try Core.start(core: core, config: config, statsTagsJSON: tags, frontConfig: front, host: host)
                SharedLog.shared.append("已连接：\(name)（\(core)）")
                completionHandler(nil)
            } catch {
                SharedLog.shared.append("连接失败：\(error.localizedDescription)")
                Core.stop()
                self.host?.closeMonitor()
                completionHandler(error)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            Core.stop()
            self.host?.closeMonitor()
            self.host = nil
            SharedLog.shared.append("已断开（\(reason.rawValue)）")
            completionHandler()
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let req = TunnelCodec.decode(TunnelRequest.self, messageData) else {
            completionHandler?(nil)
            return
        }
        DispatchQueue.global(qos: .utility).async {
            var reply = TunnelReply()
            switch req.kind {
            case .status:
                reply.coreId = Core.status
            case .stats:
                let t = Core.stats(tag: req.tag ?? "")
                reply.uplink = t.up
                reply.downlink = t.down
                reply.coreId = Core.status
            case .urlTest:
                do {
                    reply.ms = try Core.urlTest(core: Core.status, config: "", url: req.url ?? "", timeoutMs: req.timeoutMs ?? 5000)
                } catch {
                    reply.ok = false
                    reply.error = error.localizedDescription
                }
            case .tcpPing:
                do {
                    reply.ms = try Core.tcpPing(req.address ?? "", timeoutMs: req.timeoutMs ?? 3000)
                } catch {
                    reply.ok = false
                    reply.error = error.localizedDescription
                }
            }
            completionHandler?(TunnelCodec.encode(reply))
        }
    }
}

/// `go/mobile.Host` for iOS (see go/mobile/host.go for every JSON shape).
final class TunnelHost: NSObject, DBCMobileHostProtocol {
    private weak var provider: NEPacketTunnelProvider?
    private var monitor: NWPathMonitor?
    private let monitorQueue = DispatchQueue(label: "duobao.path")

    init(provider: NEPacketTunnelProvider) {
        self.provider = provider
    }

    // MARK: OpenTun: apply NEPacketTunnelNetworkSettings, return -1 (Go finds the utun fd itself)

    func openTun(_ tunConfigJSON: String?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        let cfg = try JSON.parse(tunConfigJSON ?? "{}")
        let autoRoute = cfg["auto_route"].bool()
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        settings.mtu = NSNumber(value: cfg["mtu"].int(1500))

        let v4 = cfg["inet4_address"].arrayValue.compactMap { Self.cidr($0.string) }
        if !v4.isEmpty {
            let s = NEIPv4Settings(addresses: v4.map(\.addr), subnetMasks: v4.map { Self.mask4($0.bits) })
            if autoRoute {
                s.includedRoutes = cfg["inet4_route_range"].arrayValue.compactMap { Self.cidr($0.string) }
                    .map { NEIPv4Route(destinationAddress: $0.addr, subnetMask: Self.mask4($0.bits)) }
                s.excludedRoutes = cfg["inet4_route_exclude_address"].arrayValue.compactMap { Self.cidr($0.string) }
                    .map { NEIPv4Route(destinationAddress: $0.addr, subnetMask: Self.mask4($0.bits)) }
            }
            settings.ipv4Settings = s
        }

        let v6 = cfg["inet6_address"].arrayValue.compactMap { Self.cidr($0.string) }
        if !v6.isEmpty {
            let s = NEIPv6Settings(addresses: v6.map(\.addr), networkPrefixLengths: v6.map { NSNumber(value: $0.bits) })
            if autoRoute {
                s.includedRoutes = cfg["inet6_route_range"].arrayValue.compactMap { Self.cidr($0.string) }
                    .map { NEIPv6Route(destinationAddress: $0.addr, networkPrefixLength: NSNumber(value: $0.bits)) }
                s.excludedRoutes = cfg["inet6_route_exclude_address"].arrayValue.compactMap { Self.cidr($0.string) }
                    .map { NEIPv6Route(destinationAddress: $0.addr, networkPrefixLength: NSNumber(value: $0.bits)) }
            }
            settings.ipv6Settings = s
        }

        if cfg["dns_mode"].string != "disabled" {
            let servers = cfg["dns_servers"].arrayValue.map(\.string).filter { !$0.isEmpty }
            if !servers.isEmpty {
                let dns = NEDNSSettings(servers: servers)
                dns.matchDomains = [""] // every query goes to the tunnel's DNS
                settings.dnsSettings = dns
            }
        }

        let proxy = cfg["http_proxy"]
        if proxy.objectValue != nil {
            let p = NEProxySettings()
            let server = NEProxyServer(address: proxy["server"].string, port: proxy["server_port"].int(0))
            p.httpEnabled = true
            p.httpServer = server
            p.httpsEnabled = true
            p.httpsServer = server
            p.exceptionList = proxy["bypass_domain"].arrayValue.map(\.string)
            let match = proxy["match_domain"].arrayValue.map(\.string)
            if !match.isEmpty { p.matchDomains = match }
            settings.proxySettings = p
        }

        // Go calls this on its own thread, so blocking until the system applied the settings is fine.
        let done = DispatchSemaphore(value: 0)
        var failure: Error?
        provider?.setTunnelNetworkSettings(settings) { err in
            failure = err
            done.signal()
        }
        done.wait()
        if let failure { throw failure }
        ret0_?.pointee = -1
    }

    // MARK: network monitor

    func startNetworkMonitor(_ l: DBCMobileNetworkListener?) throws {
        let m = NWPathMonitor()
        let first = DispatchSemaphore(value: 0)
        var reported = false
        m.pathUpdateHandler = { path in
            Self.report(path, to: l)
            if !reported {
                reported = true
                first.signal()
            }
        }
        m.start(queue: monitorQueue)
        monitor = m
        // Go needs the current default network before this returns.
        _ = first.wait(timeout: .now() + 3)
    }

    func closeNetworkMonitor() throws {
        closeMonitor()
    }

    func closeMonitor() {
        monitor?.cancel()
        monitor = nil
    }

    private static func report(_ path: NWPath, to l: DBCMobileNetworkListener?) {
        guard let l else { return }
        let iface = path.availableInterfaces.first { !$0.name.hasPrefix("utun") && $0.type != .loopback }
        guard path.status == .satisfied, let iface else {
            l.updateDefaultInterface("", index: -1, expensive: false, constrained: false)
            return
        }
        l.updateDefaultInterface(iface.name, index: Int32(iface.index), expensive: path.isExpensive, constrained: path.isConstrained)
    }

    // MARK: interfaces / owner

    func interfacesJSON() throws -> String {
        let interfaces = monitor?.currentPath.availableInterfaces ?? []
        let list: [JSON] = interfaces.filter { !$0.name.hasPrefix("utun") }.map { i in
            let type: String
            switch i.type {
            case .wifi: type = "wifi"
            case .cellular: type = "cellular"
            case .wiredEthernet: type = "ethernet"
            default: type = "other"
            }
            return [
                "name": .string(i.name),
                "index": .int(Int64(i.index)),
                "mtu": 1500,
                "type": .string(type),
                "addresses": .strings(Self.addresses(of: i.name)),
                "dns": [],
                "metered": .bool(type == "cellular"),
            ]
        }
        return JSON.array(list).encoded()
    }

    /// iOS cannot map connections to apps; Go treats a missing uid as "not found".
    func findOwnerJSON(_ proto: Int32, src: String?, sport: Int32, dst: String?, dport: Int32) throws -> String {
        "{}"
    }

    // MARK: helpers

    private static func cidr(_ s: String) -> (addr: String, bits: Int)? {
        let parts = s.split(separator: "/")
        guard let a = parts.first, !a.isEmpty else { return nil }
        let full = a.contains(":") ? 128 : 32
        let bits = parts.count > 1 ? Int(parts[1]) ?? full : full
        return (String(a), bits)
    }

    private static func mask4(_ bits: Int) -> String {
        let b = max(0, min(32, bits))
        let m: UInt32 = b == 0 ? 0 : ~UInt32(0) << UInt32(32 - b)
        return "\(m >> 24 & 255).\(m >> 16 & 255).\(m >> 8 & 255).\(m & 255)"
    }

    /// IPv4 / IPv6 addresses of an interface as CIDR strings (getifaddrs).
    private static func addresses(of name: String) -> [String] {
        var out: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return out }
        defer { freeifaddrs(head) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard String(cString: ifa.ifa_name) == name, let sa = ifa.ifa_addr else { continue }
            let family = Int32(sa.pointee.sa_family)
            guard family == AF_INET || family == AF_INET6 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let len = socklen_t(family == AF_INET ? MemoryLayout<sockaddr_in>.size : MemoryLayout<sockaddr_in6>.size)
            guard getnameinfo(sa, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            var addr = String(cString: host)
            if let pct = addr.firstIndex(of: "%") { addr = String(addr[..<pct]) }
            out.append(addr + (family == AF_INET ? "/32" : "/128"))
        }
        return out
    }
}
