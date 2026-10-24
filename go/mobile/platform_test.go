package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"reflect"
	"slices"
	"sync"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

func TestTunConfigJSON(t *testing.T) {
	o := &tun.Options{
		Inet4Address:             []netip.Prefix{netip.MustParsePrefix("172.19.0.1/28")},
		Inet6Address:             []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
		MTU:                      9000,
		AutoRoute:                true,
		Inet4RouteExcludeAddress: []netip.Prefix{netip.MustParsePrefix("10.0.0.0/8")},
		ExcludePackage:           []string{"com.duobao.proxy"},
	}
	ranges, err := o.BuildAutoRouteRanges(true)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := buildTunConfig(o, ranges, option.TunPlatformOptions{})
	if err != nil {
		t.Fatal(err)
	}
	content, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err = json.Unmarshal(content, &m); err != nil {
		t.Fatal(err)
	}
	// Every documented key is present; lists are [] rather than null.
	for _, key := range []string{"inet4_address", "inet6_address", "mtu", "auto_route", "strict_route", "dns_mode", "dns_servers",
		"inet4_route_range", "inet6_route_range", "inet4_route_address", "inet6_route_address",
		"inet4_route_exclude_address", "inet6_route_exclude_address", "include_package", "exclude_package"} {
		v, ok := m[key]
		if !ok || v == nil {
			t.Errorf("key %q missing or null in %s", key, content)
		}
	}
	if _, ok := m["http_proxy"]; ok {
		t.Errorf("http_proxy present while disabled: %s", content)
	}

	var back tunConfig
	if err = json.Unmarshal(content, &back); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(&back, cfg) {
		t.Errorf("round trip mismatch:\n%+v\n%+v", back, *cfg)
	}
	if back.DNSMode != tun.DNSModeHijack {
		t.Errorf("dns_mode = %q, want hijack (auto_route default)", back.DNSMode)
	}
	// spec/critic.md B2: the address after the first TUN address.
	if want := []string{"172.19.0.2", "fdfe:dcba:9876::2"}; !slices.Equal(back.DNSServers, want) {
		t.Errorf("dns_servers = %v, want %v", back.DNSServers, want)
	}
	if want := []string{"::/0"}; !slices.Equal(back.Inet6RouteRange, want) {
		t.Errorf("inet6_route_range = %v, want %v", back.Inet6RouteRange, want)
	}
	covers := func(ip string) bool {
		for _, r := range back.Inet4RouteRange {
			if netip.MustParsePrefix(r).Contains(netip.MustParseAddr(ip)) {
				return true
			}
		}
		return false
	}
	if !covers("8.8.8.8") || covers("10.1.2.3") {
		t.Errorf("inet4_route_range %v must cover 8.8.8.8 and exclude 10/8", back.Inet4RouteRange)
	}
	if !slices.Equal(back.Inet4RouteExcludeAddress, []string{"10.0.0.0/8"}) || !slices.Equal(back.ExcludePackage, []string{"com.duobao.proxy"}) {
		t.Errorf("raw excludes / packages not passed through: %+v", back)
	}

	po := option.TunPlatformOptions{HTTPProxy: &option.HTTPProxyOptions{
		Enabled:       true,
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 2080},
		BypassDomain:  badoption.Listable[string]{"lan"},
	}}
	cfg, err = buildTunConfig(o, ranges, po)
	if err != nil {
		t.Fatal(err)
	}
	content, _ = json.Marshal(cfg)
	var hp struct {
		HTTPProxy *tunHTTPProxy `json:"http_proxy"`
	}
	_ = json.Unmarshal(content, &hp)
	if hp.HTTPProxy == nil || hp.HTTPProxy.Server != "127.0.0.1" || hp.HTTPProxy.ServerPort != 2080 ||
		!slices.Equal(hp.HTTPProxy.BypassDomain, []string{"lan"}) || hp.HTTPProxy.MatchDomain == nil {
		t.Errorf("http_proxy = %s", content)
	}
}

