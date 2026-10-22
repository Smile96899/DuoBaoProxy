// Package node holds the core-independent node model: what the GUI stores,
// what import produces, and what the renderers consume.
package node

import (
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"strings"
)

// Protocol ids. These are the values a Core reports from Protocols().
const (
	Socks       = "socks"
	HTTP        = "http"
	Shadowsocks = "shadowsocks"
	VMess       = "vmess"
	VLESS       = "vless"
	Trojan      = "trojan"
	Hysteria2   = "hysteria2"
	TUIC        = "tuic"
	Naive       = "naive"
	SSH         = "ssh"
	AnyTLS      = "anytls"
	WireGuard   = "wireguard"
	Hysteria    = "hysteria" // v1
	ShadowTLS   = "shadowtls"
)

// Stream describes transport and TLS for the V2Ray-family protocols
// (socks/http/shadowsocks/vmess/vless/trojan).
type Stream struct {
	Network        string `json:"net,omitempty"` // tcp ws http grpc httpupgrade xhttp
	Security       string `json:"sec,omitempty"` // "" or "tls"
	PacketEncoding string `json:"pac_enc,omitempty"`

	Path       string `json:"path,omitempty"`
	Host       string `json:"host,omitempty"`
	HeaderType string `json:"h_type,omitempty"` // tcp: "http"

	SNI           string `json:"sni,omitempty"`
	ALPN          string `json:"alpn,omitempty"` // comma separated
	Certificate   string `json:"cert,omitempty"`
	Fingerprint   string `json:"utls,omitempty"`
	AllowInsecure bool   `json:"insecure,omitempty"`

	EarlyDataName   string `json:"ed_name,omitempty"`
	EarlyDataLength int    `json:"ed_len,omitempty"`

	RealityPublicKey string `json:"pbk,omitempty"`
	RealityShortID   string `json:"sid,omitempty"`
	RealitySpiderX   string `json:"spx,omitempty"`

	// Multiplex: 0 follow global default, 1 on, 2 off.
	Multiplex int `json:"mux_s,omitempty"`
}

type Hysteria2Options struct {
	ObfsPassword string `json:"obfs_password,omitempty"`
	UpMbps       int    `json:"up_mbps,omitempty"`
	DownMbps     int    `json:"down_mbps,omitempty"`
	HopPorts     string `json:"hop_ports,omitempty"`
	HopInterval  int    `json:"hop_interval,omitempty"`
}

type TUICOptions struct {
	CongestionControl string `json:"congestion_control,omitempty"`
	UDPRelayMode      string `json:"udp_relay_mode,omitempty"`
	UDPOverStream     bool   `json:"udp_over_stream,omitempty"`
	ZeroRTTHandshake  bool   `json:"zero_rtt,omitempty"`
	Heartbeat         string `json:"heartbeat,omitempty"`
}

// WireGuardOptions describe one peer tunnel; Server/Port is the peer endpoint.
type WireGuardOptions struct {
	PrivateKey    string `json:"private_key"`
	PeerPublicKey string `json:"peer_public_key"`
	PreSharedKey  string `json:"pre_shared_key,omitempty"`
	LocalAddress  string `json:"local_address"`      // comma separated CIDRs, e.g. "10.0.0.2/32,fd00::2/128"
	Reserved      string `json:"reserved,omitempty"` // "a,b,c" (WARP)
	MTU           int    `json:"mtu,omitempty"`
}

// HysteriaOptions is the legacy hysteria v1 protocol.
type HysteriaOptions struct {
	Auth     string `json:"auth,omitempty"` // auth_str
	UpMbps   int    `json:"up_mbps,omitempty"`
	DownMbps int    `json:"down_mbps,omitempty"`
	Obfs     string `json:"obfs,omitempty"` // xplus password
}

// ShadowTLSOptions wrap a shadowsocks connection (Method / SSPassword) in a
// ShadowTLS handshake; Password is the ShadowTLS password, Stream.SNI the
// handshake server name, Stream.Fingerprint the uTLS fingerprint.
type ShadowTLSOptions struct {
	Version    int    `json:"version,omitempty"` // 1 2 3, default 3
	SSPassword string `json:"ss_password"`
}

// QUICTLS is the TLS block shared by hysteria2 and tuic.
type QUICTLS struct {
	SNI           string `json:"sni,omitempty"`
	ALPN          string `json:"alpn,omitempty"`
	CA            string `json:"ca,omitempty"`
	AllowInsecure bool   `json:"insecure,omitempty"`
	DisableSNI    bool   `json:"disable_sni,omitempty"`
}

type NaiveOptions struct {
	Protocol            string `json:"protocol,omitempty"` // https | quic
	ExtraHeaders        string `json:"extra_headers,omitempty"`
	SNI                 string `json:"sni,omitempty"`
	Certificate         string `json:"certificate,omitempty"`
	InsecureConcurrency int    `json:"insecure_concurrency,omitempty"`
}

