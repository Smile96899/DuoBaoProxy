package mobile

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"

	"duobaoproxy/api"
	"duobaoproxy/core"
	"duobaoproxy/render"
)

// Proxy modes and route parts as stored in data.json ui.proxy_mode and
// ui.route_parts.
const (
	proxyGlobal = "global"
	proxyDirect = "direct"
	partsRules  = "rules"
	partsApps   = "apps"
)

// decodeObject parses a JSON object keeping numbers verbatim so that fields
// this package does not know survive the round trip unchanged ("" is {}).
func decodeObject(s string) (map[string]any, error) {
	m := map[string]any{}
	if strings.TrimSpace(s) == "" {
		return m, nil
	}
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	if m == nil { // "null"
		m = map[string]any{}
	}
	return m, nil
}

func encodeObject(m map[string]any) (string, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(m); err != nil {
		return "", err
	}
	return strings.TrimSuffix(buf.String(), "\n"), nil
}

// child returns m[key] as an object, creating it (like `s.x = s.x || {}`)
// when it is missing or not an object.
func child(m map[string]any, key string) map[string]any {
	if c, ok := m[key].(map[string]any); ok {
		return c
	}
	c := map[string]any{}
	m[key] = c
	return c
}

// PrepareSettings turns the stored settings object (data.json "settings")
// into what RenderConfig expects, exactly as the desktop's
// App.renderSettings() does (app/qml/App.qml:72-91):
//
//   - proxyMode ("" means "global"): global clears the proxy/direct domain
//     and IP lists and custom_rules and sets final=proxy; direct sets
//     final=bypass; rule leaves the rules alone.
//   - routeParts ("" means "both"): rules drops apps; apps drops every
//     routing list (proxy/direct/block domain and IP) and custom_rules.
//   - tun.enabled = tun.
//
// It also injects platform and own_package from Setup. Unknown fields are
// kept.
func PrepareSettings(settingsJSON, proxyMode, routeParts string, tun bool) (string, error) {
	s, err := decodeObject(settingsJSON)
	if err != nil {
		return "", errors.New("settings: " + err.Error())
	}
	routing := child(s, "routing")
	tunObj := child(s, "tun")
	empty := func(keys ...string) {
		for _, k := range keys {
			routing[k] = []any{}
		}
	}
	if proxyMode == "" {
		proxyMode = proxyGlobal
	}
	switch proxyMode {
	case proxyGlobal:
		empty("proxy_domain", "direct_domain", "proxy_ip", "direct_ip", "custom_rules")
		routing["final"] = "proxy"
	case proxyDirect:
		routing["final"] = "bypass"
	}
	if routeParts == "" {
		routeParts = "both"
	}
	switch routeParts {
	case partsRules:
		s["apps"] = []any{}
	case partsApps:
		empty("proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip", "custom_rules")
	}
	tunObj["enabled"] = tun
	injectPlatform(s, currentPaths())
	return encodeObject(s)
}

// injectPlatform sets platform / own_package from Setup unless the settings
// already carry them.
func injectPlatform(s map[string]any, p paths) {
	if p.platform == "" {
		return
	}
	if v, _ := s["platform"].(string); v == "" {
		s["platform"] = p.platform
	}
	if v, _ := s["own_package"].(string); v == "" && p.ownPackage != "" {
		s["own_package"] = p.ownPackage
	}
}

// RenderResult is a rendered engine config.
type RenderResult struct {
	// Config is the engine-native JSON config.
	Config string
	// StatsTagsJSON is a JSON array of the outbound tags to count, exit
	// ("proxy") first; pass it on to StartOptions.
	StatsTagsJSON string
	// FrontConfig is the sing-box TUN front for xray with TUN on (start it
	// with StartOptions.FrontConfig), "" otherwise.
	FrontConfig string
}

// RenderConfig renders chainJSON (a JSON array of node objects, client
// outwards; the last is the exit) with settingsJSON (normally the output of
// PrepareSettings) for coreID ("" means sing-box). mode: 0 Full (Start),
// 1 Test (throw-away test instances), 2 Export.
//
// For xray with TUN on mobile (Full mode) it picks a free loopback port and
// random credentials for the bridge between the sing-box front and xray.
func RenderConfig(coreID, chainJSON, settingsJSON string, mode int32) (*RenderResult, error) {
	var chain []json.RawMessage
	if strings.TrimSpace(chainJSON) != "" {
		if err := json.Unmarshal([]byte(chainJSON), &chain); err != nil {
			return nil, errors.New("chain: " + err.Error())
		}
	}
	nodes := make([]string, len(chain))
	for i, n := range chain {
		nodes[i] = string(n)
	}
	s, err := decodeObject(settingsJSON)
	if err != nil {
		return nil, errors.New("settings: " + err.Error())
	}
	injectPlatform(s, currentPaths())
	if render.Mode(mode) == render.ModeFull && api.CoreID(coreID) == core.IDXray && needsBridge(s) {
		b, err := newBridge()
		if err != nil {
			return nil, err
		}
		s["bridge"] = b
	}
	settings, err := encodeObject(s)
	if err != nil {
		return nil, err
	}
	r, err := api.RenderConfig(coreID, nodes, settings, render.Mode(mode))
	if err != nil {
		return nil, err
	}
	tags, _ := json.Marshal(nonNil(r.StatsTags))
	return &RenderResult{Config: r.Config, StatsTagsJSON: string(tags), FrontConfig: r.FrontConfig}, nil
}

// needsBridge reports whether the settings select the mobile xray TUN
// front: a platform and tun.enabled.
func needsBridge(s map[string]any) bool {
	if p, _ := s["platform"].(string); p == "" {
		return false
	}
	t, _ := s["tun"].(map[string]any)
	on, _ := t["enabled"].(bool)
	return on
}

// newBridge picks a loopback port that is free for both TCP and UDP (xray's
// mixed inbound uses both) plus random credentials.
func newBridge() (*render.Bridge, error) {
	var lastErr error
	for range 10 {
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			lastErr = err
			continue
		}
		port := l.Addr().(*net.TCPAddr).Port
		pc, err := net.ListenPacket("udp", fmt.Sprintf("127.0.0.1:%d", port))
		_ = l.Close()
		if err != nil {
			lastErr = err
			continue
		}
		_ = pc.Close()
		return &render.Bridge{Port: port, User: randomToken(), Pass: randomToken()}, nil
	}
	return nil, fmt.Errorf("bridge: no free loopback port: %w", lastErr)
}

func randomToken() string {
	var b [12]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}
