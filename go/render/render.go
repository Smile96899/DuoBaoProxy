// Package render turns Nodes plus user settings into core-native config.
// One renderer per core lives in a sub-package; this file holds the shared
// settings model, rule parsing and JSON helpers.
package render

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"unicode"

	"duobaoproxy/node"
)

// Mode selects how much of the config is produced.
type Mode int

const (
	// ModeFull is what Start() receives: inbounds, routing, DNS, TUN.
	ModeFull Mode = iota
	// ModeTest has no inbounds and minimal routing; used for URL tests.
	ModeTest
	// ModeExport is ModeFull without machine-specific paths.
	ModeExport
)

// SniffMode mirrors the GUI setting.
type SniffMode int

const (
	SniffDisabled SniffMode = iota
	SniffForRouting
	SniffForDestination
)

// Settings is the user configuration a renderer needs. The GUI stores it
// as JSON and sends it with every RenderConfig call.
type Settings struct {
	LogLevel string `json:"log_level,omitempty"`

	Inbound struct {
		Address        string    `json:"address,omitempty"` // default 127.0.0.1
		Port           int       `json:"port,omitempty"`    // 0 disables the mixed inbound
		Username       string    `json:"username,omitempty"`
		Password       string    `json:"password,omitempty"`
		Sniff          SniffMode `json:"sniff,omitempty"`
		DomainStrategy string    `json:"domain_strategy,omitempty"`
	} `json:"inbound"`
	// CustomInbounds is a core-native JSON array appended to the inbounds.
	CustomInbounds json.RawMessage `json:"custom_inbounds,omitempty"`

	// Apps routes traffic by originating process. Only cores with a TUN
	// inbound can see the process (sing-box); other cores ignore it.
	Apps []AppRule `json:"apps,omitempty"`

	TUN struct {
		Enabled       bool     `json:"enabled,omitempty"`
		Stack         string   `json:"stack,omitempty"` // gvisor system mixed
		MTU           int      `json:"mtu,omitempty"`
		IPv6          bool     `json:"ipv6,omitempty"`
		StrictRoute   bool     `json:"strict_route,omitempty"`
		FakeDNS       bool     `json:"fake_dns,omitempty"`
		InterfaceName string   `json:"interface_name,omitempty"`
		RuleWhite     bool     `json:"rule_white,omitempty"` // matched → proxy, else bypass
		RuleProcess   []string `json:"rule_process,omitempty"`
		RuleCIDR      []string `json:"rule_cidr,omitempty"`
		// LooseDNSHijack (mobile) answers only queries sent to the
		// tunnel's own DNS address; DNS aimed at other servers is routed
		// like any other traffic. Off (default) hijacks all DNS.
		LooseDNSHijack bool `json:"loose_dns_hijack,omitempty"`
	} `json:"tun"`

	Routing struct {
		ProxyDomain  []string `json:"proxy_domain,omitempty"`
		DirectDomain []string `json:"direct_domain,omitempty"`
		BlockDomain  []string `json:"block_domain,omitempty"`
		ProxyIP      []string `json:"proxy_ip,omitempty"`
		DirectIP     []string `json:"direct_ip,omitempty"`
		BlockIP      []string `json:"block_ip,omitempty"`
		Final        string   `json:"final,omitempty"` // proxy | bypass | block
		// CustomRules is a core-native JSON array inserted before ours.
		CustomRules            json.RawMessage `json:"custom_rules,omitempty"`
		DomainStrategy         string          `json:"domain_strategy,omitempty"`
		OutboundDomainStrategy string          `json:"outbound_domain_strategy,omitempty"`

		DNSRouting        bool   `json:"dns_routing,omitempty"`
		// RemoteDNS and DirectDNS may list several servers separated by
		// newlines or commas (DNSList); sing-box uses the first.
		RemoteDNS         string `json:"remote_dns,omitempty"`
		RemoteDNSStrategy string `json:"remote_dns_strategy,omitempty"`
		DirectDNS         string `json:"direct_dns,omitempty"`
		DirectDNSStrategy string `json:"direct_dns_strategy,omitempty"`
		DNSFinalOut       string `json:"dns_final_out,omitempty"` // proxy | bypass
		UnderlyingDNS     string `json:"underlying_dns,omitempty"`
		// DoHFirst moves encrypted servers (https, h3, tls, quic) ahead of
		// plain ones in each DNS list.
		DoHFirst bool `json:"doh_first,omitempty"`
		// CustomDNS replaces the whole generated dns object when set.
		CustomDNS json.RawMessage `json:"custom_dns,omitempty"`
	} `json:"routing"`

	Mux struct {
		Protocol    string `json:"protocol,omitempty"` // h2mux smux yamux
		Padding     bool   `json:"padding,omitempty"`
		Concurrency int    `json:"concurrency,omitempty"`
		DefaultOn   bool   `json:"default_on,omitempty"`
	} `json:"mux"`

	// TCPFastOpen enables TFO on the proxy's TCP dials.
	TCPFastOpen bool `json:"tcp_fast_open,omitempty"`

	// TLS defaults applied when the node leaves them empty.
	Fingerprint    string `json:"fingerprint,omitempty"`
	SkipCertVerify bool   `json:"skip_cert_verify,omitempty"`

	// Paths to geo assets for cores that need files on disk (xray).
	GeoIPPath   string `json:"geoip_path,omitempty"`
	GeositePath string `json:"geosite_path,omitempty"`
	// RuleSetDir is where sing-box caches downloaded rule sets.
	RuleSetDir string `json:"rule_set_dir,omitempty"`

	ClashAPIPort   int    `json:"clash_api_port,omitempty"`
	ClashAPISecret string `json:"clash_api_secret,omitempty"`

	// Platform selects the target OS: "" desktop, PlatformAndroid or
	// PlatformIOS. Only go/mobile sets it; desktop output never changes.
	Platform string `json:"platform,omitempty"`
	// OwnPackage is the app's Android package name, always kept out of
	// the VPN.
	OwnPackage string `json:"own_package,omitempty"`
	// Bridge is the loopback inbound xray listens on behind the sing-box
	// TUN front (mobile, TUN on). The caller picks a free port and random
	// credentials.
	Bridge *Bridge `json:"bridge,omitempty"`
}

