// Package singbox renders Nodes into sing-box 1.12+ configuration
// (rule actions, typed DNS servers, rule sets; no legacy geoip/geosite).
package singbox

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"hproxy/node"
	"hproxy/render"
)

type Renderer struct{}

func New() *Renderer { return &Renderer{} }

var supported = map[string]bool{
	node.Socks: true, node.HTTP: true, node.Shadowsocks: true, node.VMess: true, node.VLESS: true,
	node.Trojan: true, node.Hysteria2: true, node.TUIC: true,
}

func (r *Renderer) Supports(protocol string) bool { return supported[protocol] }

// --- outbound -----------------------------------------------------------

func transport(s *node.Stream, out render.Obj) {
	if s == nil {
		return
	}
	switch s.Network {
	case "", "tcp":
		if s.HeaderType == "http" {
			out["transport"] = render.Obj{
				"type": "http", "method": "GET", "path": s.Path,
				"headers": render.Obj{"Host": render.Strings(render.SplitComma(s.Host))},
			}
		}
	case "ws":
		t := render.Obj{"type": "ws"}
		if s.Host != "" {
			t["headers"] = render.Obj{"Host": s.Host}
		}
		path, ed, hasEd := strings.Cut(s.Path, "?ed=")
		if path != "" {
			t["path"] = path
		}
		if hasEd {
			var n int
			fmt.Sscanf(ed, "%d", &n)
			if n > 0 {
				t["max_early_data"] = n
				t["early_data_header_name"] = "Sec-WebSocket-Protocol"
			}
		}
		if s.EarlyDataLength > 0 {
			t["max_early_data"] = s.EarlyDataLength
			t["early_data_header_name"] = s.EarlyDataName
		}
		out["transport"] = t
	case "http":
		t := render.Obj{"type": "http"}
		if s.Path != "" {
			t["path"] = s.Path
		}
		if s.Host != "" {
			t["host"] = render.Strings(render.SplitComma(s.Host))
		}
		out["transport"] = t
	case "grpc":
		t := render.Obj{"type": "grpc"}
		if s.Path != "" {
			t["service_name"] = s.Path
		}
		out["transport"] = t
	case "httpupgrade":
		t := render.Obj{"type": "httpupgrade"}
		if s.Path != "" {
			t["path"] = s.Path
		}
		if s.Host != "" {
			t["host"] = s.Host
		}
		out["transport"] = t
	case "xhttp":
		// sing-box has no xhttp transport; httpupgrade is the closest
		// upgrade-style transport, but it is not wire compatible.
		t := render.Obj{"type": "httpupgrade"}
		if s.Path != "" {
			t["path"] = s.Path
		}
		if s.Host != "" {
			t["host"] = s.Host
		}
		out["transport"] = t
	}
}

func tls(s *node.Stream, set *render.Settings, out render.Obj) {
	if s == nil || s.Security != "tls" {
		return
	}
	t := render.Obj{"enabled": true}
	if s.AllowInsecure || set.SkipCertVerify {
		t["insecure"] = true
	}
	if v := strings.TrimSpace(s.SNI); v != "" {
		t["server_name"] = v
	}
	if v := strings.TrimSpace(s.Certificate); v != "" {
		t["certificate"] = v
	}
	if v := strings.TrimSpace(s.ALPN); v != "" {
		t["alpn"] = render.Strings(render.SplitComma(v))
	}
	fp := s.Fingerprint
	if fp == "" {
		fp = set.Fingerprint
	}
	if pbk := strings.TrimSpace(s.RealityPublicKey); pbk != "" {
		t["reality"] = render.Obj{"enabled": true, "public_key": pbk, "short_id": render.SplitComma(s.RealityShortID + ",")[0]}
		if fp == "" {
			fp = "random"
		}
	}
	if fp != "" {
		t["utls"] = render.Obj{"enabled": true, "fingerprint": fp}
	}
	out["tls"] = t
}

