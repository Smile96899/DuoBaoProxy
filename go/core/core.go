// Package core defines the backend-independent interface that every proxy
// engine (sing-box, xray, IKEv2) implements, plus a registry the gRPC layer
// uses to look engines up by id.
package core

import (
	"context"
	"errors"
	"net"
	"sort"
	"sync"
)

// Core ids as exposed over gRPC.
const (
	IDSingBox = "sing-box"
	IDXray    = "xray"
	IDIKEv2   = "ikev2"
)

// Kind tells the GUI how a core behaves: a Proxy core exposes local
// inbounds and supports routing; a SystemVPN core replaces the system
// default route and has no routing/inbound concept.
type Kind int

const (
	KindProxy Kind = iota
	KindSystemVPN
)

// Dialer dials through a running instance. Cores that cannot dial through
// themselves (system VPN) return the system dialer.
type Dialer interface {
	DialContext(ctx context.Context, network, addr string) (net.Conn, error)
	ListenPacket(ctx context.Context) (net.PacketConn, error)
}

// Traffic counters in bytes for one outbound tag.
type Traffic struct {
	Uplink   int64
	Downlink int64
}

// StartOptions carries the engine-native config plus flags shared by cores.
type StartOptions struct {
	// Config is the engine-native configuration (JSON for sing-box / xray,
	// JSON-encoded ikev2.Profile for IKEv2).
	Config []byte
	// StatsOutbounds enables traffic counters for these outbound tags.
	StatsOutbounds []string
	// LogWriter receives engine log lines. May be nil.
	LogWriter func(line string)
	// AssetDir holds geo databases for cores that read them from disk
	// (xray: geoip.dat / geosite.dat). Empty keeps the core default.
	AssetDir string
}

type Core interface {
	ID() string
	Kind() Kind
	Version() string
	// Protocols this core can carry, as protocol ids understood by the GUI
	// ("vmess", "vless", "trojan", "shadowsocks", "hysteria2", "tuic",
	// "wireguard", "socks", "http", "ssh", "ikev2" ...).
	Protocols() []string

	Start(ctx context.Context, opts StartOptions) error
	Stop() error
	Running() bool

	// Dialer returns a dialer bound to the running instance, or a dialer
	// bound to a throw-away instance built from cfg when cfg is non-nil.
	// The returned release func must be called when done.
	Dialer(ctx context.Context, cfg []byte) (d Dialer, release func(), err error)

	Stats(tag string) (Traffic, error)
}

var (
	ErrAlreadyRunning = errors.New("core already running")
	ErrNotRunning     = errors.New("core not running")
	ErrUnknownCore    = errors.New("unknown core")
)

var (
	mu       sync.RWMutex
	registry = map[string]Core{}
)

func Register(c Core) {
	mu.Lock()
	defer mu.Unlock()
	registry[c.ID()] = c
}

func Get(id string) (Core, error) {
	mu.RLock()
	defer mu.RUnlock()
	c, ok := registry[id]
	if !ok {
		return nil, ErrUnknownCore
	}
	return c, nil
}

func List() []Core {
	mu.RLock()
	defer mu.RUnlock()
	out := make([]Core, 0, len(registry))
	for _, c := range registry {
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID() < out[j].ID() })
	return out
}

// SystemDialer dials without any proxy.
type SystemDialer struct{}

func (SystemDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}

func (SystemDialer) ListenPacket(ctx context.Context) (net.PacketConn, error) {
	var lc net.ListenConfig
	return lc.ListenPacket(ctx, "udp", "")
}