const (
	PlatformAndroid = "android"
	PlatformIOS     = "ios"
)

// Mobile reports whether the config targets Android or iOS.
func (s *Settings) Mobile() bool { return s.Platform != "" }

// Bridge is a loopback mixed inbound with credentials.
type Bridge struct {
	Port int    `json:"port"`
	User string `json:"user"`
	Pass string `json:"pass"`
}

// TunDNS are the DNS addresses a mobile VPN hands to the OS: the address
// after the TUN's own (172.19.0.1/28, fdfe:dcba:9876::1/126).
var TunDNS = []string{"172.19.0.2", "fdfe:dcba:9876::2"}

// DNSList splits a DNS setting into servers (newlines, commas or spaces),
// dropping blanks and duplicates; with dohFirst, encrypted servers move
// ahead of plain ones, each group keeping its order.
func DNSList(s string, dohFirst bool) []string {
	var enc, plain []string
	seen := map[string]bool{}
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || unicode.IsSpace(r) }) {
		if seen[f] {
			continue
		}
		seen[f] = true
		if dohFirst && encryptedDNS(f) {
			enc = append(enc, f)
		} else {
			plain = append(plain, f)
		}
	}
	return append(enc, plain...)
}

func encryptedDNS(addr string) bool {
	l := strings.ToLower(addr)
	for _, p := range []string{"https://", "h3://", "tls://", "quic://"} {
		if strings.HasPrefix(l, p) {
			return true
		}
	}
	return false
}

// FirstDNS is DNSList's first entry, "" for none.
func FirstDNS(s string, dohFirst bool) string {
	if l := DNSList(s, dohFirst); len(l) > 0 {
		return l[0]
	}
	return ""
}