func TestParseInterfaces(t *testing.T) {
	in := `[
	 {"name":"wlan0","index":30,"mtu":1500,"type":"wifi","addresses":["192.168.1.5/24","fe80::1/64","10.0.0.9"],
	  "dns":["192.168.1.1"],"gateways":["192.168.1.1"],"metered":false},
	 {"name":"rmnet0","index":31,"type":"cellular","metered":true},
	 {"name":"x","index":32,"type":"bogus"}]`
	got, err := parseInterfaces(in)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 {
		t.Fatalf("got %d interfaces", len(got))
	}
	w := got[0]
	if w.Name != "wlan0" || w.Index != 30 || w.MTU != 1500 || w.Type != C.InterfaceTypeWIFI || w.Expensive {
		t.Errorf("wlan0 = %+v", w)
	}
	if w.Flags&net.FlagUp == 0 {
		t.Error("FlagUp not set")
	}
	wantAddr := []netip.Prefix{netip.MustParsePrefix("192.168.1.5/24"), netip.MustParsePrefix("fe80::1/64"), netip.MustParsePrefix("10.0.0.9/32")}
	if !slices.Equal(w.Addresses, wantAddr) {
		t.Errorf("addresses = %v", w.Addresses)
	}
	if !slices.Equal(w.DNSServers, []string{"192.168.1.1"}) || !slices.Equal(w.Gateways, []netip.Addr{netip.MustParseAddr("192.168.1.1")}) {
		t.Errorf("dns/gateways = %v %v", w.DNSServers, w.Gateways)
	}
	if got[1].Type != C.InterfaceTypeCellular || !got[1].Expensive {
		t.Errorf("rmnet0 = %+v", got[1])
	}
	if got[2].Type != C.InterfaceTypeOther {
		t.Errorf("unknown type = %v", got[2].Type)
	}
	if _, err = parseInterfaces("{"); err == nil {
		t.Error("bad JSON accepted")
	}
}

func TestParseOwner(t *testing.T) {
	o, err := parseOwner(`{"uid":10123,"user_name":"u0_a123","process_path":"","packages":["com.example","com.example.shared"]}`)
	if err != nil {
		t.Fatal(err)
	}
	want := &adapter.ConnectionOwner{UserId: 10123, UserName: "u0_a123", AndroidPackageNames: []string{"com.example", "com.example.shared"}}
	if !reflect.DeepEqual(o, want) {
		t.Errorf("owner = %+v", o)
	}
	for _, bad := range []string{`{"uid":-1}`, `{}`, `nope`} {
		if _, err = parseOwner(bad); err == nil {
			t.Errorf("%s accepted", bad)
		}
	}
	// And through the platform with a host.
	h := &fakeHost{owner: `{"uid":0,"packages":[]}`}
	o, err = newPlatform(h).FindConnectionOwner(&adapter.FindConnectionOwnerRequest{IpProtocol: 6, SourceAddress: "172.19.0.1", SourcePort: 1, DestinationAddress: "1.1.1.1", DestinationPort: 443})
	if err != nil || o.UserId != 0 {
		t.Errorf("FindConnectionOwner = %+v, %v", o, err)
	}
	if h.ownerArgs != "6 172.19.0.1 1 1.1.1.1 443" {
		t.Errorf("host got %q", h.ownerArgs)
	}
}

// ---- fakes ----

type fakeHost struct {
	mu         sync.Mutex
	interfaces string
	owner      string
	ownerArgs  string
	listener   *NetworkListener
	onStart    func(l *NetworkListener)
	closed     bool
}

func (h *fakeHost) OpenTun(string) (int32, error) { return -1, errors.New("no tun in tests") }
func (h *fakeHost) StartNetworkMonitor(l *NetworkListener) error {
	h.mu.Lock()
	h.listener = l
	h.mu.Unlock()
	if h.onStart != nil {
		h.onStart(l)
	}
	return nil
}
func (h *fakeHost) CloseNetworkMonitor() error { h.closed = true; return nil }
func (h *fakeHost) InterfacesJSON() (string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.interfaces, nil
}
func (h *fakeHost) FindOwnerJSON(proto int32, src string, sport int32, dst string, dport int32) (string, error) {
	h.ownerArgs = fmt.Sprintf("%d %s %d %s %d", proto, src, sport, dst, dport)
	return h.owner, nil
}
func (h *fakeHost) setInterfaces(s string) {
	h.mu.Lock()
	h.interfaces = s
	h.mu.Unlock()
}

