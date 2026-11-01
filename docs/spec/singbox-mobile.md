# Running sing-box 1.14.3 in Android VpnService and iOS Packet Tunnel without libbox

## Summary

- **Approach.** Don't import `experimental/libbox`. It pulls in `daemon`, gRPC, the command server, power and OOM reports and the crash-report zip/age code. Instead, write our own type that implements `adapter.PlatformInterface`, register it into the box context, and give it a small gomobile host interface.
- **What actually needs implementing.** On Android: protect, open TUN, the default-interface monitor, the network-interface list, and optionally the connection-owner lookup. On iOS: open TUN (Go finds the utun fd itself), the default-interface monitor, the network-interface list, and `UnderNetworkExtension() == true`. Everything else can be stubbed.
- **Config that the render layer must emit on mobile:**
  - `route.auto_detect_interface: true`. Without it, protect is never applied and traffic loops back into the tunnel.
  - A TUN inbound with `auto_route: true`.
  - Explicit DNS servers, not `local` (on Android, `local` falls back to 127.0.0.1).
  - An absolute cache-file path. The desktop's `os.TempDir()` path does not work on Android.

Path abbreviations below: **SB** = `C:/Users/Administrator/go/pkg/mod/github.com/sagernet/sing-box@v1.14.3`, **LB** = `SB/experimental/libbox`, **ST** = `C:/Users/Administrator/go/pkg/mod/github.com/sagernet/sing-tun@v0.9.7-0.20261009020158-bca4bf029237`.

---

## 1. `adapter.PlatformInterface` (SB/adapter/platform.go:12-60): implement or stub

Who calls each method in sing-box:

