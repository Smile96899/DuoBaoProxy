package node

import (
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// ImportResult is what Import returns: every node that parsed plus one
// message per line that did not.
type ImportResult struct {
	Nodes  []*Node
	Errors []string
}

// Import accepts anything a user might paste or a subscription might serve:
// one or more share links (one per line), a base64 blob of such links, a
// Clash/Clash.Meta YAML document, or a single share link.
func Import(text string) ImportResult {
	text = strings.TrimSpace(text)
	var r ImportResult
	if text == "" {
		return r
	}
	if !strings.Contains(text, "://") {
		if d, ok := decodeB64(text); ok && strings.Contains(d, "://") {
			text = d
		}
	}
	if strings.Contains(text, "proxies:") {
		nodes, err := importClash(text)
		if err != nil {
			r.Errors = append(r.Errors, "clash: "+err.Error())
		}
		r.Nodes = append(r.Nodes, nodes...)
		return r
	}
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r", ""), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		n, err := ParseLink(line)
		if err != nil {
			r.Errors = append(r.Errors, fmt.Sprintf("%s: %v", truncate(line, 40), err))
			continue
		}
		r.Nodes = append(r.Nodes, n)
	}
	return r
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// --- clash ---------------------------------------------------------------

type clashProxy map[string]any

func (p clashProxy) str(keys ...string) string {
	for _, k := range keys {
		switch v := p[k].(type) {
		case string:
			return v
		case int:
			return strconv.Itoa(v)
		case float64:
			return strconv.Itoa(int(v))
		case bool:
			return strconv.FormatBool(v)
		}
	}
	return ""
}

func (p clashProxy) integer(k string) int {
	switch v := p[k].(type) {
	case int:
		return v
	case float64:
		return int(v)
	case string:
		n, _ := strconv.Atoi(strings.Fields(v + " ")[0])
		return n
	}
	return 0
}

func (p clashProxy) boolean(k string) bool {
	switch v := p[k].(type) {
	case bool:
		return v
	case string:
		return v == "true" || v == "1"
	}
	return false
}

func (p clashProxy) list(k string) []string {
	var out []string
	switch v := p[k].(type) {
	case []any:
		for _, it := range v {
			out = append(out, fmt.Sprint(it))
		}
	case string:
		out = append(out, v)
	}
	return out
}

func (p clashProxy) child(keys ...string) clashProxy {
	for _, k := range keys {
		if m, ok := p[k].(map[string]any); ok {
			return clashProxy(m)
		}
	}
	return nil
}

func (p clashProxy) header(name string) string {
	h, _ := p["headers"].(map[string]any)
	for k, v := range h {
		if strings.EqualFold(k, name) {
			switch x := v.(type) {
			case string:
				return x
			case []any:
				if len(x) > 0 {
					return fmt.Sprint(x[0])
				}
			}
		}
	}
	return ""
}

func importClash(text string) ([]*Node, error) {
	var doc struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if err := yaml.Unmarshal([]byte(text), &doc); err != nil {
		return nil, err
	}
	var nodes []*Node
	for _, m := range doc.Proxies {
		p := clashProxy(m)
		if n := clashNode(p); n != nil {
			nodes = append(nodes, n)
		}
	}
	return nodes, nil
}

func clashNode(p clashProxy) *Node {
	typ := strings.ToLower(p.str("type"))
	n := &Node{Name: p.str("name"), Server: p.str("server"), Port: p.integer("port")}
	smux := p.child("smux")

	switch typ {
	case "ss":
		n.Protocol = Shadowsocks
		n.Method = strings.ReplaceAll(p.str("cipher"), "dummy", "none")
		n.Password = p.str("password")
		if p.boolean("udp-over-tcp") {
			n.UDPOverTCP = p.integer("udp-over-tcp-version")
			if n.UDPOverTCP == 0 {
				n.UDPOverTCP = 2
			}
		}
		if plugin := p.str("plugin"); plugin != "" {
			opts := p.child("plugin-opts")
			if opts == nil {
				opts = clashProxy{}
			}
			switch plugin {
			case "shadow-tls":
				// ss + shadow-tls plugin is our shadowtls node.
				n.Protocol = ShadowTLS
				v := opts.integer("version")
				if v == 0 {
					v = 2 // mihomo defaults the shadow-tls plugin to v2
				}
				n.ShadowTLS = &ShadowTLSOptions{Version: v, SSPassword: n.Password}
				n.Password = opts.str("password")
				s := n.stream()
				s.SNI = opts.str("host")
				s.Fingerprint = p.str("client-fingerprint")
			case "obfs":
				n.Plugin = "obfs-local;obfs=" + opts.str("mode") + ";obfs-host=" + opts.str("host")
			case "v2ray-plugin":
				parts := []string{"v2ray-plugin"}
				if mode := opts.str("mode"); mode != "" && mode != "websocket" {
					parts = append(parts, "mode="+mode)
				}
				if opts.boolean("tls") {
					parts = append(parts, "tls")
				}
				if h := opts.str("host"); h != "" {
					parts = append(parts, "host="+h)
				}
				if path := opts.str("path"); path != "" {
					parts = append(parts, "path="+path)
				}
				n.Plugin = strings.Join(parts, ";")
			}
		}
		if smux != nil && smux.boolean("enabled") {
			n.stream().Multiplex = 1
		}
	case "socks5", "http":
		n.Protocol = Socks
		n.SocksVersion = 5
		if typ == "http" {
			n.Protocol = HTTP
			n.SocksVersion = 0
		}
		n.Username, n.Password = p.str("username"), p.str("password")
		s := n.stream()
		if p.boolean("tls") {
			s.Security = "tls"
		}
		s.AllowInsecure = p.boolean("skip-cert-verify")
	case "trojan", "vless":
		n.Protocol = typ
		s := n.stream()
		if typ == "vless" {
			n.UUID = p.str("uuid")
			n.Flow = p.str("flow")
			s.PacketEncoding = "xudp"
			if p.boolean("packet-addr") {
				s.PacketEncoding = "packetaddr"
			}
			if p.boolean("tls") || p.child("reality-opts") != nil {
				s.Security = "tls"
			}
		} else {
			n.Password = p.str("password")
			s.Security = "tls"
		}
		s.Network = strings.ReplaceAll(p.str("network"), "h2", "http")
		s.SNI = p.str("sni", "servername")
		s.ALPN = strings.Join(p.list("alpn"), ",")
		s.AllowInsecure = p.boolean("skip-cert-verify")
		s.Fingerprint = p.str("client-fingerprint")
		if smux != nil && smux.boolean("enabled") {
			s.Multiplex = 1
		}
		clashTransport(p, s)
		if reality := p.child("reality-opts"); reality != nil {
			s.RealityPublicKey = reality.str("public-key")
			s.RealityShortID = reality.str("short-id")
		}
	case "vmess":
		n.Protocol = VMess
		n.UUID = p.str("uuid")
		n.AlterID = p.integer("alterId")
		n.VMessSecurity = p.str("cipher")
		if n.VMessSecurity == "" {
			n.VMessSecurity = "auto"
		}
		s := n.stream()
		s.Network = strings.ReplaceAll(p.str("network"), "h2", "http")
		s.SNI = p.str("sni", "servername")
		s.ALPN = strings.Join(p.list("alpn"), ",")
		if p.boolean("tls") {
			s.Security = "tls"
		}
		s.AllowInsecure = p.boolean("skip-cert-verify")
		s.Fingerprint = p.str("client-fingerprint")
		if smux != nil && smux.boolean("enabled") {
			s.Multiplex = 1
		}
		if p.boolean("xudp") {
			s.PacketEncoding = "xudp"
		}
		if p.boolean("packet-addr") {
			s.PacketEncoding = "packetaddr"
		}
		clashTransport(p, s)
	case "wireguard":
		n.Protocol = WireGuard
		addr := p.str("ip")
		if v6 := p.str("ipv6"); v6 != "" {
			addr += "," + v6
		}
		peer := p
		if p.str("public-key") == "" {
			// mihomo's multi-peer form keeps the peer fields under peers[0].
			if peers, ok := p["peers"].([]any); ok && len(peers) > 0 {
				if m, ok := peers[0].(map[string]any); ok {
					peer = clashProxy(m)
					if s := peer.str("server"); s != "" {
						n.Server, n.Port = s, peer.integer("port")
					}
				}
			}
		}
		n.WireGuard = &WireGuardOptions{PrivateKey: p.str("private-key"), PeerPublicKey: peer.str("public-key"), PreSharedKey: peer.str("pre-shared-key"), LocalAddress: addr, MTU: p.integer("mtu"), Reserved: clashReserved(peer.list("reserved"))}
	case "hysteria":
		n.Protocol = Hysteria
		n.Hysteria = &HysteriaOptions{Auth: p.str("auth-str", "auth_str", "auth"), Obfs: p.str("obfs"), UpMbps: p.integer("up"), DownMbps: p.integer("down")}
		n.QUICTLS = &QUICTLS{SNI: p.str("sni"), ALPN: strings.Join(p.list("alpn"), ","), AllowInsecure: p.boolean("skip-cert-verify")}
	case "hysteria2":
		n.Protocol = Hysteria2
		n.Password = p.str("password")
		n.Hysteria2 = &Hysteria2Options{
			HopPorts:     p.str("ports"),
			ObfsPassword: p.str("obfs-password"),
			UpMbps:       p.integer("up"),
			DownMbps:     p.integer("down"),
			HopInterval:  10,
		}
		n.QUICTLS = &QUICTLS{SNI: p.str("sni"), CA: p.str("ca-str"), AllowInsecure: p.boolean("skip-cert-verify")}
	case "tuic":
		n.Protocol = TUIC
		n.UUID, n.Password = p.str("uuid"), p.str("password")
		t := &TUICOptions{
			UDPRelayMode:      p.str("udp-relay-mode"),
			CongestionControl: p.str("congestion-controller"),
			ZeroRTTHandshake:  p.boolean("reduce-rtt"),
			UDPOverStream:     p.boolean("udp-over-stream"),
			Heartbeat:         "10s",
		}
		if t.UDPRelayMode == "" {
			t.UDPRelayMode = "native"
		}
		if t.CongestionControl == "" {
			t.CongestionControl = "bbr"
		}
		if hb := p.integer("heartbeat-interval"); hb != 0 {
			t.Heartbeat = strconv.Itoa(hb) + "ms"
		}
		n.TUIC = t
		n.QUICTLS = &QUICTLS{
			SNI: p.str("sni"), ALPN: strings.Join(p.list("alpn"), ","), CA: p.str("ca-str"),
			AllowInsecure: p.boolean("skip-cert-verify"), DisableSNI: p.boolean("disable-sni"),
		}
		if ip := p.str("ip"); ip != "" {
			if n.QUICTLS.SNI == "" {
				n.QUICTLS.SNI = n.Server
			}
			n.Server = ip
		}
	default:
		return nil
	}
	if n.Validate() != nil {
		return nil
	}
	return n
}

func clashTransport(p clashProxy, s *Stream) {
	if ws := p.child("ws-opts", "ws-opt"); ws != nil {
		s.Host = ws.header("Host")
		s.Path = ws.str("path")
		s.EarlyDataLength = ws.integer("max-early-data")
		s.EarlyDataName = ws.str("early-data-header-name")
	}
	if g := p.child("grpc-opts", "grpc-opt"); g != nil {
		s.Path = g.str("grpc-service-name")
	}
	if h2 := p.child("h2-opts", "h2-opt"); h2 != nil {
		if hosts := h2.list("host"); len(hosts) > 0 {
			s.Host = hosts[0]
		}
		s.Path = h2.str("path")
	}
	if th := p.child("http-opts", "http-opt"); th != nil {
		s.Network = "tcp"
		s.HeaderType = "http"
		s.Host = th.header("Host")
		if paths := th.list("path"); len(paths) > 0 {
			s.Path = paths[0]
		}
	}
}

// clashReserved accepts [1,2,3] or mihomo's base64 form "AQID" and returns "1,2,3".
func clashReserved(v []string) string {
	if len(v) == 1 {
		if b, err := base64.StdEncoding.DecodeString(v[0]); err == nil && len(b) == 3 {
			return fmt.Sprintf("%d,%d,%d", b[0], b[1], b[2])
		}
	}
	return strings.Join(v, ",")
}