// fakeNetworkManager mimics route.NetworkManager.UpdateInterfaces for a
// platform with UsePlatformNetworkInterfaces (route/network.go:266-310).
type fakeNetworkManager struct {
	p          *platform
	finder     *control.DefaultInterfaceFinder
	interfaces []adapter.NetworkInterface
	updates    int
}

func (f *fakeNetworkManager) UpdateInterfaces() error {
	f.updates++
	list, err := f.p.NetworkInterfaces()
	if err != nil {
		return err
	}
	var raw []control.Interface
	f.interfaces = nil
	for _, it := range list {
		raw = append(raw, it.Interface)
		if it.Flags&net.FlagUp != 0 {
			f.interfaces = append(f.interfaces, it)
		}
	}
	f.finder.UpdateInterfaces(raw)
	return nil
}
func (f *fakeNetworkManager) InterfaceFinder() control.InterfaceFinder { return f.finder }
func (f *fakeNetworkManager) NetworkInterfaces() []adapter.NetworkInterface {
	return f.interfaces
}

func TestNetworkListenerUpdates(t *testing.T) {
	const wifi = `[{"name":"wlan0","index":30,"type":"wifi","dns":["192.168.1.1"]},{"name":"rmnet0","index":31,"type":"cellular"}]`
	h := &fakeHost{interfaces: wifi}
	h.onStart = func(l *NetworkListener) { l.UpdateDefaultInterface("wlan0", 30, true, false) }
	p := newPlatform(h)
	nm := &fakeNetworkManager{p: p, finder: control.NewDefaultInterfaceFinder()}
	p.nm = nm

	type event struct {
		name string
		nil  bool
	}
	var mu sync.Mutex
	var events []event
	mon := p.CreateDefaultInterfaceMonitor(nil)
	mon.RegisterCallback(func(iface *control.Interface, _ int) {
		mu.Lock()
		defer mu.Unlock()
		if iface == nil {
			events = append(events, event{nil: true})
		} else {
			events = append(events, event{name: iface.Name})
		}
	})
	last := func() []event { mu.Lock(); defer mu.Unlock(); return slices.Clone(events) }

	// Start must deliver the current network synchronously.
	if err := mon.Start(); err != nil {
		t.Fatal(err)
	}
	if got := mon.DefaultInterface(); got == nil || got.Name != "wlan0" || got.Index != 30 {
		t.Fatalf("default after Start = %+v", got)
	}
	if ev := last(); len(ev) != 1 || ev[0].name != "wlan0" {
		t.Fatalf("events = %+v", ev)
	}
	// expensive applies to the default interface only.
	ifs, _ := p.NetworkInterfaces()
	if !ifs[0].Expensive || ifs[1].Expensive {
		t.Errorf("expensive flags = %v %v", ifs[0].Expensive, ifs[1].Expensive)
	}

	// Same network again: interfaces refreshed, no callback.
	h.listener.UpdateDefaultInterface("wlan0", 30, true, false)
	if ev := last(); len(ev) != 1 {
		t.Errorf("duplicate update fired a callback: %+v", ev)
	}
	if nm.updates != 2 {
		t.Errorf("UpdateInterfaces called %d times", nm.updates)
	}

	// Switch to cellular.
	h.listener.UpdateDefaultInterface("rmnet0", 31, false, false)
	if got := mon.DefaultInterface(); got == nil || got.Name != "rmnet0" {
		t.Errorf("default = %+v", got)
	}
	// Unknown index: logged, default unchanged.
	h.listener.UpdateDefaultInterface("ghost", 99, false, false)
	if got := mon.DefaultInterface(); got == nil || got.Name != "rmnet0" {
		t.Errorf("default after unknown index = %+v", got)
	}
	// No network.
	h.listener.UpdateDefaultInterface("", -1, false, false)
	if mon.DefaultInterface() != nil {
		t.Error("default not cleared by index -1")
	}
	want := []event{{name: "wlan0"}, {name: "rmnet0"}, {nil: true}}
	if ev := last(); !reflect.DeepEqual(ev, want) {
		t.Errorf("events = %+v, want %+v", ev, want)
	}

	// After Close updates are ignored.
	if err := mon.Close(); err != nil || !h.closed {
		t.Fatalf("Close = %v (closed=%v)", err, h.closed)
	}
	h.listener.UpdateDefaultInterface("wlan0", 30, false, false)
	if mon.DefaultInterface() != nil || len(last()) != 3 {
		t.Error("update after Close was applied")
	}

	mon.RegisterMyInterface("tun0")
	if !slices.Equal(mon.MyInterfaces(), []string{"tun0"}) {
		t.Errorf("MyInterfaces = %v", mon.MyInterfaces())
	}
	// A nil listener (or one the host built itself) is harmless.
	(*NetworkListener)(nil).UpdateDefaultInterface("x", 1, false, false)
	(&NetworkListener{}).UpdateDefaultInterface("x", 1, false, false)
}

