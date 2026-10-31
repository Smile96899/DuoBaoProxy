// Package xray renders Nodes into xray-core JSON configuration.
package xray

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"duobaoproxy/node"
	"duobaoproxy/render"
	"duobaoproxy/render/singbox"
)

type Renderer struct{}

func New() *Renderer { return &Renderer{} }

var supported = map[string]bool{
	node.Socks: true, node.HTTP: true, node.Shadowsocks: true, node.VMess: true, node.VLESS: true, node.Trojan: true,
	node.WireGuard: true,
}

func (r *Renderer) Supports(protocol string) bool { return supported[protocol] }

// --- outbound -----------------------------------------------------------

func streamSettings(n *node.Node, s *node.Stream, set *render.Settings) (render.Obj, error) {
	ss := render.Obj{}
	network := "tcp"
	if s != nil && s.Network != "" {
		network = s.Network
	}
	switch network {
	case "tcp":
		ss["network"] = "tcp"
		if s != nil && s.HeaderType == "http" {
			req := render.Obj{"headers": render.Obj{"Host": render.Strings(render.SplitComma(s.Host))}}
			if s.Path != "" {
				req["path"] = render.Strings(render.SplitComma(s.Path))
			}
			ss["tcpSettings"] = render.Obj{"header": render.Obj{"type": "http", "request": req}}
		}
	case "ws":
		ss["network"] = "ws"
		w := render.Obj{}
		if s.Path != "" {
			w["path"] = s.Path
		}
		if s.Host != "" {
			w["host"] = s.Host
		}
		ss["wsSettings"] = w
	case "http":
		ss["network"] = "h2"
		h := render.Obj{}
		if s.Path != "" {
			h["path"] = s.Path
		}
		if s.Host != "" {
			h["host"] = render.Strings(render.SplitComma(s.Host))
		}
		ss["httpSettings"] = h
	case "grpc":
		ss["network"] = "grpc"
		g := render.Obj{}
		if s.Path != "" {
			g["serviceName"] = s.Path
		}
		ss["grpcSettings"] = g
	case "httpupgrade":
		ss["network"] = "httpupgrade"
		h := render.Obj{}
		if s.Path != "" {
			h["path"] = s.Path
		}
		if s.Host != "" {
			h["host"] = s.Host
		}
		ss["httpupgradeSettings"] = h
	case "xhttp":
		ss["network"] = "xhttp"
		x := render.Obj{}
		if s.Path != "" {
			x["path"] = s.Path
		}
		if s.Host != "" {
			x["host"] = s.Host
		}
		ss["xhttpSettings"] = x
	default:
		ss["network"] = network
	}

	if s != nil && s.Security == "tls" {
		fp := s.Fingerprint
		if fp == "" {
			fp = set.Fingerprint
		}
		if pbk := strings.TrimSpace(s.RealityPublicKey); pbk != "" {
			if fp == "" {
				fp = "chrome"
			}
			r := render.Obj{"publicKey": pbk, "fingerprint": fp}
			if s.SNI != "" {
				r["serverName"] = s.SNI
			}
			if sid := render.SplitComma(s.RealityShortID + ","); len(sid) > 0 {
				r["shortId"] = sid[0]
			}
			if s.RealitySpiderX != "" {
				r["spiderX"] = s.RealitySpiderX
			}
			ss["security"] = "reality"
			ss["realitySettings"] = r
		} else {
			t := render.Obj{}
			if s.SNI != "" {
				t["serverName"] = s.SNI
			}
			if s.AllowInsecure || set.SkipCertVerify {
				// xray dropped allowInsecure; pin the certificate instead.
				pin, err := pinnedCert(n, s)
				if err != nil {
					return nil, fmt.Errorf("xray 不再支持跳过证书验证，且无法获取服务器证书用于固定：%w", err)
				}
				t["pinnedPeerCertSha256"] = pin
			}
			if v := strings.TrimSpace(s.ALPN); v != "" {
				t["alpn"] = render.Strings(render.SplitComma(v))
			}
			if fp != "" {
				t["fingerprint"] = fp
			}
			if v := strings.TrimSpace(s.Certificate); v != "" {
				t["certificates"] = []any{render.Obj{"certificate": render.Strings(strings.Split(v, "\n")), "usage": "verify"}}
			}
			ss["security"] = "tls"
			ss["tlsSettings"] = t
		}
	} else {
		ss["security"] = "none"
	}
	return ss, nil
}