func quicTLS(q *node.QUICTLS, hy2 bool) render.Obj {
	t := render.Obj{"enabled": true}
	if q != nil {
		t["insecure"] = q.AllowInsecure
		t["disable_sni"] = q.DisableSNI
		if q.SNI != "" {
			t["server_name"] = q.SNI
		}
		if v := strings.TrimSpace(q.CA); v != "" {
			t["certificate"] = v
		}
		if v := strings.TrimSpace(q.ALPN); v != "" {
			t["alpn"] = render.Strings(render.SplitComma(v))
		}
	}
	if hy2 {
		t["alpn"] = []any{"h3"}
	}
	return t
}

// Outbound renders one node as a sing-box outbound without tag/detour.
func Outbound(n *node.Node, set *render.Settings) (render.Obj, error) {
	if !supported[n.Protocol] {
		return nil, fmt.Errorf("%w: %s", render.ErrUnsupportedProtocol, n.Protocol)
	}
	if err := n.Validate(); err != nil {
		return nil, err
	}
	out := render.Obj{"server": n.Server, "server_port": n.Port}
	switch n.Protocol {
	case node.Socks, node.HTTP:
		out["type"] = n.Protocol
		if n.Protocol == node.Socks && n.SocksVersion == 4 {
			out["version"] = "4"
		}
		if n.Username != "" && n.Password != "" {
			out["username"], out["password"] = n.Username, n.Password
		}
		transport(n.Stream, out)
		tls(n.Stream, set, out)
	case node.Shadowsocks:
		out["type"] = "shadowsocks"
		out["method"], out["password"] = n.Method, n.Password
		if n.UDPOverTCP != 0 {
			out["udp_over_tcp"] = render.Obj{"enabled": true, "version": n.UDPOverTCP}
		}
		if p := strings.TrimSpace(n.Plugin); p != "" {
			name, opts, _ := strings.Cut(p, ";")
			out["plugin"], out["plugin_opts"] = name, opts
		}
	case node.VMess:
		sec := n.VMessSecurity
		if sec == "" {
			sec = "auto"
		}
		out["type"], out["uuid"], out["alter_id"], out["security"] = "vmess", strings.TrimSpace(n.UUID), n.AlterID, sec
		transport(n.Stream, out)
		tls(n.Stream, set, out)
		if n.Stream != nil && n.Stream.PacketEncoding != "" {
			out["packet_encoding"] = n.Stream.PacketEncoding
		}
	case node.VLESS:
		flow := strings.TrimSuffix(n.Flow, "-udp443")
		if flow == "none" {
			flow = ""
		}
		out["type"], out["uuid"] = "vless", strings.TrimSpace(n.UUID)
		if flow != "" {
			out["flow"] = flow
		}
		transport(n.Stream, out)
		tls(n.Stream, set, out)
		if n.Stream != nil && n.Stream.PacketEncoding != "" {
			out["packet_encoding"] = n.Stream.PacketEncoding
		}
	case node.Trojan:
		out["type"], out["password"] = "trojan", n.Password
		transport(n.Stream, out)
		tls(n.Stream, set, out)
	case node.Hysteria2:
		out["type"], out["password"] = "hysteria2", n.Password
		out["tls"] = quicTLS(n.QUICTLS, true)
		if h := n.Hysteria2; h != nil {
			if h.UpMbps > 0 {
				out["up_mbps"] = h.UpMbps
			}
			if h.DownMbps > 0 {
				out["down_mbps"] = h.DownMbps
			}
			if hp := strings.TrimSpace(h.HopPorts); hp != "" {
				out["server_ports"] = render.Strings(render.SplitComma(strings.ReplaceAll(hp, "-", ":")))
				if h.HopInterval > 0 {
					out["hop_interval"] = fmt.Sprintf("%ds", h.HopInterval)
				}
			}
			if h.ObfsPassword != "" {
				out["obfs"] = render.Obj{"type": "salamander", "password": h.ObfsPassword}
			}
		}
	case node.TUIC:
		out["type"], out["uuid"], out["password"] = "tuic", n.UUID, n.Password
		out["tls"] = quicTLS(n.QUICTLS, false)
		t := n.TUIC
		if t == nil {
			t = &node.TUICOptions{CongestionControl: "bbr", UDPRelayMode: "native", Heartbeat: "10s"}
		}
		if t.CongestionControl != "" {
			out["congestion_control"] = t.CongestionControl
		}
		if t.UDPOverStream {
			out["udp_over_stream"] = true
		} else if t.UDPRelayMode != "" {
			out["udp_relay_mode"] = t.UDPRelayMode
		}
		out["zero_rtt_handshake"] = t.ZeroRTTHandshake
		if hb := strings.TrimSpace(t.Heartbeat); hb != "" {
			out["heartbeat"] = hb
		}
	case node.Naive:
		// sing-box 1.12+ ships a naive outbound (build tag with_naive_outbound).
		out["type"], out["username"], out["password"] = "naive", n.Username, n.Password
		tlsObj := render.Obj{"enabled": true}
		if o := n.Naive; o != nil {
			if o.SNI != "" {
				tlsObj["server_name"] = o.SNI
			}
			if v := strings.TrimSpace(o.Certificate); v != "" {
				tlsObj["certificate"] = v
			}
			if o.Protocol == "quic" {
				out["network"] = "udp"
			}
		}
		out["tls"] = tlsObj
	}
	if err := render.MergeRaw(out, n.CustomOutbound); err != nil {
		return nil, err
	}
	return out, nil
}