| Method | Called from | Android | iOS | Stub value |
|---|---|---|---|---|
| `Initialize(nm)` | SB/box.go:417-418 | **Implement**: store `nm`; the monitor needs it | **Implement** | – |
| `UsePlatformAutoDetectInterfaceControl()` | SB/route/network.go:341,369 | **true** | **false** (see §3) | false |
| `AutoDetectInterfaceControl(fd)` | network.go:344,372 | **Implement**: `VpnService.protect(fd)` | unused | `nil` |
| `UsePlatformInterface()` | SB/protocol/tun/inbound.go:440 | **true** | **true** | – |
| `OpenInterface(opts, pOpts)` | inbound.go:441 | **Implement** (§2/§3) | **Implement** | – |
| `ProcessPlatformOptions(pOpts)` | inbound.go:454 | `nil` (libbox does the same, LB/service.go:90-92) | `nil` | `nil` |
| `UsePlatformDefaultInterfaceMonitor()` | network.go:120,141 | **true** | **true** | – |
| `CreateDefaultInterfaceMonitor(l)` | network.go:141 | **Implement** | **Implement** | – |
| `UsePlatformNetworkInterfaces()` | network.go:267; SB/common/dialer/default.go:106 | **true** (Go's own `net.Interfaces` is not dependable inside the Android app sandbox; this is general platform knowledge, not shown in the files read) | **true** | – |
| `NetworkInterfaces()` | network.go:270 | **Implement** with full data | **Implement**: name, index and type are enough, because on Darwin sing-box fills in the rest from `interfaceFinder.Update()` (network.go:274-286) | – |
| `UnderNetworkExtension()` | inbound.go:109,460; network.go:133; SB/service/oomkiller/policy.go:46 | **false** | **true** | false |
| `NetworkExtensionIncludeAllNetworks()` | inbound.go:461 (forces the gvisor stack: ST/stack.go:43-44, and stacks `mixed`/`system` then return an error: ST/stack.go:53-60) | false | false (or the `NETunnelProviderProtocol.includeAllNetworks` value) | false |
| `ClearDNSCache()` | SB/dns/router.go:1377 | no-op | no-op | – |
| `RequestPermissionForWIFIState()` | – | `nil` | `nil` | `nil` |
| `UsePlatformWIFIMonitor()` / `ReadWIFIState` | network.go:204,447-448 | false (only needed for `wifi_ssid` rules) | false | `false` / `adapter.WIFIState{}` |
| `UsePlatformConnectionOwnerFinder()` / `FindConnectionOwner` | SB/route/router.go:183; SB/route/platform_searcher.go:21-44 | **Recommended true**: needed for `package_name` rules and per-connection app names. Android with a platform interface **always** enables process search (router.go:178-180); if this returns false, sing-box falls back to `process.NewSearcher` with no package manager (router.go:186-190) | false | `false` / `nil, os.ErrInvalid` |
| `UsePlatformNotification` / `SendNotification` / `CancelNotification` | tailscale only | false | false | `false` / `nil` / `nil` |
| `MyInterfaceAddress()` | SB/route/process_cache.go:82; bridge outbound | **Implement**: return the TUN addresses (LB/service.go:94-107) | **Implement** | – |
| `UsePlatformNeighborResolver` / `Start/CloseNeighborMonitor` | router.go:109 | false | false | `false` / `os.ErrInvalid` / `nil` |
| `UsePlatformShell`, `CheckPlatformShell`, `OpenShellSession`, `LookupUser`, `LookupSFTPServer`, `ReadSystemSSHHostKey`, `TailscaleHostname` | tailscale ssh only | stub | stub | `false`, `nil`, `nil,os.ErrInvalid`, `nil,os.ErrInvalid`, `"",os.ErrInvalid`, `nil,os.ErrInvalid`, `""` |
| `UsePlatformBridge` / `CreateBridge` | bridge protocol | false | false | `false` / `nil, os.ErrInvalid` |

The stub values follow libbox's own `platformInterfaceStub` (LB/config.go:70-213).

**DNS caveat.** libbox also swaps the `local` DNS transport for a host-provided resolver (LB/config.go:29-37, LB/dns.go:23-27). Without that swap, `local` on Android reads `/etc/resolv.conf`, which is missing, and falls back to `127.0.0.1:53` / `[::1]:53` (SB/dns/transport/local/systemconfig/source_resolv.go:92-96, config.go:17-20). **Decision:** render must not use `type: local` on Android. Use explicit UDP/DoH servers with `detour: direct`, or `type: dhcp` (only with `with_dhcp`, which upstream enables only on Darwin).

---

## 2. TUN data the host needs

sing-box passes `*tun.Options` (built at inbound.go:200-231, then route sets merged at inbound.go:418-438) plus `option.TunPlatformOptions` to `OpenInterface`. libbox computes `routeRanges := options.BuildAutoRouteRanges(true)` (LB/service.go:66). Note that it **always passes `underNetworkExtension=true`**, so Darwin never gets the 1/8 … 128/1 sub-ranges (ST/tun_rules.go:107,122-136). libbox then wraps the data as `TunOptions` (LB/tun.go:18-39, implemented at :84-179):

| Data | libbox getter | Source field | Android `VpnService.Builder` | iOS `NEPacketTunnelNetworkSettings` |
|---|---|---|---|---|
| IPv4 / IPv6 addresses | `GetInet4Address()` / `GetInet6Address()` → `RoutePrefix{Address(),Prefix(),Mask()}` (tun.go:41-66) | `Inet4Address`/`Inet6Address` from `address` | `addAddress(addr, prefix)` | `NEIPv4Settings(addresses:, subnetMasks: Mask())`, `NEIPv6Settings(addresses:, networkPrefixLengths:)` |
| DNS mode | `GetDNSMode()` | `DNSMode`; the effective default is `hijack` when `auto_route`, else `disabled` (ST/tun.go:127-135). Constants: LB/tun.go:12-16 | if `disabled`, do not add DNS | same |
| DNS servers | `GetDNSServerAddress()` (tun.go:102-108) | `dns_address`, or else the address after the first TUN address, e.g. `172.19.0.1/30` → `172.19.0.2` (ST/tun.go:137-179) | `addDnsServer(ip)` | `NEDNSSettings(servers:)`; set `matchDomains = [""]` to capture all DNS (recommendation) |
| MTU | `GetMTU()` | Default when 0: **9000 on Android**, **4064 under NE**, otherwise 65535 (inbound.go:107-119) | `setMtu` | `settings.mtu` |
| auto_route / strict_route | `GetAutoRoute()` / `GetStrictRoute()` | | if `!autoRoute`, add no routes | – |
| **Final routes (use these)** | `GetInet4RouteRange()` / `GetInet6RouteRange()` (tun.go:138-148) | `BuildAutoRouteRanges`: `route_address`, or `0.0.0.0/0` when `auto_route`, **minus `route_exclude_address`**, already subtracted (ST/tun_rules.go:109-150) | `addRoute(addr, prefix)` for each range. No `excludeRoute` is needed because the excludes are already subtracted | `includedRoutes = ranges`, or `includedRoutes=[default]` plus `excludedRoutes` built from the next row |
| Raw routes | `GetInet4RouteAddress`/`GetInet6RouteAddress`, `GetInet4RouteExcludeAddress`/`GetInet6RouteExcludeAddress` (tun.go:122-136) | `route_address`, `route_exclude_address` plus rule-set IPs (see the note below) | optional | `NEIPv4Route` list for `excludedRoutes` |
| Per-app | `GetIncludePackage()` / `GetExcludePackage()` (tun.go:150-156) | `include_package` / `exclude_package` | `addAllowedApplication` / `addDisallowedApplication`. The two **cannot be mixed**; this is an Android API rule | ignore (not available on iOS) |
| System HTTP proxy | `IsHTTPProxyEnabled()`, `GetHTTPProxyServer()`, `GetHTTPProxyServerPort()`, `GetHTTPProxyBypassDomain()`, `GetHTTPProxyMatchDomain()` (tun.go:158-179) | `platform.http_proxy{enabled, server, server_port, bypass_domain, match_domain}` (SB/option/tun_platform.go:5-14). It must point at an in-box `mixed`/`http` inbound on 127.0.0.1 | `setHttpProxy(ProxyInfo.buildDirectProxy(host, port, bypass))` (API 29+) | `NEProxySettings{httpEnabled, httpsEnabled, httpServer, httpsServer, exceptionList=bypass, matchDomains=match}` |

**Other constraints:**
- libbox **rejects** `include_uid`/`exclude_uid`/`include_android_user` under the platform path (LB/service.go:60-65). Do the same.
- With a platform interface, sing-box skips `BuildAndroidRules` (inbound.go:354), so per-app filtering is entirely the host's job.
- **Android caveat:** IPs from `route_address_set`/`route_exclude_address_set` rule-sets are **not** merged into the routes on Android with a platform interface (inbound.go:419). Only literal `route_exclude_address` reaches VpnService. On iOS they are merged.
- The libbox daemon also injects per-app lists at runtime through `OverrideOptions` (SB/daemon/instance.go:79-105). We can do the same thing directly in render.

**Our host interface.** Serialize all of the above into one JSON string (`OpenTun(tunConfigJSON) (fd int32, err error)`). This avoids gomobile iterator types; `RoutePrefixIterator` and `StringIterator` exist in libbox only because gomobile cannot pass slices (LB/iterator.go:3-15).

---

## 3. TUN fd hand-off, interface monitor, protect

### Android
1. The host builds the `VpnService.Builder`, calls `establish()` and returns `pfd.fd`. It keeps the `ParcelFileDescriptor` open and closes it on stop.
2. libbox then:
   - reads the interface name via the `TUNGETIFF` ioctl (LB/tun_name_linux.go:13-26);
   - calls `options.InterfaceMonitor.RegisterMyInterface(name)`;
   - **`dup`s the fd** (LB/service.go:74-83, LB/service_other.go:7-9) so Go owns its own copy;
   - sets `options.FileDescriptor = dupFd`;
   - calls `tun.New(*options)`, which with a non-zero fd just wraps it (ST/tun_linux.go:76-80).
3. **Protect:** `AutoDetectInterfaceControl(fd)` → `VpnService.protect(fd)`. sing-box applies it through `ProtectFunc()` (network.go:368-377) inside the default dialer, **but only when `networkManager.AutoDetectInterface()` is true** (SB/common/dialer/default.go:105-125). `autoDetectInterface` comes straight from `route.auto_detect_interface` (network.go:86). The config must therefore set it.
4. **xray engine:** xray's own outbound sockets also need protect. Register `internet.RegisterDialerController` / `RegisterListenerController` (xray-core@v1.260327.0 transport/internet/system_dialer.go:207, system_listener.go:189) with a control function that calls `host.Protect(fd)`. Excluding our own package with `addDisallowedApplication` also works as a backstop, but only in exclude mode, because the two lists cannot be mixed.

### iOS
1. The host calls `setTunnelNetworkSettings(...)`, **waits for its completion**, and then returns from `OpenTun`.
2. NetworkExtension does not hand over the fd. libbox finds it with `GetTunnelFileDescriptor()` (LB/tun_darwin.go:9-34):
   - scan fds 0..1023 with `getpeername`;
   - keep the one whose `*unix.SockaddrCtl` ID equals the control ID from `IoctlCtlInfo` for `"com.apple.net.utun_control"`.
   
   Upstream Swift calls this exported function and returns the fd from `openTun`. In our design, Go does the scan itself when the host returns `-1`.
3. Name lookup: `getsockopt(fd, SYSPROTO_CONTROL=2, UTUN_OPT_IFNAME=2)` (LB/tun_name_darwin.go:5-11). Then `dup`, then `tun.New`, which uses the fd and only runs `configure` (ST/tun_darwin.go:94-125).
4. **Protect:** none. With `UsePlatformAutoDetectInterfaceControl()==false` and `auto_detect_interface: true`, sing-box binds sockets to the monitor's default interface itself with `BindToInterfaceFunc` (network.go:347-364). I'm recommending false for iOS. Upstream's Swift client is not in the module cache, so I could not confirm what it returns.

### Default interface monitor (both platforms)
This mirrors LB/monitor.go.
- `Start()` calls `host.StartNetworkMonitor(listener)`. On Darwin, libbox also starts a `dnsinfo.Watcher` (LB/monitor.go:35-51); that is optional.
- The host calls `UpdateDefaultInterface(name, index, expensive, constrained)` whenever the default network changes, and with `index=-1` when there is none. In response the Go side:
  1. calls `networkManager.UpdateInterfaces()`, which calls back into `NetworkInterfaces()` (LB/monitor.go:136);
  2. resolves `InterfaceFinder().ByIndex(index)` (LB/monitor.go:153);
  3. fires the callbacks `cb(iface, 0)`, or `cb(nil, 0)` for `-1` (LB/monitor.go:141-171).
- **Android:** run that update on a fresh goroutine and wait for it. libbox does this behind `FixAndroidStack` for golang/go#68760 (LB/monitor.go:102-113, LB/setup.go:76-78).
- The host must deliver the current network **before `StartNetworkMonitor` returns**. Otherwise `DefaultInterface()` stays nil and dials fail with `ErrNoRoute` on iOS (network.go:359-361). Recommendation: on Android, request the default network with `NET_CAPABILITY_NOT_VPN` so the TUN is never reported.
- `NetworkInterfaces()` must set `net.FlagUp`, because entries without it are dropped (network.go:291-293). libbox converts raw `IFF_*` flags (LB/link_flags_unix.go:11-32).
- The other fields to fill: `Type` (`C.InterfaceType`: WIFI/Cellular/Ethernet/Other, LB/platform.go:96-101), `DNSServers`, `Gateways`, and `Expensive` (metered).

---

## 4. Context and registration

The sequence, per start, mirrors LB/config.go:29-41, LB/command_server.go:55-63 and SB/daemon/instance.go:85-131:

```go
ctx := context.Background()
ctx = filemanager.WithDefault(ctx, workDir, tempDir, os.Getuid(), os.Getgid()) // LB/config.go:39; creates the registry (sing service/context.go:60-68)
ctx = include.Context(ctx)            // SB/include/registry.go:48-50 → box.Context registers the protocol registries (SB/box.go:71-108)
service.MustRegister[adapter.PlatformInterface](ctx, platform) // panics if there is no registry (sing service/context.go:80-86)
opts, _ := json.UnmarshalExtendedContext[option.Options](ctx, cfg) // must use this ctx (LB/config.go:43-49)
ctx, cancel := context.WithCancel(ctx)
b, _ := box.New(box.Options{Context: ctx, Options: opts, PlatformLogWriter: platform})
b.Start()   // later: b.Close(); cancel(); debug.FreeOSMemory()
```

- `box.New` itself calls `service.ContextWithDefaultRegistry` (box.go:116), which is a no-op once a registry exists. It also reads the platform interface from the context (box.go:169).
- **Build a fresh context on every start**, or call `service.ExtendContext` as the daemon does (instance.go:88). `box.New` `MustRegister`s its managers into the shared registry, so reusing one would leak state between starts. The desktop code already calls `include.Context(parent)` on each create (go/core/singbox/singbox.go:60).
- **Logging:** when a platform interface is present, the default log writer is `io.Discard` (box.go:170-173). You must pass `PlatformLogWriter` (`WriteMessage(level log.Level, msg string)`, SB/log/platform.go:3-5).
- Setting `PlatformLogWriter` **forces a cache file** (box.go:154-156).

### Files and directories the app must provide
- **`workDir`**: the filemanager base. Relative paths resolve under it (sing `service/filemanager/default.go:38-41`).
  - `cache.db`: default relative path `"cache.db"`, resolved with `filemanager.BasePath` (SB/experimental/cachefile/cache.go:88,108). It holds the selected outbound, fakeip, and **remote rule-set contents**. Remote rule-sets are kept in cache.db, not as separate files (SB/route/rule/rule_set_remote.go:96-104,287-288).
  - Other relative paths also resolve here: a remote rule-set `initial_path` (rule_set_remote.go:68), local rule-set files, and the clash_api `external_ui` directory.
  - **Desktop bug for mobile:** it sets the cache path to `filepath.Join(os.TempDir(), ...)` (go/core/singbox/singbox.go:87). On Android, Go's `os.TempDir()` falls back to `/data/local/tmp`, which the app cannot write. Pass an explicit absolute path under `filesDir` (Android) or the App Group container (iOS), so the UI process can read it.
  - Close the old box before starting the new one: the cache file is bbolt and takes a file lock.
- **`tempDir`**: the filemanager temp directory. If empty it falls back to `os.TempDir()` (filemanager default.go:26-27). Pass `cacheDir` on Android and `NSTemporaryDirectory()` or the group tmp on iOS.
- libbox-only files we can drop:
  - `Setup` creates the working and temp dirs (LB/setup.go:115-122);
  - `working/CrashReport-<src>.log` via `debug.SetCrashOutput` and `working/crash_reports/` (LB/log.go:70-96);
  - `base/configuration.json` (LB/log.go:61);
  - `base/command.sock` (LB/command_server.go:146);
  - OOM and power report drafts in `working` (LB/oom_report.go:42, LB/power_report.go:25).
  
  Worth keeping: one `debug.SetCrashOutput(file)` call in our own Setup.

---

## 5. Build tags and flags, and iOS memory

### Upstream libbox build (SB/cmd/internal/build_libbox/main.go)
- **Shared tags** (:66,69): `with_gvisor, with_quic, with_wireguard, with_utls, with_naive_outbound, with_clash_api, with_usbip, with_openvpn, with_openconnect, badlinkname, tfogo_checklinkname0`, plus tailscale with `ts_omit_*`.
- **Darwin adds** (:67): `with_dhcp, grpcnotrace`.
- **iOS and tvOS:** `with_low_memory`, applied to every target except macOS through `-tags-not-macos=with_low_memory` (:70,206). That flag belongs to the **sagernet/gomobile fork v0.1.13** (SB/Makefile:273-274).
- **Flags** (:57-58,63; SB/cmd/internal/build_shared/flags.go:5-15):
  - `-trimpath -buildvcs=false`
  - `-ldflags "-X github.com/sagernet/sing-box/constant.Version=<v> -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid="`
- **Targets:**
  - Android: `-target android -androidapi 24 -javapkg=io.nekohasekai -libname=box` (:122-130,172-176). A legacy API-21 build drops `with_naive_outbound`.
  - Apple: `-target ios,iossimulator,... -iosversion=15.0` (:201-210).

### Recommended for our `go/mobile`
Start from the desktop tags `with_quic,with_wireguard,with_utls,with_gvisor,with_clash_api` (go/build_core.sh:16), then:
- add `badlinkname,tfogo_checklinkname0` and `-ldflags -checklinkname=0` to match upstream's linkname hacks;
- iOS: add `with_low_memory` and `with_dhcp`;
- skip `with_naive_outbound` (cgo/cronet), tailscale, usbip, openvpn and openconnect unless they are needed for parity.

`with_gvisor` is required for the default `mixed` stack (ST/stack.go:42-49) and for `includeAllNetworks`, which forces gvisor (ST/stack.go:43-44). Our module's go.mod says `go 1.26.0`; sing-box requires 1.25.5.

### iOS memory, as libbox does it
- `with_low_memory` sets `BufferSize = 16 KiB` and `UDPBufferSize = 8 KiB` (sing `common/buf/buffer_low_memory.go:1-13`) and enables `common.LowMemory`, which forces copy paths in `bufio/copy.go:126,389-443`.
- `ReloadSetupOptions` (LB/setup.go:90-113): on iOS with the OOM killer on and no explicit limit, it uses limit = `oomkiller.DefaultAppleNetworkExtensionMemoryLimit` = 50 MiB and `debug.SetGCPercent(50)` (SB/service/oomkiller/policy.go:14-15), then `debug.SetMemoryLimit(limit*4/5)`, which is 40 MiB. Otherwise it sets `SetMemoryLimit(math.MaxInt64)`.
- The daemon adds an `{"type":"oom-killer"}` service when none is configured (SB/daemon/instance.go:106-119; type constant at SB/constant/proxy.go:40). Under NE on iOS it picks the 50 MB policy automatically (policy.go:44-47). On Darwin with cgo it watches dispatch memory pressure and calls `FreeOSMemory` (SB/service/oomkiller/service_darwin.go, timer_darwin.go). It is registered by `include` (SB/include/oom_killer.go). **Recommendation:** add that service in render, iOS only.
- `runtime/debug.FreeOSMemory()` runs after every start, stop and reload (SB/daemon/started_service.go:269,299,307,313,348).
- gvisor TCP buffers stay at gVisor's defaults on iOS to limit pressure (ST/stack_gvisor_tcpbuf_ios.go:10-21).
- Our own budget: a sing-box + xray dual engine within 40 MiB is the risk to measure. Disable `clash_api` on iOS, and prefer `stack: system` or `mixed` unless `includeAllNetworks` is set.

---

## 6. Go sketch: `go/mobile/platform.go`

`tunName` and `findUtunFd` go in build-tagged files copied from LB/tun_name_linux.go:13-26, LB/tun_name_darwin.go:5-11 and LB/tun_darwin.go:9-34. Do not import libbox for them, since that pulls in the whole package.

```go
//go:build android || ios

package mobile

import (
	"context"
	"encoding/json"
	"net"
	"net/netip"
	"os"
	"runtime/debug"
	"sync"
	"syscall"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

// Host is implemented by Kotlin (VpnService) / Swift (NEPacketTunnelProvider).
type Host interface {
	OpenTun(tunConfigJSON string) (int32, error) // Android: pfd.fd; iOS: -1 after setTunnelNetworkSettings completed
	Protect(fd int32) error                      // Android: VpnService.protect
	StartNetworkMonitor(l *NetworkListener) error // must report the current default network before returning
	CloseNetworkMonitor() error
	InterfacesJSON() (string, error) // [{"name","index","mtu","type","addresses":["cidr"],"dns":[],"metered"}]
	FindOwnerJSON(proto int32, src string, sport int32, dst string, dport int32) (string, error) // {"uid":1,"packages":[]}
	WriteLog(level int32, message string)
}

type TunConfig struct {
	Inet4Address, Inet6Address   []string
	DNSMode                      string
	DNSServers                   []string
	MTU                          uint32
	AutoRoute, StrictRoute       bool
	Inet4RouteRange, Inet6RouteRange     []string // final routes, excludes already subtracted
	Inet4RouteExclude, Inet6RouteExclude []string // for iOS excludedRoutes
	IncludePackage, ExcludePackage       []string
	HTTPProxy                    *option.HTTPProxyOptions `json:",omitempty"`
}

type hostIface struct {
	Name      string   `json:"name"`
	Index     int32    `json:"index"`
	MTU       int32    `json:"mtu"`
	Type      int32    `json:"type"`
	Addresses []string `json:"addresses"`
	DNS       []string `json:"dns"`
	Metered   bool     `json:"metered"`
}

type Platform struct {
	host     Host
	nm       adapter.NetworkManager
	mu       sync.Mutex
	def      *control.Interface
	cbs      list.List[tun.DefaultInterfaceUpdateCallback]
	myIfs    []string
	tunAddrs []netip.Addr
}

var _ adapter.PlatformInterface = (*Platform)(nil)

func (p *Platform) Initialize(nm adapter.NetworkManager) error { p.nm = nm; return nil }
func (p *Platform) UsePlatformAutoDetectInterfaceControl() bool { return C.IsAndroid }
func (p *Platform) AutoDetectInterfaceControl(fd int) error     { return p.host.Protect(int32(fd)) }
func (p *Platform) UsePlatformInterface() bool                  { return true }
func (p *Platform) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func (p *Platform) OpenInterface(o *tun.Options, po option.TunPlatformOptions) (tun.Tun, error) {
	if len(o.IncludeUID) > 0 || len(o.ExcludeUID) > 0 || len(o.IncludeAndroidUser) > 0 {
		return nil, E.New("platform: uid/android_user options unsupported")
	}
	ranges, err := o.BuildAutoRouteRanges(true)
	if err != nil {
		return nil, err
	}
	dns, err := o.DNSServerAddress()
	if err != nil {
		return nil, err
	}
	str := func(v any) string { return v.(interface{ String() string }).String() }
	strs := func(n int, f func(int) any) []string {
		r := make([]string, n)
		for i := range r {
			r[i] = str(f(i))
		}
		return r
	}
	var r4, r6 []netip.Prefix
	for _, r := range ranges {
		if r.Addr().Is4() { r4 = append(r4, r) } else { r6 = append(r6, r) }
	}
	cfg := TunConfig{
		Inet4Address: strs(len(o.Inet4Address), func(i int) any { return o.Inet4Address[i] }),
		Inet6Address: strs(len(o.Inet6Address), func(i int) any { return o.Inet6Address[i] }),
		DNSMode: o.DNSModeOrDefault(), MTU: o.MTU, AutoRoute: o.AutoRoute, StrictRoute: o.StrictRoute,
		DNSServers:        strs(len(dns), func(i int) any { return dns[i] }),
		Inet4RouteRange:   strs(len(r4), func(i int) any { return r4[i] }),
		Inet6RouteRange:   strs(len(r6), func(i int) any { return r6[i] }),
		Inet4RouteExclude: strs(len(o.Inet4RouteExcludeAddress), func(i int) any { return o.Inet4RouteExcludeAddress[i] }),
		Inet6RouteExclude: strs(len(o.Inet6RouteExcludeAddress), func(i int) any { return o.Inet6RouteExcludeAddress[i] }),
		IncludePackage: o.IncludePackage, ExcludePackage: o.ExcludePackage, HTTPProxy: po.HTTPProxy,
	}
	js, _ := json.Marshal(cfg)
	fd, err := p.host.OpenTun(string(js))
	if err != nil {
		return nil, err
	}
	if fd < 0 && C.IsIos {
		fd = findUtunFd() // copy of LB/tun_darwin.go:9-34
	}
	if fd < 0 {
		return nil, E.New("tun fd not found")
	}
	if o.Name, err = tunName(fd); err != nil { // TUNGETIFF / UTUN_OPT_IFNAME
		return nil, E.Cause(err, "query tun name")
	}
	o.InterfaceMonitor.RegisterMyInterface(o.Name)
	if o.FileDescriptor, err = syscall.Dup(int(fd)); err != nil {
		return nil, E.Cause(err, "dup tun fd")
	}
	p.tunAddrs = nil
	for _, a := range append(append([]netip.Prefix{}, o.Inet4Address...), o.Inet6Address...) {
		p.tunAddrs = append(p.tunAddrs, a.Addr())
	}
	return tun.New(*o)
}

func (p *Platform) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (p *Platform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor { return (*monitor)(p) }
func (p *Platform) UsePlatformNetworkInterfaces() bool { return true }
func (p *Platform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	js, err := p.host.InterfacesJSON()
	if err != nil {
		return nil, err
	}
	var in []hostIface
	if err = json.Unmarshal([]byte(js), &in); err != nil {
		return nil, err
	}
	out := make([]adapter.NetworkInterface, 0, len(in))
	for _, h := range in {
		ni := adapter.NetworkInterface{Type: C.InterfaceType(h.Type), DNSServers: h.DNS, Expensive: h.Metered}
		ni.Interface = control.Interface{Index: int(h.Index), MTU: int(h.MTU), Name: h.Name,
			Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast} // FlagUp required: network.go:291-293
		for _, a := range h.Addresses {
			if pf, e := netip.ParsePrefix(a); e == nil {
				ni.Addresses = append(ni.Addresses, pf)
			}
		}
		out = append(out, ni)
	}
	return out, nil
}
func (p *Platform) UnderNetworkExtension() bool              { return C.IsIos }
func (p *Platform) NetworkExtensionIncludeAllNetworks() bool { return false }
func (p *Platform) ClearDNSCache()                           {}
func (p *Platform) RequestPermissionForWIFIState() error     { return nil }
func (p *Platform) ReadWIFIState(context.Context) adapter.WIFIState { return adapter.WIFIState{} }
func (p *Platform) UsePlatformWIFIMonitor() bool             { return false }
func (p *Platform) UsePlatformConnectionOwnerFinder() bool   { return C.IsAndroid }
func (p *Platform) FindConnectionOwner(r *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	js, err := p.host.FindOwnerJSON(r.IpProtocol, r.SourceAddress, r.SourcePort, r.DestinationAddress, r.DestinationPort)
	if err != nil {
		return nil, err
	}
	var o struct{ UID int32 `json:"uid"`; Packages []string `json:"packages"` }
	if err = json.Unmarshal([]byte(js), &o); err != nil {
		return nil, err
	}
	return &adapter.ConnectionOwner{UserId: o.UID, AndroidPackageNames: o.Packages}, nil
}
func (p *Platform) UsePlatformNotification() bool                      { return false }
func (p *Platform) SendNotification(*adapter.Notification) error      { return nil }
func (p *Platform) CancelNotification(string, int32) error            { return nil }
func (p *Platform) MyInterfaceAddress() []netip.Addr                   { return p.tunAddrs }
func (p *Platform) UsePlatformNeighborResolver() bool                  { return false }
func (p *Platform) StartNeighborMonitor(adapter.NeighborUpdateListener) error { return os.ErrInvalid }
func (p *Platform) CloseNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }
func (p *Platform) UsePlatformShell() bool  { return false }
func (p *Platform) CheckPlatformShell() error { return nil }
func (p *Platform) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}
func (p *Platform) LookupUser(string) (*adapter.PlatformUser, error) { return nil, os.ErrInvalid }
func (p *Platform) LookupSFTPServer() (string, error)                { return "", os.ErrInvalid }
func (p *Platform) ReadSystemSSHHostKey() ([]byte, error)            { return nil, os.ErrInvalid }
func (p *Platform) TailscaleHostname() string                        { return "" }
func (p *Platform) UsePlatformBridge() bool                          { return false }
func (p *Platform) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) { return nil, os.ErrInvalid }

// log.PlatformWriter (SB/log/platform.go:3-5)
func (p *Platform) WriteMessage(level log.Level, msg string) { p.host.WriteLog(int32(level), msg) }

// ---- default interface monitor (mirrors LB/monitor.go) ----
type monitor Platform

func (m *monitor) Start() error { return m.host.StartNetworkMonitor(&NetworkListener{(*Platform)(m)}) }
func (m *monitor) Close() error { return m.host.CloseNetworkMonitor() }
func (m *monitor) DefaultInterface() *control.Interface { m.mu.Lock(); defer m.mu.Unlock(); return m.def }
func (m *monitor) OverrideAndroidVPN() bool { return false }
func (m *monitor) AndroidVPNEnabled() bool  { return false }
func (m *monitor) RegisterCallback(cb tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.mu.Lock(); defer m.mu.Unlock(); return m.cbs.PushBack(cb)
}
func (m *monitor) UnregisterCallback(e *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.mu.Lock(); defer m.mu.Unlock(); m.cbs.Remove(e)
}
func (m *monitor) RegisterMyInterface(n string) { m.mu.Lock(); defer m.mu.Unlock(); m.myIfs = append(m.myIfs, n) }
func (m *monitor) MyInterfaces() []string       { m.mu.Lock(); defer m.mu.Unlock(); return m.myIfs }

// NetworkListener is handed to the host (gomobile-exported struct).
type NetworkListener struct{ p *Platform }

func (l *NetworkListener) UpdateDefaultInterface(name string, index int32, expensive, constrained bool) {
	done := make(chan struct{}) // fresh goroutine: golang/go#68760 (LB/monitor.go:102-113)
	go func() { defer close(done); l.update(index) }()
	<-done
}

func (l *NetworkListener) update(index int32) {
	p := l.p
	if p.nm == nil {
		return
	}
	_ = p.nm.UpdateInterfaces()
	var iface *control.Interface
	if index >= 0 {
		var err error
		if iface, err = p.nm.InterfaceFinder().ByIndex(int(index)); err != nil {
			return
		}
	}
	p.mu.Lock()
	p.def = iface
	cbs := p.cbs.Array()
	p.mu.Unlock()
	for _, cb := range cbs {
		cb(iface, 0)
	}
}

// ---- start/stop ----
type Instance struct {
	b      *box.Box
	cancel context.CancelFunc
}

func Start(host Host, configJSON, workDir, tempDir string) (*Instance, error) {
	p := &Platform{host: host}
	ctx := filemanager.WithDefault(context.Background(), workDir, tempDir, os.Getuid(), os.Getgid())
	ctx = include.Context(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, p)
	opts, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
	if err != nil {
		return nil, E.Cause(err, "decode config")
	}
	ctx, cancel := context.WithCancel(ctx)
	b, err := box.New(box.Options{Context: ctx, Options: opts, PlatformLogWriter: p})
	if err == nil {
		err = b.Start()
		if err != nil {
			b.Close()
		}
	}
	if err != nil {
		cancel()
		debug.FreeOSMemory()
		return nil, err
	}
	debug.FreeOSMemory()
	return &Instance{b, cancel}, nil
}

func (i *Instance) Close() error { i.cancel(); err := i.b.Close(); debug.FreeOSMemory(); return err }
```

**gomobile notes:**
- `Host`, `NetworkListener`, `Instance` and `Start` are the exported surface.
- Unexported Go types such as `monitor` are never seen by the host.
- Methods that take `netip`/`tun` types are not exportable through gomobile. `Platform` is only used from Go, so it may be skipped by gobind (it will warn), or you can make it unexported (`platform`).
- Keep `Platform` unexported in the real code. It is shown exported here only for readability.