// TCPProtocol reports whether a protocol dials the server over TCP (the
// QUIC and WireGuard ones do not, so TFO does not apply).
func TCPProtocol(protocol string) bool {
	switch protocol {
	case node.Hysteria2, node.Hysteria, node.TUIC, node.WireGuard:
		return false
	}
	return true
}

// Defaults fills zero values with the GUI's defaults.
func (s *Settings) Defaults() {
	if s.LogLevel == "" {
		s.LogLevel = "info"
	}
	if s.Inbound.Address == "" {
		s.Inbound.Address = "127.0.0.1"
	}
	if s.TUN.Stack == "" {
		s.TUN.Stack = "gvisor"
		if s.Platform == PlatformIOS {
			s.TUN.Stack = "mixed" // gvisor alone is heavy inside the Network Extension
		}
	}
	if s.TUN.MTU == 0 {
		s.TUN.MTU = 9000
	}
	if s.TUN.InterfaceName == "" {
		s.TUN.InterfaceName = "duobao-tun"
	}
	if s.Routing.Final == "" {
		s.Routing.Final = "proxy"
	}
	if s.Routing.RemoteDNS == "" {
		s.Routing.RemoteDNS = "https://dns.google/dns-query"
	}
	if s.Routing.DirectDNS == "" {
		s.Routing.DirectDNS = "https://doh.pub/dns-query"
	}
	if s.Routing.DNSFinalOut == "" {
		s.Routing.DNSFinalOut = "proxy"
	}
	if s.Routing.UnderlyingDNS == "" {
		s.Routing.UnderlyingDNS = "local"
	}
	if s.Mux.Protocol == "" {
		s.Mux.Protocol = "h2mux"
	}
	if s.Mux.Concurrency == 0 {
		s.Mux.Concurrency = 8
	}
}

// AppRule sends one application's traffic to an outbound.
type AppRule struct {
	Name string `json:"name,omitempty"` // display name, e.g. "Google Chrome"
	// Process is the executable name ("chrome.exe") or, when it contains a
	// path separator, the full path. Matching is case-insensitive on Windows.
	Process  string `json:"process"`
	Outbound string `json:"outbound"` // proxy | bypass | block
	// Platform is "" for desktop rows and PlatformAndroid for rows whose
	// Process is an Android package name; each side ignores the other's.
	Platform string `json:"platform,omitempty"`
}

// PlatformApps returns the rows for the target platform: desktop rows on
// desktop, Android rows on Android, none on iOS (no per-app routing there).
func (s *Settings) PlatformApps() []AppRule {
	if s.Platform == PlatformIOS {
		return nil
	}
	var out []AppRule
	for _, a := range s.Apps {
		if a.Platform == s.Platform {
			out = append(out, a)
		}
	}
	return out
}

// Request is one render call. Chain lists the hops from the client
// outwards: Chain[0] is dialed first, the last element is the exit whose
// outbound is tagged "proxy".
type Request struct {
	Chain    []*node.Node
	Settings Settings
	Mode     Mode
}

// Result is the rendered config plus the outbound tags whose traffic the
// GUI should count (exit first).
type Result struct {
	Config    []byte
	StatsTags []string
	// FrontConfig is the sing-box TUN front feeding Config's loopback
	// bridge (xray on mobile with TUN on), nil otherwise. Start Config
	// first, then the front; stop in reverse.
	FrontConfig []byte
}

// Renderer is implemented per core.
type Renderer interface {
	Render(req Request) (*Result, error)
	// Supports reports whether this core can carry the protocol.
	Supports(protocol string) bool
}

var ErrUnsupportedProtocol = errors.New("render: protocol not supported by this core")

// Tag returns the outbound tag for hop i of n.
func Tag(i, n int) string {
	if i == n-1 {
		return "proxy"
	}
	return fmt.Sprintf("hop-%d", i)
}

// --- rule parsing -------------------------------------------------------

// DomainRule is a parsed user domain line.
type DomainRule struct {
	Full, Suffix, Keyword, Regex, Geosite []string
}