func TestPlatformStubs(t *testing.T) {
	for _, tc := range []struct {
		name string
		p    *platform
	}{{"host", newPlatform(&fakeHost{interfaces: "[]"})}, {"test", newPlatform(nil)}} {
		p := tc.p
		hasHost := p.host != nil
		checks := []struct {
			what      string
			got, want any
		}{
			{"UsePlatformAutoDetectInterfaceControl", p.UsePlatformAutoDetectInterfaceControl(), C.IsAndroid},
			{"UsePlatformInterface", p.UsePlatformInterface(), true},
			{"ProcessPlatformOptions", p.ProcessPlatformOptions(option.TunPlatformOptions{}), nil},
			{"UsePlatformDefaultInterfaceMonitor", p.UsePlatformDefaultInterfaceMonitor(), hasHost || C.IsAndroid},
			{"UsePlatformNetworkInterfaces", p.UsePlatformNetworkInterfaces(), hasHost || C.IsAndroid},
			{"UnderNetworkExtension", p.UnderNetworkExtension(), C.IsIos && hasHost},
			{"NetworkExtensionIncludeAllNetworks", p.NetworkExtensionIncludeAllNetworks(), false},
			{"RequestPermissionForWIFIState", p.RequestPermissionForWIFIState(), nil},
			{"ReadWIFIState", p.ReadWIFIState(context.Background()), adapter.WIFIState{}},
			{"UsePlatformWIFIMonitor", p.UsePlatformWIFIMonitor(), false},
			{"UsePlatformConnectionOwnerFinder", p.UsePlatformConnectionOwnerFinder(), C.IsAndroid && hasHost},
			{"UsePlatformNotification", p.UsePlatformNotification(), false},
			{"SendNotification", p.SendNotification(&adapter.Notification{}), nil},
			{"CancelNotification", p.CancelNotification("", 0), nil},
			{"MyInterfaceAddress", len(p.MyInterfaceAddress()), 0},
			{"UsePlatformNeighborResolver", p.UsePlatformNeighborResolver(), false},
			{"StartNeighborMonitor", p.StartNeighborMonitor(nil), os.ErrInvalid},
			{"CloseNeighborMonitor", p.CloseNeighborMonitor(nil), nil},
			{"UsePlatformShell", p.UsePlatformShell(), false},
			{"CheckPlatformShell", p.CheckPlatformShell(), nil},
			{"TailscaleHostname", p.TailscaleHostname(), ""},
			{"UsePlatformBridge", p.UsePlatformBridge(), false},
		}
		for _, c := range checks {
			if !reflect.DeepEqual(c.got, c.want) {
				t.Errorf("%s: %s = %v, want %v", tc.name, c.what, c.got, c.want)
			}
		}
		if _, err := p.OpenShellSession(nil, "", nil, "", 0, 0); err != os.ErrInvalid {
			t.Errorf("%s: OpenShellSession err = %v", tc.name, err)
		}
		if _, err := p.LookupUser("x"); err != os.ErrInvalid {
			t.Errorf("%s: LookupUser err = %v", tc.name, err)
		}
		if _, err := p.LookupSFTPServer(); err != os.ErrInvalid {
			t.Errorf("%s: LookupSFTPServer err = %v", tc.name, err)
		}
		if _, err := p.ReadSystemSSHHostKey(); err != os.ErrInvalid {
			t.Errorf("%s: ReadSystemSSHHostKey err = %v", tc.name, err)
		}
		if _, err := p.CreateBridge(adapter.BridgeOptions{}); err != os.ErrInvalid {
			t.Errorf("%s: CreateBridge err = %v", tc.name, err)
		}
		if _, err := p.OpenInterface(&tun.Options{}, option.TunPlatformOptions{}); err == nil {
			t.Errorf("%s: OpenInterface succeeded without a tun", tc.name)
		}
	}
	// uid options are rejected before the host is asked.
	p := newPlatform(&fakeHost{})
	if _, err := p.OpenInterface(&tun.Options{IncludeAndroidUser: []int{0}}, option.TunPlatformOptions{}); err == nil {
		t.Error("include_android_user accepted")
	}
	// Test platform: no interfaces, no owner lookup, monitor is a no-op.
	tp := newPlatform(nil)
	if ifs, err := tp.NetworkInterfaces(); ifs != nil || err != nil {
		t.Errorf("test NetworkInterfaces = %v, %v", ifs, err)
	}
	if _, err := tp.FindConnectionOwner(&adapter.FindConnectionOwnerRequest{}); err != os.ErrInvalid {
		t.Errorf("test FindConnectionOwner err = %v", err)
	}
	mon := tp.CreateDefaultInterfaceMonitor(nil)
	if mon.Start() != nil || mon.DefaultInterface() != nil || mon.Close() != nil {
		t.Error("test monitor is not a no-op")
	}
}

