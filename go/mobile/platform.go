package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"os"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// networkManager is the part of adapter.NetworkManager the platform uses;
// narrowed so tests can fake it.
type networkManager interface {
	UpdateInterfaces() error
	InterfaceFinder() control.InterfaceFinder
	NetworkInterfaces() []adapter.NetworkInterface
}

// platform implements sing-box's adapter.PlatformInterface on top of a Host
// (spec/singbox-mobile.md §1). One is created per engine instance. A nil
// host makes it a throw-away test platform: it still protects sockets on
// Android and keeps the local DNS transport, but cannot open a TUN.
type platform struct {
	host Host
	// includeAllNetworks mirrors NETunnelProviderProtocol.includeAllNetworks
	// on iOS (forces the gvisor stack in sing-tun).
	includeAllNetworks bool

	access             sync.Mutex
	nm                 networkManager
	logger             logger.Logger
	callbacks          list.List[tun.DefaultInterfaceUpdateCallback]
	myInterfaces       []string
	myTunName          string
	myTunAddress       []netip.Addr
	defaultIndex       int // last index reported by the host, -1 none
	defaultInterface   *control.Interface
	defaultDNS         []string
	defaultInitialized bool
	expensive          bool
	constrained        bool
	monitorClosed      bool
}

var (
	_ adapter.PlatformInterface = (*platform)(nil)
	_ log.PlatformWriter        = (*platform)(nil)
)

func newPlatform(h Host) *platform {
	return &platform{host: h, defaultIndex: -1}
}

func (p *platform) Initialize(nm adapter.NetworkManager) error {
	p.access.Lock()
	defer p.access.Unlock()
	if nm != nil {
		p.nm = nm
	}
	return nil
}

// Protect (Android): sing-box applies this to every socket when
// route.auto_detect_interface is set. iOS binds to the default interface
// instead.
func (p *platform) UsePlatformAutoDetectInterfaceControl() bool { return C.IsAndroid }
func (p *platform) AutoDetectInterfaceControl(fd int) error     { return protectFD(uintptr(fd)) }

func (p *platform) UsePlatformInterface() bool                             { return true }
func (p *platform) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func (p *platform) OpenInterface(o *tun.Options, po option.TunPlatformOptions) (tun.Tun, error) {
	if p.host == nil {
		return nil, E.New("platform: no TUN host")
	}
	if len(o.IncludeUID) > 0 || len(o.ExcludeUID) > 0 {
		return nil, E.New("platform: unsupported uid options")
	}
	if len(o.IncludeAndroidUser) > 0 {
		return nil, E.New("platform: unsupported android_user option")
	}
	// Always the NetworkExtension flavour, like libbox: no 1/8 … 128/1 split.
	ranges, err := o.BuildAutoRouteRanges(true)
	if err != nil {
		return nil, err
	}
	cfg, err := buildTunConfig(o, ranges, po)
	if err != nil {
		return nil, err
	}
	content, err := json.Marshal(cfg)
	if err != nil {
		return nil, err
	}
	fd, err := p.host.OpenTun(string(content))
	if err != nil {
		return nil, E.Cause(err, "open tun")
	}
	if fd < 0 && C.IsIos {
		fd = findTunFd()
	}
	if fd < 0 {
		return nil, E.New("platform: tun file descriptor not found")
	}
	if o.Name, err = tunName(fd); err != nil {
		return nil, E.Cause(err, "query tun name")
	}
	if o.InterfaceMonitor != nil {
		o.InterfaceMonitor.RegisterMyInterface(o.Name)
	}
	// Go owns its own copy; the host keeps (and later closes) the original.
	if o.FileDescriptor, err = dupFd(int(fd)); err != nil {
		return nil, E.Cause(err, "dup tun file descriptor")
	}
	addresses := make([]netip.Addr, 0, len(o.Inet4Address)+len(o.Inet6Address))
	for _, prefix := range append(append([]netip.Prefix{}, o.Inet4Address...), o.Inet6Address...) {
		addresses = append(addresses, prefix.Addr())
	}
	p.access.Lock()
	p.myTunName = o.Name
	p.myTunAddress = addresses
	p.access.Unlock()
	t, err := tun.New(*o)
	if err != nil {
		_ = closeFd(o.FileDescriptor)
		return nil, err
	}
	return t, nil
}

func (p *platform) MyInterfaceAddress() []netip.Addr {
	p.access.Lock()
	defer p.access.Unlock()
	return p.myTunAddress
}

// A test platform on iOS has no host to monitor with; sing-box's own route
// socket monitor works in the app process. On Android the system monitor
// needs netlink, which the app sandbox denies, so a static (empty) platform
// monitor is used instead: dials are protected, not bound.
func (p *platform) UsePlatformDefaultInterfaceMonitor() bool { return p.host != nil || C.IsAndroid }
func (p *platform) UsePlatformNetworkInterfaces() bool       { return p.host != nil || C.IsAndroid }