// Outbound renders one node as an xray outbound without tag.
func Outbound(n *node.Node, set *render.Settings) (render.Obj, error) {
	if !supported[n.Protocol] {
		return nil, fmt.Errorf("%w: %s", render.ErrUnsupportedProtocol, n.Protocol)
	}
	if err := n.Validate(); err != nil {
		return nil, err
	}
	out := render.Obj{}
	switch n.Protocol {
	case node.Socks, node.HTTP:
		if n.Protocol == node.Socks && n.SocksVersion == 4 {
			return nil, fmt.Errorf("render: xray does not support socks4 outbounds")
		}
		out["protocol"] = n.Protocol
		srv := render.Obj{"address": n.Server, "port": n.Port}
		if n.Username != "" && n.Password != "" {
			srv["users"] = []any{render.Obj{"user": n.Username, "pass": n.Password}}
		}
		out["settings"] = render.Obj{"servers": []any{srv}}
	case node.Shadowsocks:
		if strings.TrimSpace(n.Plugin) != "" {
			return nil, fmt.Errorf("render: xray does not support shadowsocks plugins")
		}
		out["protocol"] = "shadowsocks"
		srv := render.Obj{"address": n.Server, "port": n.Port, "method": n.Method, "password": n.Password}
		if n.UDPOverTCP != 0 {
			srv["uot"] = true
			srv["UoTVersion"] = n.UDPOverTCP
		}
		out["settings"] = render.Obj{"servers": []any{srv}}
	case node.VMess:
		sec := n.VMessSecurity
		if sec == "" {
			sec = "auto"
		}
		out["protocol"] = "vmess"
		out["settings"] = render.Obj{"vnext": []any{render.Obj{
			"address": n.Server, "port": n.Port,
			"users": []any{render.Obj{"id": strings.TrimSpace(n.UUID), "alterId": n.AlterID, "security": sec}},
		}}}
	case node.VLESS:
		flow := strings.TrimSuffix(n.Flow, "-udp443")
		if flow == "none" {
			flow = ""
		}
		user := render.Obj{"id": strings.TrimSpace(n.UUID), "encryption": "none"}
		if flow != "" {
			user["flow"] = flow
		}
		out["protocol"] = "vless"
		out["settings"] = render.Obj{"vnext": []any{render.Obj{"address": n.Server, "port": n.Port, "users": []any{user}}}}
	case node.WireGuard:
		w := n.WireGuard
		allowed := []any{"0.0.0.0/0"}
		for _, a := range render.SplitComma(w.LocalAddress) {
			if strings.Contains(a, ":") {
				allowed = append(allowed, "::/0")
				break
			}
		}
		peer := render.Obj{"publicKey": w.PeerPublicKey, "endpoint": fmt.Sprintf("%s:%d", n.Server, n.Port), "allowedIPs": allowed}
		if w.PreSharedKey != "" {
			peer["preSharedKey"] = w.PreSharedKey
		}
		st := render.Obj{"secretKey": w.PrivateKey, "address": render.Strings(render.SplitComma(w.LocalAddress)), "peers": []any{peer}}
		if w.MTU > 0 {
			st["mtu"] = w.MTU
		}
		if r := node.ReservedBytes(w.Reserved); r != nil {
			st["reserved"] = []any{r[0], r[1], r[2]}
		}
		out["protocol"] = "wireguard"
		out["settings"] = st
		if err := render.MergeRaw(out, n.CustomOutbound); err != nil {
			return nil, err
		}
		return out, nil // no streamSettings for wireguard
	case node.Trojan:
		out["protocol"] = "trojan"
		out["settings"] = render.Obj{"servers": []any{render.Obj{"address": n.Server, "port": n.Port, "password": n.Password}}}
	}
	ss, err := streamSettings(n, n.Stream, set)
	if err != nil {
		return nil, err
	}
	out["streamSettings"] = ss
	if err := render.MergeRaw(out, n.CustomOutbound); err != nil {
		return nil, err
	}
	return out, nil
}

func needMux(n *node.Node, set *render.Settings) bool {
	switch n.Protocol {
	case node.VMess, node.Trojan, node.VLESS:
	default:
		return false
	}
	if set.Mux.Concurrency <= 0 {
		return false
	}
	on := set.Mux.DefaultOn
	if s := n.Stream; s != nil {
		if s.Network == "grpc" || s.Network == "xhttp" {
			return false
		}
		switch s.Multiplex {
		case 1:
			on = true
		case 2:
			on = false
		}
	}
	if n.Protocol == node.VLESS && n.Flow != "" && n.Flow != "none" {
		return false
	}
	return on
}