type fakeLogHandler struct {
	mu    sync.Mutex
	lines []string
}

func (h *fakeLogHandler) WriteLog(level int32, line string) {
	h.mu.Lock()
	h.lines = append(h.lines, fmt.Sprintf("%d %s", level, line))
	h.mu.Unlock()
}

func TestWriteMessage(t *testing.T) {
	h := &fakeLogHandler{}
	SetLogHandler(h)
	defer SetLogHandler(nil)
	newPlatform(nil).WriteMessage(3, "hello")
	if !slices.Equal(h.lines, []string{"3 hello"}) {
		t.Errorf("lines = %v", h.lines)
	}
	SetLogHandler(nil)
	newPlatform(nil).WriteMessage(3, "dropped")
	if len(h.lines) != 1 {
		t.Error("line written after SetLogHandler(nil)")
	}
}

type fakeProtector struct {
	mu   sync.Mutex
	fds  []int32
	fail bool
}

func (f *fakeProtector) Protect(fd int32) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fds = append(f.fds, fd)
	return !f.fail
}

func TestProtectedDialer(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	logs := &fakeLogHandler{}
	SetLogHandler(logs)
	defer SetLogHandler(nil)

	// No protector: plain dial.
	SetProtector(nil)
	c, err := protectedDialer().Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()

	fp := &fakeProtector{}
	SetProtector(fp)
	defer SetProtector(nil)
	c, err = protectedDialer().Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if len(fp.fds) != 1 {
		t.Errorf("Protect called %d times", len(fp.fds))
	}
	// The sing-box AutoDetectInterfaceControl path uses the same protector.
	if err = newPlatform(nil).AutoDetectInterfaceControl(42); err != nil || fp.fds[len(fp.fds)-1] != 42 {
		t.Errorf("AutoDetectInterfaceControl = %v, fds %v", err, fp.fds)
	}

	fp.fail = true
	protectFailures.Store(0) // the first failure is always logged
	before := protectFailures.Load()
	d := protectedDialer()
	d.Timeout = 2 * time.Second
	if _, err = d.Dial("tcp", ln.Addr().String()); !errors.Is(err, errProtect) {
		t.Errorf("dial with failing protector: %v", err)
	}
	if protectFailures.Load() != before+1 {
		t.Errorf("failures %d -> %d", before, protectFailures.Load())
	}
	if len(logs.lines) == 0 {
		t.Error("protect failure not logged")
	}
	// SetProtector again must not register a second xray controller.
	SetProtector(fp)
}

