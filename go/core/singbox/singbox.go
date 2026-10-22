// Package singbox embeds upstream sing-box as a Core.
package singbox

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"

	"duobaoproxy/core"
)

type Core struct {
	mu   sync.Mutex
	inst *instance
}

func New() *Core { return &Core{} }

func (c *Core) ID() string      { return core.IDSingBox }
func (c *Core) Version() string { return constant.Version }

func (c *Core) Protocols() []string {
	return []string{"socks", "http", "shadowsocks", "vmess", "vless", "trojan", "hysteria2", "tuic", "ssh", "anytls", "wireguard", "hysteria", "shadowtls"}
}

type logWriter struct{ fn func(string) }

func (w logWriter) WriteMessage(level log.Level, message string) {
	if w.fn != nil {
		w.fn(message)
	}
}

type instance struct {
	box    *box.Box
	cancel context.CancelFunc
	stats  *v2rayapi.StatsService // nil when stats are disabled
}

func (i *instance) close() {
	i.cancel()
	_ = i.box.Close()
}

func create(parent context.Context, cfg []byte, statsOutbounds []string, logFn func(string)) (*instance, error) {
	ctx := include.Context(parent)
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, cfg)
	if err != nil {
		return nil, err
	}
	if options.Log == nil {
		options.Log = &option.LogOptions{}
	}
	options.Log.DisableColor = true
	boxOptions := box.Options{Options: options}
	if logFn != nil {
		// A platform log writer makes sing-box require a cache file; keep it
		// out of the working directory and away from throw-away instances
		// (which would block on the bbolt lock).
		boxOptions.PlatformLogWriter = logWriter{logFn}
		// logFn already timestamps, files and echoes each line; sing-box's own
		// console copy would show every line twice in the GUI.
		if options.Log.Output == "" {
			options.Log.Output = os.DevNull
		}
		if options.Experimental == nil {
			options.Experimental = &option.ExperimentalOptions{}
		}
		if options.Experimental.CacheFile == nil {
			options.Experimental.CacheFile = &option.CacheFileOptions{}
		}
		if options.Experimental.CacheFile.Path == "" {
			options.Experimental.CacheFile.Path = filepath.Join(os.TempDir(), "duobaoproxy-singbox-cache.db")
		}
		boxOptions.Options = options
	}
	ctx, cancel := context.WithCancel(ctx)
	ctx = service.ContextWithDefaultRegistry(ctx)
	boxOptions.Context = ctx
	b, err := box.New(boxOptions)
	if err != nil {
		cancel()
		return nil, err
	}
	inst := &instance{box: b, cancel: cancel}
	if len(statsOutbounds) > 0 {
		// Attach the upstream stats tracker directly instead of enabling
		// experimental.v2ray_api, which would also open a gRPC listener.
		inst.stats = v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: statsOutbounds})
		b.Router().AppendTracker(inst.stats)
	}
	if err = b.Start(); err != nil {
		inst.close()
		return nil, err
	}
	return inst, nil
}

func (c *Core) Start(ctx context.Context, opts core.StartOptions) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inst != nil {
		return core.ErrAlreadyRunning
	}
	inst, err := create(ctx, opts.Config, opts.StatsOutbounds, opts.LogWriter)
	if err != nil {
		return err
	}
	c.inst = inst
	return nil
}

func (c *Core) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inst == nil {
		return nil
	}
	c.inst.cancel()
	err := c.inst.box.Close()
	c.inst = nil
	return err
}

func (c *Core) Running() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.inst != nil
}

type dialer struct{ out adapter.Outbound }

func (d dialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	return d.out.DialContext(ctx, network, M.ParseSocksaddr(addr))
}

func (d dialer) ListenPacket(ctx context.Context) (net.PacketConn, error) {
	return d.out.ListenPacket(ctx, M.Socksaddr{})
}

func (c *Core) Dialer(ctx context.Context, cfg []byte) (core.Dialer, func(), error) {
	if cfg != nil {
		inst, err := create(ctx, cfg, nil, nil)
		if err != nil {
			return nil, nil, err
		}
		return dialer{inst.box.Outbound().Default()}, inst.close, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inst == nil {
		return nil, nil, core.ErrNotRunning
	}
	return dialer{c.inst.box.Outbound().Default()}, func() {}, nil
}

func (c *Core) Stats(tag string) (core.Traffic, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inst == nil {
		return core.Traffic{}, core.ErrNotRunning
	}
	if c.inst.stats == nil {
		return core.Traffic{}, errors.New("stats not enabled")
	}
	ctx := context.Background()
	var t core.Traffic
	if r, err := c.inst.stats.GetStats(ctx, &v2rayapi.GetStatsRequest{Name: "outbound>>>" + tag + ">>>traffic>>>uplink"}); err == nil && r.Stat != nil {
		t.Uplink = r.Stat.Value
	}
	if r, err := c.inst.stats.GetStats(ctx, &v2rayapi.GetStatsRequest{Name: "outbound>>>" + tag + ">>>traffic>>>downlink"}); err == nil && r.Stat != nil {
		t.Downlink = r.Stat.Value
	}
	return t, nil
}
