import Foundation
import NetworkExtension

/// Owns the one `NETunnelProviderManager` of the app and talks to the PacketTunnel extension.
@MainActor
final class TunnelController: ObservableObject {
    @Published private(set) var status: NEVPNStatus = .invalid
    @Published private(set) var lastError: String?

    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] n in
            guard let conn = n.object as? NEVPNConnection else { return }
            Task { @MainActor in self?.status = conn.status }
        }
        Task { await load() }
    }

    var isConnected: Bool { status == .connected }
    var isBusy: Bool { status == .connecting || status == .disconnecting || status == .reasserting }

    /// Loads the saved configuration (creating nothing yet).
    func load() async {
        let list = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = list.first
        status = manager?.connection.status ?? .invalid
    }

    /// Creates or updates the VPN profile. iOS asks the user to allow it the first time.
    func configure(prefs: Prefs) async throws -> NETunnelProviderManager {
        let m = manager ?? NETunnelProviderManager()
        let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = AppGroup.tunnelBundleId
        proto.serverAddress = "DuoBao Proxy"
        // Kill Switch: every connection must use the tunnel while it is up (or reconnecting).
        proto.includeAllNetworks = prefs.includeAll
        proto.excludeLocalNetworks = prefs.excludeLocal
        proto.disconnectOnSleep = false
        m.protocolConfiguration = proto
        m.localizedDescription = "DuoBao Proxy"
        m.isEnabled = true
        if prefs.onDemand {
            let rule = NEOnDemandRuleConnect()
            rule.interfaceTypeMatch = .any
            m.onDemandRules = [rule]
            m.isOnDemandEnabled = true
        } else {
            m.onDemandRules = []
            m.isOnDemandEnabled = false
        }
        try await m.saveToPreferences()
        try await m.loadFromPreferences()
        manager = m
        return m
    }

    func start(options: [String: NSObject], prefs: Prefs) async throws {
        lastError = nil
        let m = try await configure(prefs: prefs)
        try m.connection.startVPNTunnel(options: options)
    }

    func stop() {
        // On-demand would bring the tunnel straight back; switch it off for a user stop.
        if let m = manager, m.isOnDemandEnabled {
            m.isOnDemandEnabled = false
            Task { try? await m.saveToPreferences() }
        }
        manager?.connection.stopVPNTunnel()
    }

    /// The reason of the last failed start or drop (iOS 16+).
    func fetchLastError() async -> String? {
        guard let conn = manager?.connection else { return nil }
        do {
            try await conn.fetchLastDisconnectError()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    /// One request to the running extension; nil when it is not running or did not answer.
    func send(_ req: TunnelRequest) async -> TunnelReply? {
        guard let session = manager?.connection as? NETunnelProviderSession, status == .connected else { return nil }
        return await withCheckedContinuation { cont in
            do {
                try session.sendProviderMessage(TunnelCodec.encode(req)) { data in
                    cont.resume(returning: TunnelCodec.decode(TunnelReply.self, data))
                }
            } catch {
                cont.resume(returning: nil)
            }
        }
    }
}
