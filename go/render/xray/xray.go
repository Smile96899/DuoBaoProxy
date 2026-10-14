// Package xray renders Nodes into xray-core JSON configuration.
package xray

import (
	"encoding/json"
	"fmt"
	"strings"

	"hproxy/node"
	"hproxy/render"
)

type Renderer struct{}

func New() *Renderer { return &Renderer{} }

var supported = map[string]bool{
	node.Socks: true, node.HTTP: true, node.Shadowsocks: true, node.VMess: true, node.VLESS: true, node.Trojan: true,
}

func (r *Renderer) Supports(protocol string) bool { return supported[protocol] }

// --- outbound -----------------------------------------------------------

func streamSettings(s *node.Stream, set *render.Settings) render.Obj {
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
				t["allowInsecure"] = true
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
	return ss
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
	case node.Trojan:
		out["protocol"] = "trojan"
		out["settings"] = render.Obj{"servers": []any{render.Obj{"address": n.Server, "port": n.Port, "password": n.Password}}}
	}
	out["streamSettings"] = streamSettings(n.Stream, set)
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
	if !test && set.TUN.Enabled {
		return nil, fmt.Errorf("render: xray has no TUN inbound; use sing-box for VPN mode")
	}
	level := set.LogLevel
	if level == "trace" || level == "panic" || level == "fatal" {
		level = "debug"
	}
	if level == "warn" {
		level = "warning"
	}
	cfg := render.Obj{"log": render.Obj{"loglevel": level}}

	// Inbounds
	var inbounds []any
	if !test && set.Inbound.Port > 0 {
		settings := render.Obj{"udp": true, "auth": "noauth"}
		if set.Inbound.Username != "" && set.Inbound.Password != "" {
			settings["auth"] = "password"
			settings["accounts"] = []any{render.Obj{"user": set.Inbound.Username, "pass": set.Inbound.Password}}
		}
		in := render.Obj{"tag": "mixed-in", "protocol": "mixed", "listen": set.Inbound.Address, "port": set.Inbound.Port, "settings": settings}
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
	outbounds = append(outbounds,
		render.Obj{"tag": "direct", "protocol": "freedom"},
		render.Obj{"tag": "bypass", "protocol": "freedom"},
		render.Obj{"tag": "block", "protocol": "blackhole"},
	)
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
	var dnsServers []any
	remote := render.Obj{"address": set.Routing.RemoteDNS}
	direct := render.Obj{"address": set.Routing.DirectDNS, "skipFallback": true}
	if set.Routing.DNSRouting {
		if len(proxyDomains) > 0 {
			remote["domains"] = proxyDomains
		}
		if len(directDomains)+len(serverDomains) > 0 {
			direct["domains"] = append(append([]any{}, directDomains...), serverDomains...)
		}
	} else if len(serverDomains) > 0 {
		direct["domains"] = serverDomains
	}
	if set.Routing.DNSFinalOut == "bypass" {
		dnsServers = append(dnsServers, direct, remote)
	} else {
		dnsServers = append(dnsServers, remote, direct)
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
	rules = append(rules, rule(render.Obj{"inboundTag": []any{"mixed-in"}, "port": 53, "network": "udp", "outboundTag": "dns-out"}))
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
	return &render.Result{Config: b, StatsTags: statsTags}, nil
}
