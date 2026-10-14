// Package ikev2 drives the operating system's native IKEv2/IPsec client.
// Nothing is embedded: the core writes a connection profile into the OS VPN
// store and dials it. Because this is a system VPN there are no inbounds,
// no routing rules and no per-outbound stats.
package ikev2

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"sync"
	"time"

	"hproxy/core"
)

// AuthMethod selects how the client authenticates to the gateway.
type AuthMethod string

const (
	AuthEAP  AuthMethod = "eap"  // EAP-MSCHAPv2 username/password
	AuthCert AuthMethod = "cert" // client certificate
	AuthPSK  AuthMethod = "psk"  // pre-shared key (not available on Windows for IKEv2)
)

// Profile is the engine-native config for this core, passed as JSON in
// StartOptions.Config.
type Profile struct {
	// Name of the VPN connection as shown by the OS. Defaults to "h.proxy".
	Name string `json:"name"`
	// Server hostname or IP of the IKEv2 gateway.
	Server string `json:"server"`
	// RemoteID expected from the gateway (IKE identity). Defaults to Server.
	RemoteID string `json:"remote_id,omitempty"`
	// LocalID sent to the gateway. Optional.
	LocalID string `json:"local_id,omitempty"`

	Auth     AuthMethod `json:"auth"`
	Username string     `json:"username,omitempty"`
	Password string     `json:"password,omitempty"`
	PSK      string     `json:"psk,omitempty"`

	// CACertPEM is the CA that signed the gateway certificate. Optional when
	// the gateway uses a publicly trusted certificate.
	CACertPEM string `json:"ca_cert_pem,omitempty"`
	// ClientCertP12 is a PKCS#12 bundle (base64) for AuthCert.
	ClientCertP12  string `json:"client_cert_p12,omitempty"`
	ClientCertPass string `json:"client_cert_pass,omitempty"`

	// SplitTunnel keeps the default route on the physical interface and only
	// sends Routes through the tunnel.
	SplitTunnel bool     `json:"split_tunnel,omitempty"`
	Routes      []string `json:"routes,omitempty"`
}

func (p *Profile) normalize() error {
	if p.Server == "" {
		return errors.New("ikev2: server is required")
	}
	if p.Name == "" {
		p.Name = "h.proxy"
	}
	if p.RemoteID == "" {
		p.RemoteID = p.Server
	}
	switch p.Auth {
	case AuthEAP:
		if p.Username == "" {
			return errors.New("ikev2: username is required for EAP")
		}
	case AuthCert:
		if p.ClientCertP12 == "" {
			return errors.New("ikev2: client certificate is required")
		}
	case AuthPSK:
		if p.PSK == "" {
			return errors.New("ikev2: psk is required")
		}
	case "":
		p.Auth = AuthEAP
	default:
		return fmt.Errorf("ikev2: unknown auth method %q", p.Auth)
	}
	return nil
}

// driver is implemented once per operating system.
type driver interface {
	connect(ctx context.Context, p *Profile, logf func(string)) error
	disconnect(ctx context.Context, p *Profile) error
	connected(ctx context.Context, p *Profile) (bool, error)
}

type Core struct {
	mu      sync.Mutex
	profile *Profile
	drv     driver
}

func New() *Core { return &Core{drv: newDriver()} }

func (c *Core) ID() string      { return core.IDIKEv2 }
func (c *Core) Kind() core.Kind { return core.KindSystemVPN }
func (c *Core) Version() string { return "system" }

func (c *Core) Protocols() []string { return []string{"ikev2"} }

func (c *Core) Start(ctx context.Context, opts core.StartOptions) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.profile != nil {
		return core.ErrAlreadyRunning
	}
	var p Profile
	if err := json.Unmarshal(opts.Config, &p); err != nil {
		return fmt.Errorf("ikev2: parse profile: %w", err)
	}
	if err := p.normalize(); err != nil {
		return err
	}
	logf := opts.LogWriter
	if logf == nil {
		logf = func(string) {}
	}
	if err := c.drv.connect(ctx, &p, logf); err != nil {
		return err
	}
	c.profile = &p
	return nil
}

func (c *Core) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.profile == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := c.drv.disconnect(ctx, c.profile)
	c.profile = nil
	return err
}

func (c *Core) Running() bool {
	c.mu.Lock()
	p := c.profile
	c.mu.Unlock()
	if p == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ok, err := c.drv.connected(ctx, p)
	return err == nil && ok
}

// Dialer: a system VPN has no in-process tunnel; traffic is already routed
// by the OS, so probes use the system dialer. A cfg-based throw-away
// instance is not possible and returns ErrNotRunning.
func (c *Core) Dialer(ctx context.Context, cfg []byte) (core.Dialer, func(), error) {
	if cfg != nil {
		return nil, nil, errors.New("ikev2: test instances are not supported, connect first")
	}
	if !c.Running() {
		return nil, nil, core.ErrNotRunning
	}
	return core.SystemDialer{}, func() {}, nil
}

func (c *Core) Stats(string) (core.Traffic, error) {
	return core.Traffic{}, errors.New("ikev2: per-outbound stats are not available")
}

// run executes a command and returns combined output; used by all drivers.
func run(ctx context.Context, logf func(string), name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	s := strings.TrimSpace(string(out))
	if logf != nil && s != "" {
		logf(name + ": " + s)
	}
	if err != nil {
		if s != "" {
			return s, fmt.Errorf("%s: %w: %s", name, err, s)
		}
		return s, fmt.Errorf("%s: %w", name, err)
	}
	return s, nil
}