func needMux(n *node.Node, set *render.Settings) bool {
	switch n.Protocol {
	case node.VMess, node.Trojan, node.VLESS, node.Shadowsocks:
	default:
		return false
	}
	if set.Mux.Concurrency <= 0 {
		return false
	}
	on := set.Mux.DefaultOn
	if s := n.Stream; s != nil {
		if s.Network == "grpc" || s.Network == "quic" || (s.Network == "http" && s.Security == "tls") {
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

// --- DNS ----------------------------------------------------------------

// dnsServer converts a GUI address ("https://dns.google/dns-query",
// "tls://1.1.1.1", "udp://1.1.1.1", "1.1.1.1", "local", "dhcp://auto")
// into a typed sing-box DNS server.
func dnsServer(tag, address, detour string) render.Obj {
	o := render.Obj{"tag": tag}
	addr := strings.TrimSpace(address)
	switch {
	case addr == "" || addr == "local" || addr == "localhost":
		o["type"] = "local"
		return o
	case strings.HasPrefix(addr, "dhcp://"):
		o["type"] = "dhcp"
		if iface := strings.TrimPrefix(addr, "dhcp://"); iface != "" && iface != "auto" {
			o["interface"] = iface
		}
		return o
	case addr == "fakeip":
		o["type"] = "fakeip"
		return o
	}
	if !strings.Contains(addr, "://") {
		addr = "udp://" + addr
	}
	u, err := url.Parse(addr)
	if err != nil {
		o["type"], o["server"] = "udp", address
		return o
	}
	typ := strings.ToLower(u.Scheme)
	switch typ {
	case "udp", "tcp", "tls", "https", "quic", "h3":
	case "http":
		typ = "https"
	default:
		typ = "udp"
	}
	o["type"] = typ
	o["server"] = u.Hostname()
	if p := u.Port(); p != "" {
		var n int
		fmt.Sscanf(p, "%d", &n)
		o["server_port"] = n
	}
	if (typ == "https" || typ == "h3") && u.Path != "" && u.Path != "/dns-query" {
		o["path"] = u.Path
	}
	// sing-box rejects a detour to a plain direct outbound.
	if detour != "" && detour != "direct" {
		o["detour"] = detour
	}
	if tag != "dns-local" {
		o["domain_resolver"] = "dns-local"
	}
	return o
}

// --- rules --------------------------------------------------------------

type ruleBuilder struct {
	ruleSets map[string]render.Obj
	dir      string
}

func (b *ruleBuilder) ruleSet(kind, name string) string {
	tag := kind + "-" + name
	if _, ok := b.ruleSets[tag]; !ok {
		repo := "sing-geosite"
		if kind == "geoip" {
			repo = "sing-geoip"
		}
		b.ruleSets[tag] = render.Obj{
			"type": "remote", "tag": tag, "format": "binary",
			"url":             fmt.Sprintf("https://raw.githubusercontent.com/SagerNet/%s/rule-set/%s.srs", repo, tag),
			"download_detour": "direct", // never depend on the proxy to fetch the rules the proxy needs
			"update_interval": "7d",
		}
	}
	return tag
}

func (b *ruleBuilder) domainRule(r render.DomainRule) render.Obj {
	if r.Empty() {
		return nil
	}
	o := render.Obj{}
	if len(r.Full) > 0 {
		o["domain"] = render.Strings(r.Full)
	}
	if len(r.Suffix) > 0 {
		o["domain_suffix"] = render.Strings(r.Suffix)
	}
	if len(r.Keyword) > 0 {
		o["domain_keyword"] = render.Strings(r.Keyword)
	}
	if len(r.Regex) > 0 {
		o["domain_regex"] = render.Strings(r.Regex)
	}
	if len(r.Geosite) > 0 {
		var sets []string
		for _, g := range r.Geosite {
			sets = append(sets, b.ruleSet("geosite", g))
		}
		o["rule_set"] = render.Strings(sets)
	}
	return o
}

func (b *ruleBuilder) ipRule(r render.IPRule) render.Obj {
	if r.Empty() {
		return nil
	}
	o := render.Obj{}
	if len(r.CIDR) > 0 {
		o["ip_cidr"] = render.Strings(r.CIDR)
	}
	if len(r.GeoIP) > 0 {
		var sets []string
		for _, g := range r.GeoIP {
			if g == "private" {
				// sing-geoip ships no "private" set; sing-box has a native match.
				o["ip_is_private"] = true
				continue
			}
			sets = append(sets, b.ruleSet("geoip", g))
		}
		if len(sets) > 0 {
			o["rule_set"] = render.Strings(sets)
		}
	}
	return o
}

func withOut(rule render.Obj, outbound string) render.Obj {
	if rule == nil {
		return nil
	}
	if outbound == "block" {
		rule["action"] = "reject"
	} else {
		rule["outbound"] = outbound
	}
	return rule
}

func appendRule(rules *[]any, rule render.Obj) {
	if rule != nil {
		*rules = append(*rules, rule)
	}
}

// --- full config --------------------------------------------------------

func (r *Renderer) Render(req render.Request) (*render.Result, error) {
	set := req.Settings
	set.Defaults()
	if len(req.Chain) == 0 {
		return nil, fmt.Errorf("render: empty chain")
	}
	test := req.Mode == render.ModeTest
	cfg := render.Obj{"log": render.Obj{"level": set.LogLevel}}
	if test {
		// Tests report through the RPC result; their logs would only flood the GUI.
		cfg["log"] = render.Obj{"disabled": true}
	}

	// Inbounds
	var inbounds []any
	if !test && set.Inbound.Port > 0 {
		in := render.Obj{"type": "mixed", "tag": "mixed-in", "listen": set.Inbound.Address, "listen_port": set.Inbound.Port}
		if set.Inbound.Username != "" && set.Inbound.Password != "" {
			in["users"] = []any{render.Obj{"username": set.Inbound.Username, "password": set.Inbound.Password}}
		}
		inbounds = append(inbounds, in)
	}
	tun := !test && set.TUN.Enabled
	if tun {
		in := render.Obj{
			"type": "tun", "tag": "tun-in", "interface_name": set.TUN.InterfaceName,
			"auto_route": true, "strict_route": set.TUN.StrictRoute, "stack": set.TUN.Stack, "mtu": set.TUN.MTU,
		}
		addr := []any{"172.19.0.1/28"}
		if set.TUN.IPv6 {
			addr = append(addr, "fdfe:dcba:9876::1/126")
		}
		in["address"] = addr
		inbounds = append(inbounds, in)
	}
	if !test {
		extra, err := render.RawArray(set.CustomInbounds)
		if err != nil {
			return nil, err
		}
		inbounds = append(inbounds, extra...)
	}
	if len(inbounds) > 0 {
		cfg["inbounds"] = inbounds
	}

	// Outbounds: chain
	var outbounds []any
	var statsTags []string
	muxApplied := false
	var serverDomains []string
	n := len(req.Chain)
	for i, nd := range req.Chain {
		out, err := Outbound(nd, &set)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", nd.DisplayName(), err)
		}
		tag := render.Tag(i, n)
		out["tag"] = tag
		if i > 0 {
			out["detour"] = render.Tag(i-1, n)
		}
		if !muxApplied && needMux(nd, &set) {
			out["multiplex"] = render.Obj{"enabled": true, "protocol": set.Mux.Protocol, "padding": set.Mux.Padding, "max_streams": set.Mux.Concurrency}
			muxApplied = true
		}
		if !node.IsIP(nd.Server) {
			serverDomains = append(serverDomains, strings.ToLower(nd.Server))
		}
		outbounds = append(outbounds, out)
		statsTags = append([]string{tag}, statsTags...)
	}
	outbounds = append(outbounds,
		render.Obj{"type": "direct", "tag": "direct"},
		render.Obj{"type": "direct", "tag": "bypass"},
	)
	cfg["outbounds"] = outbounds

	rb := &ruleBuilder{ruleSets: map[string]render.Obj{}, dir: set.RuleSetDir}

	// DNS
	var dnsServers, dnsRules []any
	remote := dnsServer("dns-remote", set.Routing.RemoteDNS, "proxy")
	direct := dnsServer("dns-direct", set.Routing.DirectDNS, "direct")
	local := dnsServer("dns-local", set.Routing.UnderlyingDNS, "")
	if test {
		dnsServers = append(dnsServers, local)
	} else {
		dnsServers = append(dnsServers, remote, direct)
		if tun && set.TUN.FakeDNS {
			dnsServers = append(dnsServers, render.Obj{"type": "fakeip", "tag": "dns-fake", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"})
		}
		dnsServers = append(dnsServers, local)
	}
	dns := render.Obj{"servers": dnsServers, "independent_cache": true}
	if !test {
		if set.Routing.DNSRouting {
			appendRule(&dnsRules, withServer(rb.domainRule(render.ParseDomainRules(set.Routing.ProxyDomain)), "dns-remote"))
			appendRule(&dnsRules, withServer(rb.domainRule(render.ParseDomainRules(set.Routing.DirectDomain)), "dns-direct"))
		}
		if len(serverDomains) > 0 {
			appendRule(&dnsRules, withServer(render.Obj{"domain": render.Strings(serverDomains)}, "dns-direct"))
		}
		dnsRules = append(dnsRules,
			render.Obj{"query_type": []any{32, 33}, "action": "reject"},
			render.Obj{"domain_suffix": []any{".lan"}, "action": "reject"},
		)
		if tun && set.TUN.FakeDNS {
			dnsRules = append(dnsRules, render.Obj{"inbound": []any{"tun-in"}, "query_type": []any{"A", "AAAA"}, "action": "route", "server": "dns-fake"})
		}
		dns["rules"] = dnsRules
		if set.Routing.DNSFinalOut == "bypass" {
			dns["final"] = "dns-direct"
		} else {
			dns["final"] = "dns-remote"
		}
		if set.Routing.RemoteDNSStrategy != "" {
			dns["strategy"] = set.Routing.RemoteDNSStrategy
		}
	} else {
		dns["final"] = "dns-local"
	}
	if !test && len(set.Routing.CustomDNS) > 0 {
		dns = render.Obj{}
		if err := render.MergeRaw(dns, set.Routing.CustomDNS); err != nil {
			return nil, err
		}
	}
	cfg["dns"] = dns

	// Route
	var rules []any
	if !test {
		custom, err := render.RawArray(set.Routing.CustomRules)
		if err != nil {
			return nil, err
		}
		if set.Inbound.Sniff != render.SniffDisabled {
			// sing-box 1.12+ sniffs for routing only; the old
			// "override destination" switch has no equivalent.
			rules = append(rules, render.Obj{"action": "sniff"})
		}
		rules = append(rules, render.Obj{"protocol": []any{"dns"}, "action": "hijack-dns"})
		rules = append(rules, custom...)
		// Order decides who wins: blocking first (ads stay blocked for every
		// app), then per-app choices, then the proxy / direct lists.
		appendRule(&rules, withOut(rb.domainRule(render.ParseDomainRules(set.Routing.BlockDomain)), "block"))
		appendRule(&rules, withOut(rb.ipRule(render.ParseIPRules(set.Routing.BlockIP)), "block"))
		rules = append(rules, appRules(set.Apps)...)
		appendRule(&rules, withOut(rb.domainRule(render.ParseDomainRules(set.Routing.ProxyDomain)), "proxy"))
		appendRule(&rules, withOut(rb.domainRule(render.ParseDomainRules(set.Routing.DirectDomain)), "bypass"))
		appendRule(&rules, withOut(rb.ipRule(render.ParseIPRules(set.Routing.ProxyIP)), "proxy"))
		appendRule(&rules, withOut(rb.ipRule(render.ParseIPRules(set.Routing.DirectIP)), "bypass"))
		rules = append(rules,
			render.Obj{"network": []any{"udp"}, "port": []any{135, 137, 138, 139, 5353}, "action": "reject"},
			render.Obj{"ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "action": "reject"},
			render.Obj{"source_ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "action": "reject"},
		)
		if tun {
			matchOut := "bypass"
			if set.TUN.RuleWhite {
				matchOut = "proxy"
			}
			if len(set.TUN.RuleProcess) > 0 {
				rules = append(rules, render.Obj{"process_name": render.Strings(set.TUN.RuleProcess), "outbound": matchOut})
			}
			if len(set.TUN.RuleCIDR) > 0 {
				rules = append(rules, render.Obj{"ip_cidr": render.Strings(set.TUN.RuleCIDR), "outbound": matchOut})
			}
		}
	}
	route := render.Obj{
		"rules":                   rules,
		"auto_detect_interface":   true,
		"default_domain_resolver": render.Obj{"server": "dns-local"},
	}
	if !test {
		final := set.Routing.Final
		if final == "block" {
			final = "bypass" // sing-box has no block outbound any more; reject via rules instead
		}
		route["final"] = final
	} else {
		route["final"] = "proxy"
	}
	if len(rb.ruleSets) > 0 {
		var sets []any
		for _, rs := range rb.ruleSets {
			sets = append(sets, rs)
		}
		route["rule_set"] = sets
	}
	if req.Mode == render.ModeExport {
		delete(route, "auto_detect_interface")
	}
	cfg["route"] = route

	if !test && set.ClashAPIPort > 0 {
		cfg["experimental"] = render.Obj{"clash_api": render.Obj{
			"external_controller": fmt.Sprintf("127.0.0.1:%d", set.ClashAPIPort),
			"secret":              set.ClashAPISecret,
			"external_ui":         "dashboard",
		}}
	}

	// Per-node full-config override (exit node wins).
	if err := render.MergeRaw(cfg, req.Chain[n-1].CustomConfig); err != nil {
		return nil, err
	}

	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return nil, err
	}
	return &render.Result{Config: b, StatsTags: statsTags}, nil
}

// appRules turns the per-app list into process rules, one per outbound.
// sing-box knows the process for TUN traffic and for local connections to
// the mixed inbound, so these also work in system-proxy mode (for programs
// that use the system proxy).
func appRules(apps []render.AppRule) []any {
	byOut := map[string][]string{}
	var order []string
	for _, a := range apps {
		p := strings.TrimSpace(a.Process)
		if p == "" {
			continue
		}
		if _, seen := byOut[a.Outbound]; !seen {
			order = append(order, a.Outbound)
		}
		byOut[a.Outbound] = append(byOut[a.Outbound], p)
	}
	var rules []any
	for _, out := range order {
		var names, paths []string
		for _, p := range byOut[out] {
			if strings.ContainsAny(p, `/\`) {
				paths = append(paths, p)
			} else {
				names = append(names, p)
			}
		}
		if len(names) > 0 {
			appendRule(&rules, withOut(render.Obj{"process_name": render.Strings(names)}, out))
		}
		if len(paths) > 0 {
			appendRule(&rules, withOut(render.Obj{"process_path": render.Strings(paths)}, out))
		}
	}
	return rules
}

func withServer(rule render.Obj, server string) render.Obj {
	if rule == nil {
		return nil
	}
	rule["action"], rule["server"] = "route", server
	return rule
}