// Node is one importable/exportable proxy endpoint.
type Node struct {
	Protocol string `json:"protocol"`
	Name     string `json:"name,omitempty"`
	Server   string `json:"server"`
	Port     int    `json:"port"`

	// Credentials. Which ones apply depends on Protocol:
	//   socks/http: Username, Password
	//   shadowsocks: Method, Password, Plugin ("obfs-local;obfs=http;obfs-host=x")
	//   vmess: UUID, AlterID, VMessSecurity
	//   vless: UUID, Flow
	//   trojan: Password
	//   hysteria2: Password
	//   tuic: UUID, Password
	//   naive: Username, Password
	Username      string `json:"username,omitempty"`
	Password      string `json:"password,omitempty"`
	UUID          string `json:"uuid,omitempty"`
	Method        string `json:"method,omitempty"`
	Plugin        string `json:"plugin,omitempty"`
	AlterID       int    `json:"alter_id,omitempty"`
	VMessSecurity string `json:"vmess_security,omitempty"`
	Flow          string `json:"flow,omitempty"`
	SocksVersion  int    `json:"socks_version,omitempty"` // 4 or 5 (default)
	UDPOverTCP    int    `json:"uot,omitempty"`           // shadowsocks: 0 off, 1/2 version

	Stream    *Stream           `json:"stream,omitempty"`
	Hysteria2 *Hysteria2Options `json:"hysteria2,omitempty"`
	TUIC      *TUICOptions      `json:"tuic,omitempty"`
	WireGuard *WireGuardOptions `json:"wireguard,omitempty"`
	Hysteria  *HysteriaOptions  `json:"hysteria,omitempty"`
	ShadowTLS *ShadowTLSOptions `json:"shadowtls,omitempty"`
	QUICTLS   *QUICTLS          `json:"quic_tls,omitempty"`
	Naive     *NaiveOptions     `json:"naive,omitempty"`

	// CustomOutbound is merged over the rendered outbound (core-native JSON).
	CustomOutbound json.RawMessage `json:"custom_outbound,omitempty"`
	// CustomConfig is merged over the rendered full config.
	CustomConfig json.RawMessage `json:"custom_config,omitempty"`
}

func (n *Node) stream() *Stream {
	if n.Stream == nil {
		n.Stream = &Stream{Network: "tcp"}
	}
	if n.Stream.Network == "" {
		n.Stream.Network = "tcp"
	}
	return n.Stream
}

// DisplayAddress renders host:port, bracketing IPv6.
func (n *Node) DisplayAddress() string {
	if n.Protocol == Hysteria2 && n.Hysteria2 != nil && strings.TrimSpace(n.Hysteria2.HopPorts) != "" {
		return wrapIPv6(n.Server) + ":" + n.Hysteria2.HopPorts
	}
	return net.JoinHostPort(n.Server, strconv.Itoa(n.Port))
}

// DisplayName falls back to the address when the node has no name.
func (n *Node) DisplayName() string {
	if n.Name != "" {
		return n.Name
	}
	return n.DisplayAddress()
}

// Validate checks the fields every renderer relies on.
func (n *Node) Validate() error {
	if n.Protocol == "" {
		return fmt.Errorf("node: protocol is empty")
	}
	if n.Server == "" {
		return fmt.Errorf("node: server is empty")
	}
	if n.Port <= 0 || n.Port > 65535 {
		return fmt.Errorf("node: invalid port %d", n.Port)
	}
	switch n.Protocol {
	case Shadowsocks:
		if n.Method == "" || n.Password == "" {
			return fmt.Errorf("node: shadowsocks needs method and password")
		}
	case VMess, VLESS, TUIC:
		if n.UUID == "" {
			return fmt.Errorf("node: %s needs uuid", n.Protocol)
		}
	case Trojan, Hysteria2:
		if n.Password == "" {
			return fmt.Errorf("node: %s needs password", n.Protocol)
		}
	case Naive:
		if n.Username == "" || n.Password == "" {
			return fmt.Errorf("node: naive needs username and password")
		}
	case WireGuard:
		w := n.WireGuard
		if w == nil || w.PrivateKey == "" || w.PeerPublicKey == "" || strings.TrimSpace(w.LocalAddress) == "" {
			return fmt.Errorf("node: wireguard needs private key, peer public key and local address")
		}
		w.LocalAddress = cidrList(w.LocalAddress)
		if w.Reserved != "" && ReservedBytes(w.Reserved) == nil {
			return fmt.Errorf("node: wireguard reserved must be three numbers 0-255, e.g. 1,2,3")
		}
	case Hysteria:
		if n.Hysteria == nil || n.Hysteria.Auth == "" {
			return fmt.Errorf("node: hysteria needs an auth string")
		}
	case ShadowTLS:
		if n.Password == "" || n.ShadowTLS == nil || n.ShadowTLS.SSPassword == "" || n.Method == "" {
			return fmt.Errorf("node: shadowtls needs the shadowtls password plus the inner shadowsocks method and password")
		}
	case SSH:
		if n.Username == "" {
			return fmt.Errorf("node: ssh needs a username")
		}
	case AnyTLS:
		if n.Password == "" {
			return fmt.Errorf("node: anytls needs a password")
		}
	case Socks, HTTP:
	default:
		return fmt.Errorf("node: unknown protocol %q", n.Protocol)
	}
	return nil
}

func wrapIPv6(host string) string {
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		return "[" + host + "]"
	}
	return host
}

// IsIP reports whether s parses as an IP literal.
func IsIP(s string) bool { return net.ParseIP(strings.Trim(s, "[]")) != nil }

// cidrList gives bare addresses a host prefix ("10.0.0.2" -> "10.0.0.2/32"),
// which sing-box requires and Clash configs usually omit.
func cidrList(s string) string {
	parts := strings.Split(s, ",")
	out := parts[:0]
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		if !strings.Contains(p, "/") {
			if strings.Contains(p, ":") {
				p += "/128"
			} else {
				p += "/32"
			}
		}
		out = append(out, p)
	}
	return strings.Join(out, ",")
}

// ReservedBytes parses a WireGuard reserved triple "a,b,c" (each 0-255);
// nil when the string is not exactly that.
func ReservedBytes(s string) []int {
	parts := strings.Split(s, ",")
	if len(parts) != 3 {
		return nil
	}
	out := make([]int, 0, 3)
	for _, p := range parts {
		v, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || v < 0 || v > 255 {
			return nil
		}
		out = append(out, v)
	}
	return out
}
