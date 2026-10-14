package node

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// ErrUnknownLink is returned for schemes we do not understand.
var ErrUnknownLink = errors.New("node: unsupported link")

// ParseLink turns one share link into a Node.
func ParseLink(link string) (*Node, error) {
	link = strings.TrimSpace(link)
	scheme := strings.ToLower(strings.SplitN(link, "://", 2)[0])
	switch scheme {
	case "socks", "socks4", "socks4a", "socks5", "http", "https":
		return parseSocksHTTP(link)
	case "ss":
		return parseShadowsocks(link)
	case "vmess":
		return parseVMess(link)
	case "vless":
		return parseTrojanVLESS(link, VLESS)
	case "trojan":
		return parseTrojanVLESS(link, Trojan)
	case "naive+https", "naive+quic":
		return parseNaive(link)
	case "hysteria2", "hy2":
		return parseHysteria2(link)
	case "tuic":
		return parseTUIC(link)
	case "hproxy":
		return parseHProxy(link)
	}
	return nil, ErrUnknownLink
}

// --- helpers -------------------------------------------------------------

func decodeB64(s string) (string, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", false
	}
	for _, enc := range []*base64.Encoding{base64.RawURLEncoding, base64.RawStdEncoding} {
		if b, err := enc.DecodeString(strings.TrimRight(s, "=")); err == nil {
			return string(b), true
		}
	}
	return "", false
}

func fragment(u *url.URL) string {
	if u.Fragment != "" {
		return u.Fragment
	}
	f, _ := url.PathUnescape(u.EscapedFragment())
	return f
}

func portOr(u *url.URL, def int) int {
	if p := u.Port(); p != "" {
		n, _ := strconv.Atoi(p)
		return n
	}
	return def
}

func userinfo(u *url.URL) (string, string) {
	if u.User == nil {
		return "", ""
	}
	p, _ := u.User.Password()
	return u.User.Username(), p
}

func qget(q url.Values, key, def string) string {
	if v := q.Get(key); v != "" {
		return v
	}
	return def
}

// applyV2RayQuery fills Stream from the common vmess/vless/trojan query
// parameters defined by the XTLS share-link standard.
func applyV2RayQuery(s *Stream, q url.Values, defaultSecurity string) {
	network := qget(q, "type", "tcp")
	if network == "h2" {
		network = "http"
	}
	s.Network = network
	sec := qget(q, "security", defaultSecurity)
	sec = strings.ReplaceAll(sec, "reality", "tls")
	sec = strings.ReplaceAll(sec, "none", "")
	s.Security = sec
	if v := q.Get("sni"); v != "" {
		s.SNI = v
	}
	if v := q.Get("peer"); v != "" {
		s.SNI = v
	}
	s.ALPN = q.Get("alpn")
	if q.Get("allowInsecure") != "" {
		s.AllowInsecure = true
	}
	s.RealityPublicKey = q.Get("pbk")
	s.RealityShortID = q.Get("sid")
	s.RealitySpiderX = q.Get("spx")
	s.Fingerprint = q.Get("fp")
	switch s.Network {
	case "ws", "httpupgrade", "xhttp":
		s.Path = q.Get("path")
		s.Host = q.Get("host")
	case "http":
		s.Path = q.Get("path")
		s.Host = strings.ReplaceAll(q.Get("host"), "|", ",")
	case "grpc":
		s.Path = q.Get("serviceName")
	case "tcp":
		if q.Get("headerType") == "http" {
			s.HeaderType = "http"
			s.Host = q.Get("host")
			s.Path = q.Get("path")
		}
	}
}

// --- parsers -------------------------------------------------------------

func parseSocksHTTP(link string) (*Node, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	n := &Node{Protocol: Socks, SocksVersion: 5, Name: fragment(u), Server: u.Hostname()}
	scheme := strings.ToLower(u.Scheme)
	if strings.HasPrefix(scheme, "socks4") {
		n.SocksVersion = 4
	}
	if strings.HasPrefix(scheme, "http") {
		n.Protocol = HTTP
		n.SocksVersion = 0
	}
	def := 1080
	if n.Protocol == HTTP {
		def = 443
	}
	n.Port = portOr(u, def)
	n.Username, n.Password = userinfo(u)
	// v2rayN packs "user:pass" base64 into the username.
	if n.Password == "" && n.Username != "" {
		if d, ok := decodeB64(n.Username); ok && strings.Contains(d, ":") {
			n.Username, n.Password, _ = strings.Cut(d, ":")
		}
	}
	q := u.Query()
	s := n.stream()
	s.Security = q.Get("security")
	s.SNI = q.Get("sni")
	if scheme == "https" {
		s.Security = "tls"
	}
	if n.Server == "" {
		return nil, errors.New("node: empty host")
	}
	return n, nil
}