func (p *platform) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	p.access.Lock()
	p.logger = l
	p.access.Unlock()
	return (*interfaceMonitor)(p)
}

func (p *platform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	if p.host == nil {
		return nil, nil
	}
	content, err := p.host.InterfacesJSON()
	if err != nil {
		return nil, E.Cause(err, "list interfaces")
	}
	p.access.Lock()
	defaultIndex, tunName, expensive, constrained := p.defaultIndex, p.myTunName, p.expensive, p.constrained
	p.access.Unlock()
	interfaces, err := parseInterfaces(content)
	if err != nil {
		return nil, err
	}
	for i := range interfaces {
		if interfaces[i].Index == defaultIndex && interfaces[i].Name != tunName {
			interfaces[i].Expensive = interfaces[i].Expensive || expensive
			interfaces[i].Constrained = constrained
		}
	}
	return common.UniqBy(interfaces, func(it adapter.NetworkInterface) string { return it.Name }), nil
}

func (p *platform) UnderNetworkExtension() bool              { return C.IsIos && p.host != nil }
func (p *platform) NetworkExtensionIncludeAllNetworks() bool { return p.includeAllNetworks }
func (p *platform) ClearDNSCache()                           {}

func (p *platform) RequestPermissionForWIFIState() error            { return nil }
func (p *platform) ReadWIFIState(context.Context) adapter.WIFIState { return adapter.WIFIState{} }
func (p *platform) UsePlatformWIFIMonitor() bool                    { return false }

// Owner lookup backs package_name rules; with a platform on Android sing-box
// always searches (route/router.go:178-180).
func (p *platform) UsePlatformConnectionOwnerFinder() bool { return C.IsAndroid && p.host != nil }

func (p *platform) FindConnectionOwner(r *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	if p.host == nil {
		return nil, os.ErrInvalid
	}
	content, err := p.host.FindOwnerJSON(r.IpProtocol, r.SourceAddress, r.SourcePort, r.DestinationAddress, r.DestinationPort)
	if err != nil {
		return nil, err
	}
	return parseOwner(content)
}

func (p *platform) UsePlatformNotification() bool                             { return false }
func (p *platform) SendNotification(*adapter.Notification) error              { return nil }
func (p *platform) CancelNotification(string, int32) error                    { return nil }
func (p *platform) UsePlatformNeighborResolver() bool                         { return false }
func (p *platform) CloseNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }
func (p *platform) StartNeighborMonitor(adapter.NeighborUpdateListener) error {
	return os.ErrInvalid
}

func (p *platform) UsePlatformShell() bool                           { return false }
func (p *platform) CheckPlatformShell() error                        { return nil }
func (p *platform) TailscaleHostname() string                        { return "" }
func (p *platform) UsePlatformBridge() bool                          { return false }
func (p *platform) LookupSFTPServer() (string, error)                { return "", os.ErrInvalid }
func (p *platform) ReadSystemSSHHostKey() ([]byte, error)            { return nil, os.ErrInvalid }
func (p *platform) LookupUser(string) (*adapter.PlatformUser, error) { return nil, os.ErrInvalid }
func (p *platform) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}
func (p *platform) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, os.ErrInvalid
}

// WriteMessage is the box's PlatformLogWriter (with a platform interface the
// default writer is io.Discard, box.go:169-173).
func (p *platform) WriteMessage(level log.Level, message string) { writeLog(level, message) }

func (p *platform) logError(err error) {
	p.access.Lock()
	l := p.logger
	p.access.Unlock()
	if l != nil {
		l.Error(err)
	} else {
		logf(log.LevelError, "%v", err)
	}
}

// ---- JSON shapes exchanged with the host (documented on Host) ----

type tunConfig struct {
	Inet4Address             []string      `json:"inet4_address"`
	Inet6Address             []string      `json:"inet6_address"`
	MTU                      uint32        `json:"mtu"`
	AutoRoute                bool          `json:"auto_route"`
	StrictRoute              bool          `json:"strict_route"`
	DNSMode                  string        `json:"dns_mode"`
	DNSServers               []string      `json:"dns_servers"`
	Inet4RouteRange          []string      `json:"inet4_route_range"`
	Inet6RouteRange          []string      `json:"inet6_route_range"`
	Inet4RouteAddress        []string      `json:"inet4_route_address"`
	Inet6RouteAddress        []string      `json:"inet6_route_address"`
	Inet4RouteExcludeAddress []string      `json:"inet4_route_exclude_address"`
	Inet6RouteExcludeAddress []string      `json:"inet6_route_exclude_address"`
	IncludePackage           []string      `json:"include_package"`
	ExcludePackage           []string      `json:"exclude_package"`
	HTTPProxy                *tunHTTPProxy `json:"http_proxy,omitempty"`
}

type tunHTTPProxy struct {
	Server       string   `json:"server"`
	ServerPort   uint16   `json:"server_port"`
	BypassDomain []string `json:"bypass_domain"`
	MatchDomain  []string `json:"match_domain"`
}