// IPRule is a parsed user IP line.
type IPRule struct {
	CIDR, GeoIP []string
}

func (r DomainRule) Empty() bool {
	return len(r.Full)+len(r.Suffix)+len(r.Keyword)+len(r.Regex)+len(r.Geosite) == 0
}

func (r IPRule) Empty() bool { return len(r.CIDR)+len(r.GeoIP) == 0 }

// ParseDomainRules understands the v2fly syntax: geosite:, full:, domain:,
// regexp:, keyword:, or a bare suffix.
func ParseDomainRules(lines []string) DomainRule {
	var r DomainRule
	for _, raw := range lines {
		item := strings.TrimSpace(raw)
		if item == "" || strings.HasPrefix(item, "#") {
			continue
		}
		lower := strings.ToLower(item)
		switch {
		case strings.HasPrefix(lower, "geosite:"):
			r.Geosite = append(r.Geosite, strings.ToLower(item[8:]))
		case strings.HasPrefix(lower, "full:"):
			r.Full = append(r.Full, strings.ToLower(item[5:]))
		case strings.HasPrefix(lower, "domain:"):
			r.Suffix = append(r.Suffix, strings.ToLower(item[7:]))
		case strings.HasPrefix(lower, "regexp:"):
			r.Regex = append(r.Regex, item[7:])
		case strings.HasPrefix(lower, "keyword:"):
			r.Keyword = append(r.Keyword, strings.ToLower(item[8:]))
		default:
			r.Suffix = append(r.Suffix, lower)
		}
	}
	return r
}

func ParseIPRules(lines []string) IPRule {
	var r IPRule
	for _, raw := range lines {
		item := strings.TrimSpace(raw)
		if item == "" || strings.HasPrefix(item, "#") {
			continue
		}
		if strings.HasPrefix(strings.ToLower(item), "geoip:") {
			r.GeoIP = append(r.GeoIP, strings.ToLower(item[6:]))
		} else {
			r.CIDR = append(r.CIDR, item)
		}
	}
	return r
}

// --- JSON helpers --------------------------------------------------------

// Obj is a loosely typed JSON object; renderers build config with it so
// that user overrides can be merged without a full schema.
type Obj = map[string]any

// Merge applies src over dst recursively. A key written "+name" prepends
// its array to dst["name"], "name+" appends; everything else replaces.
func Merge(dst, src Obj) {
	for key, sv := range src {
		if sa, ok := sv.([]any); ok {
			if strings.HasPrefix(key, "+") {
				k := key[1:]
				da, _ := dst[k].([]any)
				dst[k] = append(append([]any{}, sa...), da...)
				continue
			}
			if strings.HasSuffix(key, "+") {
				k := key[:len(key)-1]
				da, _ := dst[k].([]any)
				dst[k] = append(append([]any{}, da...), sa...)
				continue
			}
		}
		if so, ok := sv.(Obj); ok {
			if do, ok := dst[key].(Obj); ok {
				Merge(do, so)
				continue
			}
		}
		dst[key] = sv
	}
}

// MergeRaw merges a raw JSON object into dst; empty raw is a no-op.
func MergeRaw(dst Obj, raw json.RawMessage) error {
	if len(raw) == 0 {
		return nil
	}
	var src Obj
	if err := json.Unmarshal(raw, &src); err != nil {
		return fmt.Errorf("render: custom json: %w", err)
	}
	Merge(dst, src)
	return nil
}

// RawArray decodes a JSON array, tolerating empty input.
func RawArray(raw json.RawMessage) ([]any, error) {
	if len(raw) == 0 {
		return nil, nil
	}
	var arr []any
	if err := json.Unmarshal(raw, &arr); err != nil {
		return nil, fmt.Errorf("render: custom json array: %w", err)
	}
	return arr, nil
}

// Strings converts a []string to []any for embedding in Obj.
func Strings(in []string) []any {
	out := make([]any, len(in))
	for i, s := range in {
		out[i] = s
	}
	return out
}

// SplitComma splits a comma list, dropping blanks.
func SplitComma(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