// --- rules --------------------------------------------------------------

func domainRule(r render.DomainRule) []any {
	var out []any
	for _, v := range r.Full {
		out = append(out, "full:"+v)
	}
	for _, v := range r.Suffix {
		out = append(out, "domain:"+v)
	}
	for _, v := range r.Keyword {
		out = append(out, "keyword:"+v)
	}
	for _, v := range r.Regex {
		out = append(out, "regexp:"+v)
	}
	for _, v := range r.Geosite {
		out = append(out, "geosite:"+v)
	}
	return out
}

func ipRule(r render.IPRule) []any {
	var out []any
	for _, v := range r.CIDR {
		out = append(out, v)
	}
	for _, v := range r.GeoIP {
		out = append(out, "geoip:"+v)
	}
	return out
}

func domainStrategy(s string) string {
	switch s {
	case "", "AsIs":
		return "AsIs"
	case "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only", "IPIfNonMatch":
		return "IPIfNonMatch"
	case "IPOnDemand":
		return "IPOnDemand"
	}
	return "AsIs"
}

// --- full config --------------------------------------------------------

func (r *Renderer) Render(req render.Request) (*render.Result, error) {
	set := req.Settings
	set.Defaults()
	if len(req.Chain) == 0 {
		return nil, fmt.Errorf("render: empty chain")
	}
	test := req.Mode == render.ModeTest
	// Front mode: on mobile the TUN is a sing-box front (FrontConfig) that
	// hands everything to xray over a loopback bridge. Export leaves the
	// bridge out and renders the plain proxy config.
	front := req.Mode == render.ModeFull && set.Mobile() && set.TUN.Enabled
	if front && (set.Bridge == nil || set.Bridge.Port <= 0) {
		return nil, fmt.Errorf("render: xray TUN on mobile needs settings.bridge")
	}
	if !test && set.TUN.Enabled && !set.Mobile() {
		return nil, fmt.Errorf("render: xray has no TUN inbound; use sing-box for VPN mode")
	}
	level := set.LogLevel
	if level == "trace" || level == "panic" || level == "fatal" {
		level = "debug"
	}
	if level == "warn" {
		level = "warning"
	}
	if test {
		level = "none" // tests report through the RPC result
	}
	cfg := render.Obj{"log": render.Obj{"loglevel": level}}

	// Inbounds
	var inbounds []any
	dnsInbounds := []any{"mixed-in"}
	if front {
		// The bridge: any app can reach loopback, hence the credentials.
		// Sniffing is forced on, otherwise domain rules only ever see the
		// IPs coming out of the TUN.
		b := set.Bridge
		settings := render.Obj{"udp": true, "auth": "noauth"}
		if b.User != "" && b.Pass != "" {
			settings["auth"] = "password"
			settings["accounts"] = []any{render.Obj{"user": b.User, "pass": b.Pass}}
		}
		inbounds = append(inbounds, render.Obj{
			"tag": "mixed-in", "protocol": "mixed", "listen": "127.0.0.1", "port": b.Port, "settings": settings,
			"sniffing": render.Obj{
				"enabled": true, "destOverride": []any{"http", "tls", "quic"},
				"routeOnly": set.Inbound.Sniff != render.SniffForDestination,
			},
		})
	}
	// iOS never gets the user's local port (see render/singbox).
	if !test && set.Inbound.Port > 0 && set.Platform != render.PlatformIOS {
		settings := render.Obj{"udp": true, "auth": "noauth"}
		if set.Inbound.Username != "" && set.Inbound.Password != "" {
			settings["auth"] = "password"
			settings["accounts"] = []any{render.Obj{"user": set.Inbound.Username, "pass": set.Inbound.Password}}
		}
		tag := "mixed-in"
		if front {
			tag = "user-in" // the bridge owns mixed-in
			dnsInbounds = append(dnsInbounds, tag)
		}
		in := render.Obj{"tag": tag, "protocol": "mixed", "listen": set.Inbound.Address, "port": set.Inbound.Port, "settings": settings}
		if set.Inbound.Sniff != render.SniffDisabled {
			in["sniffing"] = render.Obj{
				"enabled": true, "destOverride": []any{"http", "tls", "quic"},
				"routeOnly": set.Inbound.Sniff == render.SniffForRouting,
			}
		}
		inbounds = append(inbounds, in)
		extra, err := render.RawArray(set.CustomInbounds)
		if err != nil {
			return nil, err
		}
		inbounds = append(inbounds, extra...)
	}
	cfg["inbounds"] = inbounds

	// Outbounds: chain. Xray chains with sockopt.dialerProxy pointing at
	// the previous hop's tag.
	var outbounds []any
	var statsTags []string
	muxApplied := false
	var serverDomains []any
	n := len(req.Chain)
	for i, nd := range req.Chain {
		out, err := Outbound(nd, &set)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", nd.DisplayName(), err)
		}
		tag := render.Tag(i, n)
		out["tag"] = tag
		if i > 0 {
			ss, _ := out["streamSettings"].(render.Obj)
			if ss == nil {
				ss = render.Obj{}
				out["streamSettings"] = ss
			}
			ss["sockopt"] = render.Obj{"dialerProxy": render.Tag(i-1, n)}
		}
		if set.TCPFastOpen && render.TCPProtocol(nd.Protocol) {
			ss, _ := out["streamSettings"].(render.Obj)
			if ss == nil {
				ss = render.Obj{}
				out["streamSettings"] = ss
			}
			so, _ := ss["sockopt"].(render.Obj)
			if so == nil {
				so = render.Obj{}
				ss["sockopt"] = so
			}
			so["tcpFastOpen"] = true
		}
		if !muxApplied && needMux(nd, &set) {
			out["mux"] = render.Obj{"enabled": true, "concurrency": set.Mux.Concurrency}
			muxApplied = true
		}
		if !node.IsIP(nd.Server) {
			serverDomains = append(serverDomains, "full:"+strings.ToLower(nd.Server))
		}
		outbounds = append(outbounds, out)
		statsTags = append([]string{tag}, statsTags...)
	}
	// Xray uses the first outbound as default; "proxy" must come first.
	if n > 1 {
		outbounds = append([]any{outbounds[n-1]}, outbounds[:n-1]...)
	}
	freeDirect := render.Obj{"tag": "direct", "protocol": "freedom"}
	freeBypass := render.Obj{"tag": "bypass", "protocol": "freedom"}
	if front {
		// Resolve sniffed domains through xray's DNS, not the system
		// resolver (which on mobile may follow the VPN back in).
		freeDirect["settings"] = render.Obj{"domainStrategy": "UseIP"}
		freeBypass["settings"] = render.Obj{"domainStrategy": "UseIP"}
	}
	outbounds = append(outbounds, freeDirect, freeBypass, render.Obj{"tag": "block", "protocol": "blackhole"})
	if !test {
		outbounds = append(outbounds, render.Obj{"tag": "dns-out", "protocol": "dns"})
	}
	cfg["outbounds"] = outbounds

	if test {
		cfg["routing"] = render.Obj{"domainStrategy": "AsIs", "rules": []any{}}
		b, err := json.MarshalIndent(cfg, "", "  ")
		if err != nil {
			return nil, err
		}
		return &render.Result{Config: b, StatsTags: statsTags}, nil
	}

	// DNS
	proxyDomains := domainRule(render.ParseDomainRules(set.Routing.ProxyDomain))
	directDomains := domainRule(render.ParseDomainRules(set.Routing.DirectDomain))
	var dnsServers, remote, direct []any
	var remoteDomains, directDomainList []any
	if set.Routing.DNSRouting {
		remoteDomains = proxyDomains
		directDomainList = append(append([]any{}, directDomains...), serverDomains...)
	} else {
		directDomainList = serverDomains
	}
	// Every server of a list carries the list's domains; xray falls back
	// through them in order.
	for _, a := range render.DNSList(set.Routing.RemoteDNS, set.Routing.DoHFirst) {
		o := render.Obj{"address": a}
		if len(remoteDomains) > 0 {
			o["domains"] = remoteDomains
		}
		remote = append(remote, o)
	}
	for _, a := range render.DNSList(set.Routing.DirectDNS, set.Routing.DoHFirst) {
		o := render.Obj{"address": a, "skipFallback": true}
		if len(directDomainList) > 0 {
			o["domains"] = directDomainList
		}
		direct = append(direct, o)
	}
	if set.Routing.DNSFinalOut == "bypass" {
		dnsServers = append(append(dnsServers, direct...), remote...)
	} else {
		dnsServers = append(append(dnsServers, remote...), direct...)
	}
	dnsServers = append(dnsServers, "localhost")
	dns := render.Obj{"servers": dnsServers, "tag": "dns-in"}
	if strategy := set.Routing.RemoteDNSStrategy; strategy != "" {
		switch strategy {
		case "ipv4_only":
			dns["queryStrategy"] = "UseIPv4"
		case "ipv6_only":
			dns["queryStrategy"] = "UseIPv6"
		default:
			dns["queryStrategy"] = "UseIP"
		}
	}
	if len(set.Routing.CustomDNS) > 0 {
		dns = render.Obj{}
		if err := render.MergeRaw(dns, set.Routing.CustomDNS); err != nil {
			return nil, err
		}
	}
	cfg["dns"] = dns

	// Routing
	var rules []any
	rule := func(o render.Obj) render.Obj { o["type"] = "field"; return o }
	hijack := render.Obj{"inboundTag": dnsInbounds, "port": 53, "network": "udp", "outboundTag": "dns-out"}
	if front && set.TUN.LooseDNSHijack {
		hijack["ip"] = render.Strings(render.TunDNS)
	}
	rules = append(rules, rule(hijack))
	custom, err := render.RawArray(set.Routing.CustomRules)
	if err != nil {
		return nil, err
	}
	rules = append(rules, custom...)
	add := func(list []any, key, out string) {
		if len(list) > 0 {
			rules = append(rules, rule(render.Obj{key: list, "outboundTag": out}))
		}
	}
	add(domainRule(render.ParseDomainRules(set.Routing.BlockDomain)), "domain", "block")
	add(proxyDomains, "domain", "proxy")
	add(directDomains, "domain", "bypass")
	add(ipRule(render.ParseIPRules(set.Routing.BlockIP)), "ip", "block")
	add(ipRule(render.ParseIPRules(set.Routing.ProxyIP)), "ip", "proxy")
	add(ipRule(render.ParseIPRules(set.Routing.DirectIP)), "ip", "bypass")
	rules = append(rules,
		rule(render.Obj{"network": "udp", "port": "135,137,138,139,5353", "outboundTag": "block"}),
		rule(render.Obj{"ip": []any{"224.0.0.0/3", "ff00::/8"}, "outboundTag": "block"}),
	)
	final := set.Routing.Final
	if final == "" {
		final = "proxy"
	}
	rules = append(rules, rule(render.Obj{"network": "tcp,udp", "outboundTag": final}))
	cfg["routing"] = render.Obj{"domainStrategy": domainStrategy(set.Routing.DomainStrategy), "rules": rules}

	if err := render.MergeRaw(cfg, req.Chain[n-1].CustomConfig); err != nil {
		return nil, err
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return nil, err
	}
	res := &render.Result{Config: b, StatsTags: statsTags}
	if front {
		if res.FrontConfig, err = singbox.RenderXrayFront(set, set.Bridge.Port, set.Bridge.User, set.Bridge.Pass); err != nil {
			return nil, err
		}
	}
	return res, nil
}

// pinnedCert returns the hex SHA-256 of the server certificate: from the
// certificate the node carries, otherwise fetched from the server (trust on
// first use) so self-signed setups keep working on xray.
func pinnedCert(n *node.Node, s *node.Stream) (string, error) {
	if v := strings.TrimSpace(s.Certificate); v != "" {
		if b, _ := pem.Decode([]byte(v)); b != nil {
			sum := sha256.Sum256(b.Bytes)
			return hex.EncodeToString(sum[:]), nil
		}
	}
	sni := strings.TrimSpace(s.SNI)
	if sni == "" {
		sni = n.Server
	}
	d := &net.Dialer{Timeout: 8 * time.Second}
	conn, err := tls.DialWithDialer(d, "tcp", net.JoinHostPort(n.Server, strconv.Itoa(n.Port)), &tls.Config{InsecureSkipVerify: true, ServerName: sni})
	if err != nil {
		return "", err
	}
	defer conn.Close()
	certs := conn.ConnectionState().PeerCertificates
	if len(certs) == 0 {
		return "", fmt.Errorf("server sent no certificate")
	}
	sum := sha256.Sum256(certs[0].Raw)
	return hex.EncodeToString(sum[:]), nil
}