type hostInterface struct {
	Name      string   `json:"name"`
	Index     int32    `json:"index"`
	MTU       int32    `json:"mtu"`
	Type      string   `json:"type"`
	Addresses []string `json:"addresses"`
	DNS       []string `json:"dns"`
	Gateways  []string `json:"gateways,omitempty"`
	Metered   bool     `json:"metered"`
}

type hostOwner struct {
	UID         int32    `json:"uid"`
	UserName    string   `json:"user_name,omitempty"`
	ProcessPath string   `json:"process_path,omitempty"`
	Packages    []string `json:"packages"`
}

func stringsOf[T fmt.Stringer](in []T) []string {
	out := make([]string, 0, len(in))
	for _, it := range in {
		out = append(out, it.String())
	}
	return out
}

func nonNil(in []string) []string {
	if in == nil {
		return []string{}
	}
	return in
}

// buildTunConfig flattens the TUN options sing-box hands to OpenInterface
// (spec/singbox-mobile.md §2; mirrors libbox tunOptions).
func buildTunConfig(o *tun.Options, ranges []netip.Prefix, po option.TunPlatformOptions) (*tunConfig, error) {
	dnsServers, err := o.DNSServerAddress()
	if err != nil {
		return nil, err
	}
	var r4, r6 []netip.Prefix
	for _, r := range ranges {
		if r.Addr().Is4() {
			r4 = append(r4, r)
		} else {
			r6 = append(r6, r)
		}
	}
	cfg := &tunConfig{
		Inet4Address:             stringsOf(o.Inet4Address),
		Inet6Address:             stringsOf(o.Inet6Address),
		MTU:                      o.MTU,
		AutoRoute:                o.AutoRoute,
		StrictRoute:              o.StrictRoute,
		DNSMode:                  o.DNSModeOrDefault(),
		DNSServers:               stringsOf(dnsServers),
		Inet4RouteRange:          stringsOf(r4),
		Inet6RouteRange:          stringsOf(r6),
		Inet4RouteAddress:        stringsOf(o.Inet4RouteAddress),
		Inet6RouteAddress:        stringsOf(o.Inet6RouteAddress),
		Inet4RouteExcludeAddress: stringsOf(o.Inet4RouteExcludeAddress),
		Inet6RouteExcludeAddress: stringsOf(o.Inet6RouteExcludeAddress),
		IncludePackage:           nonNil(o.IncludePackage),
		ExcludePackage:           nonNil(o.ExcludePackage),
	}
	if hp := po.HTTPProxy; hp != nil && hp.Enabled {
		cfg.HTTPProxy = &tunHTTPProxy{
			Server:       hp.Server,
			ServerPort:   hp.ServerPort,
			BypassDomain: nonNil(hp.BypassDomain),
			MatchDomain:  nonNil(hp.MatchDomain),
		}
	}
	return cfg, nil
}

// parseInterfaces decodes Host.InterfacesJSON. Every entry is marked up
// (sing-box drops interfaces without net.FlagUp, route/network.go:291-293).
func parseInterfaces(content string) ([]adapter.NetworkInterface, error) {
	var in []hostInterface
	if err := json.Unmarshal([]byte(content), &in); err != nil {
		return nil, E.Cause(err, "decode interfaces")
	}
	out := make([]adapter.NetworkInterface, 0, len(in))
	for _, h := range in {
		ifType, ok := C.StringToInterfaceType[h.Type]
		if !ok {
			ifType = C.InterfaceTypeOther
		}
		ni := adapter.NetworkInterface{
			Interface: control.Interface{
				Index: int(h.Index),
				MTU:   int(h.MTU),
				Name:  h.Name,
				Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast,
			},
			Type:       ifType,
			DNSServers: h.DNS,
			Expensive:  h.Metered,
		}
		for _, a := range h.Addresses {
			if prefix, err := netip.ParsePrefix(a); err == nil {
				ni.Addresses = append(ni.Addresses, prefix)
			} else if addr, err := netip.ParseAddr(a); err == nil {
				ni.Addresses = append(ni.Addresses, netip.PrefixFrom(addr, addr.BitLen()))
			}
		}
		for _, g := range h.Gateways {
			if addr, err := netip.ParseAddr(g); err == nil {
				ni.Gateways = append(ni.Gateways, addr.Unmap().WithZone(""))
			}
		}
		out = append(out, ni)
	}
	return out, nil
}

// parseOwner decodes Host.FindOwnerJSON; a missing or negative uid means
// "not found".
func parseOwner(content string) (*adapter.ConnectionOwner, error) {
	o := hostOwner{UID: -1}
	if err := json.Unmarshal([]byte(content), &o); err != nil {
		return nil, E.Cause(err, "decode connection owner")
	}
	if o.UID < 0 {
		return nil, E.New("connection owner not found")
	}
	return &adapter.ConnectionOwner{
		UserId:              o.UID,
		UserName:            o.UserName,
		ProcessPath:         o.ProcessPath,
		AndroidPackageNames: o.Packages,
	}, nil
}