func parseTrojanVLESS(link, protocol string) (*Node, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	n := &Node{Protocol: protocol, Name: fragment(u), Server: u.Hostname(), Port: portOr(u, 443)}
	user, _ := userinfo(u)
	q := u.Query()
	s := n.stream()
	if protocol == Trojan {
		n.Password = user
		applyV2RayQuery(s, q, "tls")
	} else {
		n.UUID = user
		applyV2RayQuery(s, q, "")
		n.Flow = q.Get("flow")
	}
	if user == "" || n.Server == "" {
		return nil, errors.New("node: missing credentials or host")
	}
	return n, nil
}

func parseShadowsocks(link string) (*Node, error) {
	n := &Node{Protocol: Shadowsocks}
	body, frag, _ := strings.Cut(link, "#")
	if strings.Contains(body, "@") {
		u, err := url.Parse(body)
		if err != nil {
			return nil, err
		}
		n.Server, n.Port = u.Hostname(), portOr(u, 0)
		user, pass := userinfo(u)
		if pass == "" {
			// traditional: base64(method:password)@host
			d, ok := decodeB64(user)
			if !ok {
				return nil, errors.New("node: bad ss userinfo")
			}
			n.Method, n.Password, _ = strings.Cut(d, ":")
		} else {
			n.Method, n.Password = user, pass
		}
		n.Plugin = strings.ReplaceAll(u.Query().Get("plugin"), "simple-obfs;", "obfs-local;")
	} else {
		// v2rayN: ss://base64(method:password@host:port)
		d, ok := decodeB64(strings.TrimPrefix(body, "ss://"))
		if !ok {
			return nil, errors.New("node: bad ss link")
		}
		u, err := url.Parse("ss://" + d)
		if err != nil {
			return nil, err
		}
		n.Server, n.Port = u.Hostname(), portOr(u, 0)
		n.Method, n.Password = userinfo(u)
	}
	if frag != "" {
		n.Name, _ = url.PathUnescape(frag)
	}
	if n.Server == "" || n.Method == "" || n.Password == "" {
		return nil, errors.New("node: incomplete ss link")
	}
	return n, nil
}

func parseVMess(link string) (*Node, error) {
	n := &Node{Protocol: VMess, VMessSecurity: "auto"}
	if d, ok := decodeB64(strings.TrimPrefix(link, "vmess://")); ok && strings.HasPrefix(strings.TrimSpace(d), "{") {
		// v2rayN JSON
		var m map[string]any
		if err := json.Unmarshal([]byte(d), &m); err != nil {
			return nil, err
		}
		str := func(k string) string {
			switch v := m[k].(type) {
			case string:
				return v
			case float64:
				return strconv.Itoa(int(v))
			}
			return ""
		}
		n.UUID = str("id")
		n.Server = str("add")
		n.Port, _ = strconv.Atoi(str("port"))
		n.Name = str("ps")
		n.AlterID, _ = strconv.Atoi(str("aid"))
		s := n.stream()
		s.Host = str("host")
		s.Path = str("path")
		s.SNI = str("sni")
		s.HeaderType = str("type")
		if net := str("net"); net != "" {
			if net == "h2" {
				net = "http"
			}
			s.Network = net
		}
		if scy := str("scy"); scy != "" {
			n.VMessSecurity = scy
		}
		s.Security = str("tls")
		s.Fingerprint = str("fp")
		s.ALPN = str("alpn")
		if n.UUID == "" || n.Server == "" {
			return nil, errors.New("node: incomplete vmess link")
		}
		return n, nil
	}
	// XTLS standard URL form
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	n.Name, n.Server, n.Port = fragment(u), u.Hostname(), portOr(u, 443)
	n.UUID, _ = userinfo(u)
	q := u.Query()
	n.VMessSecurity = qget(q, "encryption", "auto")
	applyV2RayQuery(n.stream(), q, "tls")
	if n.UUID == "" || n.Server == "" {
		return nil, errors.New("node: incomplete vmess link")
	}
	return n, nil
}

func parseNaive(link string) (*Node, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	n := &Node{Protocol: Naive, Name: fragment(u), Server: u.Hostname(), Port: portOr(u, 443)}
	n.Username, n.Password = userinfo(u)
	n.Naive = &NaiveOptions{Protocol: strings.TrimPrefix(strings.ToLower(u.Scheme), "naive+")}
	if n.Username == "" || n.Password == "" || n.Server == "" {
		return nil, errors.New("node: incomplete naive link")
	}
	return n, nil
}

