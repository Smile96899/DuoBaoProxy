// Package xray embeds upstream xray-core as a Core.
package xray

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"sync"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"

	// Protocols, transports and apps; keep in sync with upstream main/distro/all
	// minus the CLI command packages.
	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/dns"
	_ "github.com/xtls/xray-core/app/dns/fakedns"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/metrics"
	_ "github.com/xtls/xray-core/app/observatory"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	_ "github.com/xtls/xray-core/app/reverse"
	_ "github.com/xtls/xray-core/app/router"
	_ "github.com/xtls/xray-core/app/stats"
	_ "github.com/xtls/xray-core/main/json"
	_ "github.com/xtls/xray-core/proxy/blackhole"
	_ "github.com/xtls/xray-core/proxy/dns"
	_ "github.com/xtls/xray-core/proxy/dokodemo"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/http"
	_ "github.com/xtls/xray-core/proxy/loopback"
	_ "github.com/xtls/xray-core/proxy/shadowsocks"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/proxy/trojan"
	_ "github.com/xtls/xray-core/proxy/vless/inbound"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/proxy/vmess/inbound"
	_ "github.com/xtls/xray-core/proxy/vmess/outbound"
	_ "github.com/xtls/xray-core/proxy/wireguard"
	_ "github.com/xtls/xray-core/transport/internet/grpc"
	_ "github.com/xtls/xray-core/transport/internet/headers/http"
	_ "github.com/xtls/xray-core/transport/internet/headers/noop"
	_ "github.com/xtls/xray-core/transport/internet/httpupgrade"
	_ "github.com/xtls/xray-core/transport/internet/kcp"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/splithttp"
	_ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/udp"
	_ "github.com/xtls/xray-core/transport/internet/websocket"

	hcore "hproxy/core"
)

type Core struct {
	mu       sync.Mutex
	instance *core.Instance
}

func New() *Core { return &Core{} }

func (c *Core) ID() string       { return hcore.IDXray }
func (c *Core) Kind() hcore.Kind { return hcore.KindProxy }
func (c *Core) Version() string  { return core.Version() }

func (c *Core) Protocols() []string {
	return []string{"socks", "http", "shadowsocks", "vmess", "vless", "trojan", "wireguard"}
}

// withStats injects the "stats" app and outbound traffic policy so that
// per-outbound counters exist. Xray has no per-tag allow list; counters are
// created for every outbound.
func withStats(cfg []byte) ([]byte, error) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(cfg, &m); err != nil {
		return nil, err
	}
	if _, ok := m["stats"]; !ok {
		m["stats"] = json.RawMessage(`{}`)
	}
	var policy map[string]json.RawMessage
	if raw, ok := m["policy"]; ok && len(raw) > 0 {
		if err := json.Unmarshal(raw, &policy); err != nil {
			return nil, err
		}
	}
	if policy == nil {
		policy = map[string]json.RawMessage{}
	}
	var system map[string]any
	if raw, ok := policy["system"]; ok && len(raw) > 0 {
		if err := json.Unmarshal(raw, &system); err != nil {
			return nil, err
		}
	}
	if system == nil {
		system = map[string]any{}
	}
	system["statsOutboundUplink"] = true
	system["statsOutboundDownlink"] = true
	sysRaw, _ := json.Marshal(system)
	policy["system"] = sysRaw
	policyRaw, _ := json.Marshal(policy)
	m["policy"] = policyRaw
	return json.Marshal(m)
}

func create(cfg []byte, enableStats bool) (*core.Instance, error) {
	var err error
	if enableStats {
		cfg, err = withStats(cfg)
		if err != nil {
			return nil, err
		}
	}
	config, err := core.LoadConfig("json", bytes.NewReader(cfg))
	if err != nil {
		return nil, err
	}
	instance, err := core.New(config)
	if err != nil {
		return nil, err
	}
	if err = instance.Start(); err != nil {
		_ = instance.Close()
		return nil, err
	}
	return instance, nil
}

func (c *Core) Start(ctx context.Context, opts hcore.StartOptions) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.instance != nil {
		return hcore.ErrAlreadyRunning
	}
	if opts.AssetDir != "" {
		_ = os.Setenv("XRAY_LOCATION_ASSET", opts.AssetDir)
	}
	instance, err := create(opts.Config, len(opts.StatsOutbounds) > 0)
	if err != nil {
		return err
	}
	c.instance = instance
	return nil
}

func (c *Core) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.instance == nil {
		return nil
	}
	err := c.instance.Close()
	c.instance = nil
	return err
}

func (c *Core) Running() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.instance != nil
}

type dialer struct{ inst *core.Instance }

func (d dialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	dest, err := xnet.ParseDestination(network + ":" + addr)
	if err != nil {
		return nil, err
	}
	return core.Dial(ctx, d.inst, dest)
}

func (d dialer) ListenPacket(ctx context.Context) (net.PacketConn, error) {
	return core.DialUDP(ctx, d.inst)
}

func (c *Core) Dialer(ctx context.Context, cfg []byte) (hcore.Dialer, func(), error) {
	if cfg != nil {
		instance, err := create(cfg, false)
		if err != nil {
			return nil, nil, err
		}
		return dialer{instance}, func() { _ = instance.Close() }, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.instance == nil {
		return nil, nil, hcore.ErrNotRunning
	}
	return dialer{c.instance}, func() {}, nil
}

func (c *Core) Stats(tag string) (hcore.Traffic, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.instance == nil {
		return hcore.Traffic{}, hcore.ErrNotRunning
	}
	m, ok := c.instance.GetFeature(stats.ManagerType()).(stats.Manager)
	if !ok || m == nil {
		return hcore.Traffic{}, errors.New("stats not enabled")
	}
	var t hcore.Traffic
	if ctr := m.GetCounter("outbound>>>" + tag + ">>>traffic>>>uplink"); ctr != nil {
		t.Uplink = ctr.Value()
	}
	if ctr := m.GetCounter("outbound>>>" + tag + ">>>traffic>>>downlink"); ctr != nil {
		t.Downlink = ctr.Value()
	}
	return t, nil
}
