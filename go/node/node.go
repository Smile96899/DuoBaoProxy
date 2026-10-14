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
	IKEv2       = "ikev2"
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
	QUICTLS   *QUICTLS          `json:"quic_tls,omitempty"`
	Naive     *NaiveOptions     `json:"naive,omitempty"`
	// IKEv2 carries the ikev2.Profile as raw JSON; the GUI edits it directly.
	IKEv2 json.RawMessage `json:"ikev2,omitempty"`

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
	if n.Protocol == IKEv2 {
		if len(n.IKEv2) == 0 {
			return fmt.Errorf("node: ikev2 profile is empty")
		}
		return nil
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