func parseHysteria2(link string) (*Node, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	if u.Hostname() == "" || u.Port() == "" {
		return nil, errors.New("node: hysteria2 needs host and port")
	}
	q := u.Query()
	n := &Node{Protocol: Hysteria2, Name: fragment(u), Server: u.Hostname(), Port: portOr(u, 443)}
	user, pass := userinfo(u)
	n.Password = user
	if pass != "" {
		n.Password = user + ":" + pass
	}
	n.Hysteria2 = &Hysteria2Options{HopPorts: q.Get("mport"), ObfsPassword: q.Get("obfs-password"), HopInterval: 10}
	n.QUICTLS = &QUICTLS{SNI: q.Get("sni"), AllowInsecure: q.Get("insecure") == "1" || q.Get("insecure") == "true"}
	return n, nil
}

func parseTUIC(link string) (*Node, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	if u.Hostname() == "" || u.Port() == "" {
		return nil, errors.New("node: tuic needs host and port")
	}
	q := u.Query()
	n := &Node{Protocol: TUIC, Name: fragment(u), Server: u.Hostname(), Port: portOr(u, 443)}
	n.UUID, n.Password = userinfo(u)
	n.TUIC = &TUICOptions{
		CongestionControl: qget(q, "congestion_control", "bbr"),
		UDPRelayMode:      qget(q, "udp_relay_mode", "native"),
		Heartbeat:         "10s",
	}
	n.QUICTLS = &QUICTLS{SNI: q.Get("sni"), ALPN: q.Get("alpn"), AllowInsecure: q.Get("allow_insecure") == "1", DisableSNI: q.Get("disable_sni") == "1"}
	return n, nil
}

// hproxy://<base64url(json Node)> is our own lossless format.
func parseHProxy(link string) (*Node, error) {
	d, ok := decodeB64(strings.TrimPrefix(link, "hproxy://"))
	if !ok {
		return nil, errors.New("node: bad hproxy link")
	}
	var n Node
	if err := json.Unmarshal([]byte(d), &n); err != nil {
		return nil, err
	}
	return &n, n.Validate()
}

// --- writers -------------------------------------------------------------

// ShareLink renders the node in the most common link format for its
// protocol. IKEv2 and nodes with custom config fall back to hproxy://.
func (n *Node) ShareLink() string {
	switch n.Protocol {
	case Socks, HTTP:
		return n.socksHTTPLink()
	case Shadowsocks:
		return n.shadowsocksLink()
	case VMess:
		return n.vmessLink()
	case VLESS, Trojan:
		return n.trojanVLESSLink()
	case Naive:
		return n.naiveLink()
	case Hysteria2:
		return n.hysteria2Link()
	case TUIC:
		return n.tuicLink()
	}
	return n.HProxyLink()
}

func (n *Node) HProxyLink() string {
	b, _ := json.Marshal(n)
	return "hproxy://" + base64.RawURLEncoding.EncodeToString(b)
}

func (n *Node) baseURL(scheme string) *url.URL {
	return &url.URL{Scheme: scheme, Host: n.DisplayAddressRaw(), Fragment: n.Name}
}

// DisplayAddressRaw is host:port without hop-port substitution.
func (n *Node) DisplayAddressRaw() string {
	return wrapIPv6(n.Server) + ":" + strconv.Itoa(n.Port)
}

func (n *Node) socksHTTPLink() string {
	scheme := "socks5"
	if n.Protocol == HTTP {
		scheme = "http"
		if n.Stream != nil && n.Stream.Security == "tls" {
			scheme = "https"
		}
	} else if n.SocksVersion == 4 {
		scheme = "socks4"
	}
	u := n.baseURL(scheme)
	if n.Username != "" {
		if n.Password != "" {
			u.User = url.UserPassword(n.Username, n.Password)
		} else {
			u.User = url.User(n.Username)
		}
	}
	return u.String()
}

func (n *Node) shadowsocksLink() string {
	u := n.baseURL("ss")
	if strings.HasPrefix(n.Method, "2022-") {
		u.User = url.UserPassword(n.Method, n.Password)
	} else {
		u.User = url.User(base64.RawURLEncoding.EncodeToString([]byte(n.Method + ":" + n.Password)))
	}
	if n.Plugin != "" {
		u.RawQuery = url.Values{"plugin": {n.Plugin}}.Encode()
	}
	return u.String()
}