// fakeDNS answers every A query with 192.0.2.7.
type fakeDNS struct {
	mu      sync.Mutex
	queries []string
}

func (f *fakeDNS) Exchange(message []byte) ([]byte, error) {
	var q mDNS.Msg
	if err := q.Unpack(message); err != nil {
		return nil, err
	}
	f.mu.Lock()
	f.queries = append(f.queries, q.Question[0].Name)
	f.mu.Unlock()
	r := new(mDNS.Msg)
	r.SetReply(&q)
	if q.Question[0].Qtype == mDNS.TypeA {
		r.Answer = append(r.Answer, &mDNS.A{
			Hdr: mDNS.RR_Header{Name: q.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60},
			A:   net.IPv4(192, 0, 2, 7),
		})
	}
	return r.Pack()
}

const localDNSConfig = `{"log":{"disabled":true},
 "dns":{"servers":[{"type":"local","tag":"dns-local"}],"strategy":"ipv4_only"},
 "outbounds":[{"type":"direct","tag":"direct"}]}`

// startBox starts a minimal box on ctx and returns its (cancelable) context.
func startBox(t *testing.T, ctx context.Context) context.Context {
	t.Helper()
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(localDNSConfig))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(ctx)
	b, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close(); cancel() })
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	return ctx
}

func localTransport(t *testing.T, ctx context.Context) adapter.DNSTransport {
	t.Helper()
	tr, ok := service.FromContext[adapter.DNSTransportManager](ctx).Transport("dns-local")
	if !ok {
		t.Fatal("dns-local transport missing")
	}
	return tr
}

func TestLocalDNSThroughBox(t *testing.T) {
	resolver := &fakeDNS{}
	SetLocalDNS(resolver)
	defer SetLocalDNS(nil)

	dir := t.TempDir()
	ctx := newTestContext(dir, dir)
	if p := platformFromContext(ctx); p == nil || p.host != nil {
		t.Fatal("test platform not registered")
	}
	ctx = startBox(t, ctx)
	if _, ours := localTransport(t, ctx).(*localDNSTransport); !ours {
		t.Fatalf("dns-local is %T", localTransport(t, ctx))
	}
	router := service.FromContext[adapter.DNSRouter](ctx)
	lookupCtx, lookupCancel := context.WithTimeout(ctx, 5*time.Second)
	defer lookupCancel()
	addrs, err := router.Lookup(lookupCtx, "duobao.invalid", adapter.DNSQueryOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(addrs, []netip.Addr{netip.MustParseAddr("192.0.2.7")}) {
		t.Errorf("addrs = %v", addrs)
	}
	resolver.mu.Lock()
	defer resolver.mu.Unlock()
	if len(resolver.queries) == 0 || resolver.queries[0] != "duobao.invalid." {
		t.Errorf("host resolver queries = %v", resolver.queries)
	}
}

func TestLocalDNSDefaultKept(t *testing.T) {
	SetLocalDNS(nil)
	dir := t.TempDir()
	ctx := newBoxContext(&fakeHost{interfaces: "[]"}, dir, dir)
	if p := platformFromContext(ctx); p == nil || p.host == nil {
		t.Fatal("host platform not registered")
	}
	ctx = startBox(t, ctx)
	if tr := localTransport(t, ctx); tr.Type() != C.DNSTypeLocal {
		t.Errorf("dns-local type = %s", tr.Type())
	} else if _, ours := tr.(*localDNSTransport); ours {
		t.Error("platform local transport registered without SetLocalDNS")
	}
}
