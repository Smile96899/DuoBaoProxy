// Package mobile is the gomobile surface of the DuoBao Proxy core for the
// Android (VpnService) and iOS (NEPacketTunnelProvider) hosts. Everything
// that crosses the boundary is a basic type, []byte, error, an exported
// struct of those, or a host-implemented interface; lists travel as JSON.
//
// This file holds the host-implemented interfaces; their JSON payloads are
// documented on each method because the Kotlin and Swift sides are written
// from these comments.
package mobile

import "sync/atomic"

// Host is implemented by the VpnService (Android) or the
// NEPacketTunnelProvider (iOS) for the lifetime of one Start.
type Host interface {
	// OpenTun configures and opens the tunnel described by tunConfigJSON and
	// returns its file descriptor. Android: build a VpnService.Builder from
	// the config, establish() it, keep the ParcelFileDescriptor open until
	// Stop and return pfd.fd (Go dups it). iOS: apply
	// NEPacketTunnelNetworkSettings, wait for the setTunnelNetworkSettings
	// completion handler, then return -1; Go finds the utun fd itself.
	//
	// tunConfigJSON (all lists are present, possibly empty):
	//
	//	{
	//	  "inet4_address": ["172.19.0.1/28"],          // addAddress / NEIPv4Settings
	//	  "inet6_address": ["fdfe:dcba:9876::1/126"],  // addAddress / NEIPv6Settings
	//	  "mtu": 9000,
	//	  "auto_route": true,
	//	  "strict_route": false,
	//	  "dns_mode": "hijack",           // "disabled": add no DNS server; "hijack" | "native": add dns_servers
	//	  "dns_servers": ["172.19.0.2", "fdfe:dcba:9876::2"],
	//	  "inet4_route_range": ["0.0.0.0/0"], // final routes, excludes already subtracted:
	//	  "inet6_route_range": ["::/0"],      //   Android addRoute each; iOS includedRoutes
	//	  "inet4_route_address": [],          // raw route_address (informational)
	//	  "inet6_route_address": [],
	//	  "inet4_route_exclude_address": [],  // raw route_exclude_address: iOS excludedRoutes
	//	  "inet6_route_exclude_address": [],
	//	  "include_package": [],  // Android addAllowedApplication (never mixed with exclude)
	//	  "exclude_package": [],  // Android addDisallowedApplication
	//	  "http_proxy": {         // omitted unless the tun platform.http_proxy is enabled
	//	    "server": "127.0.0.1", "server_port": 2080,
	//	    "bypass_domain": [], "match_domain": []
	//	  }
	//	}
	//
	// With auto_route false the route ranges are empty and no routes should
	// be added.
	OpenTun(tunConfigJSON string) (int32, error)

	// StartNetworkMonitor starts watching the default (non-VPN) network and
	// reports every change through l.UpdateDefaultInterface. It must report
	// the current default network before it returns, otherwise dials fail
	// until the first change. Android: register a NetworkCallback for
	// NET_CAPABILITY_INTERNET + NET_CAPABILITY_NOT_VPN so the TUN itself is
	// never reported.
	StartNetworkMonitor(l *NetworkListener) error
	// CloseNetworkMonitor stops the monitor started above.
	CloseNetworkMonitor() error

	// InterfacesJSON lists the physical interfaces that are up, as a JSON
	// array:
	//
	//	[{
	//	  "name": "wlan0",
	//	  "index": 30,                  // OS interface index (NetworkInterface.getIndex / if_nametoindex)
	//	  "mtu": 1500,
	//	  "type": "wifi",               // "wifi" | "cellular" | "ethernet" | "other"
	//	  "addresses": ["192.168.1.5/24", "fe80::1/64"],
	//	  "dns": ["192.168.1.1"],       // LinkProperties.getDnsServers
	//	  "gateways": ["192.168.1.1"],  // optional
	//	  "metered": false              // !NET_CAPABILITY_NOT_METERED
	//	}]
	//
	// iOS only needs name, index and type; Go fills in the rest.
	InterfacesJSON() (string, error)

	// FindOwnerJSON resolves the owner of a connection seen on the TUN
	// (Android: ConnectivityManager.getConnectionOwnerUid + PackageManager).
	// proto is the IP protocol number (6 TCP, 17 UDP); src/dst are IP
	// literals. Returns
	//
	//	{"uid": 10123, "user_name": "", "process_path": "", "packages": ["com.example"]}
	//
	// or an error (or uid -1) when the owner is unknown. Unused on iOS.
	FindOwnerJSON(proto int32, src string, sport int32, dst string, dport int32) (string, error)
}

// Protector keeps a socket outside the VPN (Android: VpnService.protect).
// It must return true when no VPN is up, and stay valid for the life of the
// process: it covers every socket the process dials (xray, sing-box
// auto_detect_interface, probes, subscription fetches).
type Protector interface {
	Protect(fd int32) bool
}

// LocalDNS resolves on the underlying network (Android: DnsResolver.rawQuery
// on the default non-VPN network). Exchange takes a raw DNS query message in
// wire format and returns the raw response message.
type LocalDNS interface {
	Exchange(message []byte) ([]byte, error)
}

// LogHandler receives every log line of both engines and of this package.
// level uses the sing-box scale: 0 panic, 1 fatal, 2 error, 3 warn, 4 info,
// 5 debug, 6 trace.
type LogHandler interface {
	WriteLog(level int32, line string)
}

// The process-wide host hooks, boxed so they can be swapped atomically
// (including back to nil) while engines read them from other goroutines.
type (
	protectorBox  struct{ p Protector }
	localDNSBox   struct{ d LocalDNS }
	logHandlerBox struct{ h LogHandler }
)

var (
	protectorValue  atomic.Pointer[protectorBox]
	localDNSValue   atomic.Pointer[localDNSBox]
	logHandlerValue atomic.Pointer[logHandlerBox]
)

// SetProtector installs the socket protector. Call it once per process,
// before the first Start or test, on Android only. The xray dialer hook it
// installs cannot be removed; a later call only swaps the protector it uses.
func SetProtector(p Protector) {
	protectorValue.Store(&protectorBox{p})
	if p != nil {
		installXrayProtect()
	}
}

// SetLocalDNS installs the resolver behind sing-box `type: local` DNS
// servers. It takes effect for engine instances created afterwards. On iOS
// leave it unset to keep sing-box's own system resolver.
func SetLocalDNS(d LocalDNS) {
	localDNSValue.Store(&localDNSBox{d})
}

// SetLogHandler installs the process-wide log sink (nil discards).
func SetLogHandler(h LogHandler) {
	logHandlerValue.Store(&logHandlerBox{h})
}

func currentProtector() Protector {
	if b := protectorValue.Load(); b != nil {
		return b.p
	}
	return nil
}

func currentLocalDNS() LocalDNS {
	if b := localDNSValue.Load(); b != nil {
		return b.d
	}
	return nil
}

func currentLogHandler() LogHandler {
	if b := logHandlerValue.Load(); b != nil {
		return b.h
	}
	return nil
}