func (n *Node) v2rayQuery() url.Values {
	s := n.stream()
	q := url.Values{}
	sec := s.Security
	if sec == "tls" && strings.TrimSpace(s.RealityPublicKey) != "" {
		sec = "reality"
	}
	if sec == "" {
		sec = "none"
	}
	q.Set("security", sec)
	if s.SNI != "" {
		q.Set("sni", s.SNI)
	}
	if s.ALPN != "" {
		q.Set("alpn", s.ALPN)
	}
	if s.AllowInsecure {
		q.Set("allowInsecure", "1")
	}
	if s.Fingerprint != "" {
		q.Set("fp", s.Fingerprint)
	}
	if sec == "reality" {
		q.Set("pbk", s.RealityPublicKey)
		if s.RealityShortID != "" {
			q.Set("sid", s.RealityShortID)
		}
		if s.RealitySpiderX != "" {
			q.Set("spx", s.RealitySpiderX)
		}
	}
	q.Set("type", s.Network)
	switch s.Network {
	case "ws", "http", "httpupgrade", "xhttp":
		if s.Path != "" {
			q.Set("path", s.Path)
		}
		if s.Host != "" {
			q.Set("host", s.Host)
		}
	case "grpc":
		if s.Path != "" {
			q.Set("serviceName", s.Path)
		}
	case "tcp":
		if s.HeaderType == "http" {
			q.Set("headerType", "http")
			q.Set("host", s.Host)
			if s.Path != "" {
				q.Set("path", s.Path)
			}
		}
	}
	return q
}

func (n *Node) trojanVLESSLink() string {
	u := n.baseURL(n.Protocol)
	q := n.v2rayQuery()
	if n.Protocol == VLESS {
		u.User = url.User(n.UUID)
		if n.Flow != "" {
			q.Set("flow", n.Flow)
		}
		q.Set("encryption", "none")
	} else {
		u.User = url.User(n.Password)
	}
	u.RawQuery = q.Encode()
	return u.String()
}

func (n *Node) vmessLink() string {
	u := n.baseURL("vmess")
	u.User = url.User(n.UUID)
	q := n.v2rayQuery()
	sec := n.VMessSecurity
	if sec == "" {
		sec = "auto"
	}
	q.Set("encryption", sec)
	u.RawQuery = q.Encode()
	return u.String()
}

// VMessLinkV2RayN renders the legacy base64 JSON form many apps still expect.
func (n *Node) VMessLinkV2RayN() string {
	s := n.stream()
	tls := ""
	if s.Security == "tls" {
		tls = "tls"
	}
	m := map[string]string{
		"v": "2", "ps": n.Name, "add": n.Server, "port": strconv.Itoa(n.Port), "id": n.UUID,
		"aid": strconv.Itoa(n.AlterID), "net": s.Network, "host": s.Host, "path": s.Path,
		"type": s.HeaderType, "scy": n.VMessSecurity, "tls": tls, "sni": s.SNI, "fp": s.Fingerprint, "alpn": s.ALPN,
	}
	b, _ := json.Marshal(m)
	return "vmess://" + base64.StdEncoding.EncodeToString(b)
}

func (n *Node) naiveLink() string {
	proto := "https"
	if n.Naive != nil && n.Naive.Protocol != "" {
		proto = n.Naive.Protocol
	}
	u := n.baseURL("naive+" + proto)
	u.User = url.UserPassword(n.Username, n.Password)
	return u.String()
}

func (n *Node) hysteria2Link() string {
	u := n.baseURL("hy2")
	if user, pass, ok := strings.Cut(n.Password, ":"); ok {
		u.User = url.UserPassword(user, pass)
	} else {
		u.User = url.User(n.Password)
	}
	q := url.Values{}
	if h := n.Hysteria2; h != nil {
		if h.ObfsPassword != "" {
			q.Set("obfs", "salamander")
			q.Set("obfs-password", h.ObfsPassword)
		}
		if strings.TrimSpace(h.HopPorts) != "" {
			q.Set("mport", h.HopPorts)
		}
	}
	if t := n.QUICTLS; t != nil {
		if t.AllowInsecure {
			q.Set("insecure", "1")
		}
		if t.SNI != "" {
			q.Set("sni", t.SNI)
		}
	}
	u.RawQuery = q.Encode()
	return u.String()
}

func (n *Node) tuicLink() string {
	u := n.baseURL("tuic")
	u.User = url.UserPassword(n.UUID, n.Password)
	q := url.Values{}
	if t := n.TUIC; t != nil {
		if t.CongestionControl != "" {
			q.Set("congestion_control", t.CongestionControl)
		}
		if t.UDPRelayMode != "" {
			q.Set("udp_relay_mode", t.UDPRelayMode)
		}
	}
	if t := n.QUICTLS; t != nil {
		if t.ALPN != "" {
			q.Set("alpn", t.ALPN)
		}
		if t.SNI != "" {
			q.Set("sni", t.SNI)
		}
		if t.AllowInsecure {
			q.Set("allow_insecure", "1")
		}
		if t.DisableSNI {
			q.Set("disable_sni", "1")
		}
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// String is a short human readable summary used in logs.
func (n *Node) String() string {
	return fmt.Sprintf("%s %s (%s)", n.Protocol, n.DisplayName(), n.DisplayAddress())
}
