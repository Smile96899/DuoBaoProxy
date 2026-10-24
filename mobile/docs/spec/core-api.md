# go/mobile spec: Go core API surface, mobile TUN changes and gomobile hazards

Line references are per file and relative to `C:/Users/Administrator/Desktop/h.proxy/go/` unless another path is given. sing-box paths are relative to `.../sing-box@v1.14.3/` and xray paths to `.../xray-core@v1.260327.0/`.

---

## 0. Architecture facts the port depends on

- **Engine registration happens in `package main`.** `core.Register(singbox.New())` and `core.Register(xray.New())` are called in `cmd/duobaoproxy-core/main.go:27-28`. go/mobile must do this itself, for example in `init()`.
- **The renderer map is private.** It is the unexported `rpc.renderers` (`rpc/server.go:33-36`). go/mobile must not import `rpc`, which pulls in grpc, `os.Exit` and `watchParent`. It should rebuild the map, or `render` should get a public registry.
- **QR code lives in `package main`** (`cmd/duobaoproxy-core/qr.go`). It must move to an importable package (e.g. `duobaoproxy/qr`) before gomobile can use it.
- **App version is set by ldflags.** `main.Version` (`cmd/duobaoproxy-core/main.go:24`) is filled from `build_core.sh:17`, which also sets `github.com/sagernet/sing-box/constant.Version`. The mobile build must pass the same `-X` flags, otherwise `Version()` reports the default.
- **Desktop build tags:** `with_quic,with_wireguard,with_utls,with_gvisor,with_clash_api` (`build_core.sh:16`), built with `CGO_ENABLED=0` (`:22`). gomobile always builds with cgo.
  - Upstream libbox adds `with_naive_outbound,badlinkname,tfogo_checklinkname0`, `-checklinkname=0` and `with_low_memory` on iOS (sing-box `cmd/internal/build_libbox/main.go:66-70,206`, `cmd/internal/build_shared/flags.go:5-10`).
  - SagerNet builds with its own gomobile fork. Flags like `-tags-not-macos` and `-libname` are not in upstream gomobile.
- **The GUI does several steps itself that the mobile host must repeat:**
  - It always renders a one-node chain: `[store.nodeJson(selectedId)]` (`app/qml/App.qml:107`, `qml/Tests.qml:165`).
  - It applies proxy mode in QML before rendering (`app/qml/App.qml:72-91`):
    - `global` clears the proxy/direct lists and custom_rules and sets final=proxy.
    - `direct` sets final=bypass.
    - `route_parts` = `rules` / `apps` / `both` clears `apps` or the rule lists.
    - `tun.enabled` is set from the UI mode.
  - **Recommendation:** port this into a Go helper (§3) so the logic stays identical on every platform.
- **FullTest is not called by the GUI.** It only uses TcpPing, UrlTest and Probe (`app/src/core_client.cpp:178-230`, `qml/Tests.qml:141-191`).

---

## 1. RPCs

The service is defined at `rpc/gen/libcore.proto:8-25`. Auth is the metadata key `duobao_auth`, compared in constant time (`rpc/auth.go:14,18-28`), and is not needed in-process. An empty `core_id` means `"sing-box"` (`rpc/server.go:62-67`).

All RPCs return gRPC error = nil. Errors travel in a body field.

| RPC | Inputs | Outputs | Semantics | Go calls |
|---|---|---|---|---|
| **Exit** | — | — | Stops the running core, then `os.Exit(0)` (`server.go:69-77`). | `core.Core.Stop`. **Do not expose on mobile.** |
| **ListCores** | — | `cores[] {id, version, protocols[], running}` | Lists every registered core, sorted by id (`server.go:79-90`, `core/core.go:92-101`). | `core.List()`, `Core.ID/Version/Protocols/Running`. sing-box protocols: `core/singbox/singbox.go:36-38`. xray protocols: `core/xray/xray.go:74-76`. |
| **Start** | `core_config` (native JSON), `stats_outbounds[]`, `core_id`, `asset_dir`. `enable_tracked_connections` is unused (`proto:49`). | `error` | `core.Get(id)`. Fails if **any** core is running: `"%s is already running; stop it first"` (`server.go:102-104`). Then `c.Start(context.Background(), StartOptions{Config, StatsOutbounds, LogWriter: s.logFn, AssetDir})` (`:105-110`) and records it as running. | sing-box `create()` (`core/singbox/singbox.go:59-111`); xray `Start` (`core/xray/xray.go:159-177`). |
| **Stop** | `core_id` (optional) | `error` | No-op if nothing is running. Error if `core_id` does not match the running core (`server.go:124-126`). Otherwise `Stop()` and clear (`:118-130`). | sing-box: cancel + `box.Close` (`core/singbox/singbox.go:127-137`). xray: `instance.Close` (`core/xray/xray.go:179-188`). |
| **Status** | — | `running_core_id` (empty if none) | `server.go:132-140` | — |
| **Test** | `mode`, `timeout` (ms; ≤0 becomes 5 s, `server.go:163-166`), plus mode-specific fields | `error`, `ms`, `full_report` | Dispatch is at `server.go:167-206`; the modes are below. | |
| — TcpPing | `address` (host:port) | `ms` | Direct TCP connect time (no proxy). | `probe.TCPPing` (`probe/probe.go:57-68`) |
| — UrlTest | `config` (LoadConfigReq; non-empty `core_config` means a throw-away instance of `config.core_id`, otherwise the running core), `url` | `ms` | One GET through the dialer. A status ≥400 is an error (`probe.go:37-54`). Dialer selection is `dialerFor` (`server.go:144-159`). | `Core.Dialer(ctx, cfg)`, then `probe.URLTest` |
| — FullTest | `url`, `full_latency`, `full_udp_latency`, `full_speed`, `full_speed_url`, `full_speed_timeout` (**seconds**), `full_in_out`, `in_address` | `ms` = latency, `full_report` = `"Latency: Nms / UDPLatency: … / Speed: x.xxMiB/s / In: ip / Out: ip"` | `server.go:188-200`, `probe.go:131-171`. UDP latency is hard-coded to 3 s, 8.8.8.8:53, `www.google.com`. Speed timeout defaults to 30 s. "In" resolves `in_address` locally. "Out" reads `ip=` from cloudflare trace. | `probe.FullTest` |
| — Probe | `probe`, `address`, `url`, `count`, `config` | `ms` and/or `full_report` JSON | See §2 (`server.go:210-294`). | |
| **QueryStats** | `tag`, `direct` (`"downlink"`, anything else means uplink) | `traffic` (cumulative bytes) | Returns 0 if not running **and swallows every error** (`server.go:296-314`). The GUI polls the **first** stats tag (the exit, `"proxy"`) twice per tick and computes rates itself (`app/src/core_client.cpp:268-298`). | `Core.Stats(tag)`. sing-box uses a v2ray stats tracker attached via `Router().AppendTracker` with no gRPC listener (`core/singbox/singbox.go:100-105,171-189`). xray injects the `stats` app + `policy.system.statsOutbound{Up,Down}link` for **all** outbounds (`core/xray/xray.go:81-114,226-244`). Counter names: `outbound>>>TAG>>>traffic>>>uplink|downlink`. |
| **ImportNodes** | `text` (links, base64 blob, or Clash YAML) | `nodes_json[]` (one `node.Node` JSON each), `errors[]` | `server.go:316-328`. `node.Import` (`node/import.go:22-54`), in order: (1) trim; (2) if there is no `://`, try base64 (`decodeB64`, `node/link.go:45`); (3) if the text contains `proxies:`, parse it as Clash/mihomo (`importClash`, `:145-160`; types ss/ss+shadow-tls/obfs/v2ray-plugin, socks5, http, trojan, vless (+reality), vmess, wireguard, hysteria, hysteria2, tuic; invalid nodes are silently dropped, `:355-357`); (4) otherwise one link per line, skipping `#` lines, via `ParseLink` (`node/link.go:17-41`; schemes socks/socks4/socks4a/socks5/http/https, ss, vmess, vless, trojan, naive+https/naive+quic, hysteria2/hy2, hysteria, tuic). Per-line error format: `"<first 40 chars>…: err"`. | `node.Import`, `json.Marshal` |
| **ShareLink** | `node_json`, `v2rayn_vmess` | `link`, `error` | Unmarshal. For vmess with `v2rayn_vmess` set, `VMessLinkV2RayN()` (`node/link.go:510`). Otherwise `n.ShareLink()` (`node/link.go:358-378`). An empty result gives the error `"<protocol> 没有通用的分享链接格式"` (wireguard, ssh, anytls and shadowtls have no link) (`server.go:330-343`). | `node.Node.ShareLink`, `VMessLinkV2RayN` |
| **RenderConfig** | `core_id`, `chain_json[]` (client outwards; the last is the exit), `settings_json` (`render.Settings`), `mode` (Full=0, Test=1, Export=2) | `config`, `stats_tags[]`, `error` | Unmarshal each node (error prefix `"node: "`). An empty chain gives `"empty chain"`. Renderer lookup; settings unmarshal (error prefix `"settings: "`). Then `r.Render(render.Request{Chain, Settings, Mode})` (`server.go:345-373`). Tags: exit=`"proxy"`, others `hop-N` (`render/render.go:189-194`). `StatsTags` is ordered exit first. | `render/singbox.Renderer.Render`, `render/xray.Renderer.Render` |

The process entry is `rpc.Run`: it listens on `127.0.0.1:<port>` (default 19810), reads the token from a flag or stdin, and runs a parent watchdog (`server.go:377-428`). None of this applies to mobile.

**Logging.** Log lines go `LogWriter`, then `logFile.WriteLine`: a timestamp is added, the line is echoed to stderr (which the GUI reads), and it is written to `./core.log` capped at 50 KiB (`cmd/duobaoproxy-core/logfile.go:30-40`, `main.go:44-52`).
- **sing-box:** a `PlatformLogWriter` gets the message only, and the box's own output goes to `os.DevNull` (`core/singbox/singbox.go:40-46,74-79`).
- **xray:** registers a Console log handler globally in `init()` (`core/xray/xray.go:140-157`). The sink is a process-global `atomic.Value` that is never cleared.

---

## 2. Probe kinds (`TestMode_Probe`, `rpc/server.go:210-294`)

Every probe reads the `timeout` field in milliseconds (default 5 s). Report JSON goes in `full_report`.

| `probe` | Path | Inputs | Output | Implementation |
|---|---|---|---|---|
| `icmp` | **direct** | `address` = host or host:port (port stripped, `server.go:221-224`) | `ms` (≥1) | `probe.ICMPPing` (`probe/icmp.go:10-20`): resolves IPv4 only and sends one echo. Windows uses iphlpapi `IcmpSendEcho` (`icmp_windows.go`). Elsewhere it uses an unprivileged `udp4` ICMP socket (`icmp_other.go:17-44`). Android allows this through the default `ping_group_range`. darwin/ios header stripping is handled in `x/net/icmp/endpoint.go:54`. |
| `loss` | **direct** | `address` host:port, `count` (≤0 becomes 10) | `ms` = avg; report `{"avg","min","max","jitter","loss"(0..1)}` | `probe.TCPPingStats` (`probe/extra.go:48-88`): sequential TCP connects 150 ms apart. Jitter is the mean absolute difference between consecutive samples. |
| `udp` | node | `address` = DNS server (default `8.8.8.8:53`), `url` = name (default `www.google.com`) | `ms` (≥1) | `probe.UDPLatencyTo` (`probe/probe.go:87-110`): a hand-built A query sent over `Dialer.ListenPacket`; time to the first reply. |
| `download` | node | `url` | `{"bps": float}` | `probe.DownloadSpeed` (`extra.go:94-114`): the timeout bounds the whole transfer, and bytes received before a timeout still count. |
| `upload` | node | `url`, `count` = MiB (≤0 becomes 10) | `{"bps": float}` | `probe.UploadSpeed` (`extra.go:136-161`): POSTs zero bytes; uses bytes sent / elapsed time. |
| `geo` | node | `url` = trace URL (default `https://www.cloudflare.com/cdn-cgi/trace`) | `{"ip","cc"}` | `probe.ExitGeo` (`extra.go:171-191`) |
| `media` | node | `url` = comma list from `netflix,disney,youtube` (empty means all) | `{"netflix":{"ok","region","note"},…}` | `probe.MediaUnlock` (`extra.go:343-345`); checks at `:204-252`. Run concurrently (`runAll`, `:326-341`). |
| `ai` | node | `url` = comma list from `chatgpt,claude,gemini` | same shape | `probe.AIUnlock` (`extra.go:347-349`); checks at `:267-307` (regions via `traceLoc`). |
| `dnsleak` | node, plus **local resolver** | — | `{"leak","local","servers":[{"ip","cc","country","asn"}]}` | `probe.DNSLeakTest` (`extra.go:371-412`). It gets an id from bash.ws, then resolves `1..6.<id>.bash.ws` **both with `net.DefaultResolver` locally** (`:380`) and by TCP-dialing them through the node (`:382`). It then reads the bash.ws JSON. `local` is the Cloudflare `loc=` fetched via `core.SystemDialer` (`:415-417`). `leak` is true when a resolver's country equals `local`. |

Unknown names return `unknown probe "<name>"`. On error only `error` is set (`ms`/report are not). The GUI maps ms for icmp/udp and treats `loss>=1` as a timeout (`qml/Tests.qml:184-190`). GUI argument conventions are in `qml/Tests.qml:141-151`.

`HTTPClient` (`probe/probe.go:23-32`) disables keep-alives, uses TLS ≥1.2 and HTTP/2, and sends a Windows Chrome User-Agent (`extra.go:22`; consider a mobile UA). Body reads are capped at 512 KiB (`extra.go:42`).

**Mobile caveat.** The "direct" probes (`icmp`, `loss`, TcpPing, FullTest "In", the local half of `dnsleak`, `localCountry`) and `probe.TCPPing` use a plain `net.Dialer`/`net.DefaultResolver`.
- **Android:** if the app's own UID is routed into the VPN, they measure the TUN stack instead of the server. Fixes: exclude the own package (`VpnService.Builder.addDisallowedApplication(self)`), or give these dialers a `Control` that calls `VpnService.protect(fd)`.
- **iOS:** the containing app's sockets go through the tunnel. Only the extension's sockets bypass it. Run direct probes in the extension (via provider messages) or accept that the results are tunnelled.

---

## 3. Proposed gomobile API (`package mobile`)

Constraints:
- Only basic types, `string`, `[]byte`, `error`, exported structs of those, and host-implemented interfaces. No `[]string`, no maps. Lists travel as JSON strings.
- All calls are synchronous. The host must call them off the UI thread. Go handles concurrent calls.

**Prerequisite refactor.** Move the RPC bodies into a transport-free package (e.g. `duobaoproxy/api`) that both `rpc/server.go` and `mobile` call. This keeps the semantics 1:1 instead of copying them.

```go
package mobile

// ---- environment / lifecycle ------------------------------------------
type SetupOptions struct {
    BasePath    string // Android filesDir / iOS App Group container
    WorkingPath string // os.Chdir target (clash external_ui, relative paths)
    TempPath    string // sets TMPDIR (Go's os.TempDir() is /data/local/tmp on Android without it)
    AssetPath   string // geoip.dat / geosite.dat for xray (== LoadConfigReq.asset_dir)
    CacheFile   string // sing-box cache.db path (replaces os.TempDir default, core/singbox/singbox.go:86-88)
    Debug       bool   // == rpc.Debug
}
func Setup(o *SetupOptions) error   // core.Register(singbox.New()); core.Register(xray.New()); env; chdir
func Version() string                // app version (-X duobaoproxy/mobile.Version)
func ListCoresJSON() string          // == ListCores: [{"id","version","protocols":[...],"running"}]
func Supports(coreID, protocol string) bool // == render.Renderer.Supports (render/singbox:24, render/xray:29)

// ---- logging -----------------------------------------------------------
type LogHandler interface{ WriteLog(line string) }
func SetLogHandler(h LogHandler)     // becomes StartOptions.LogWriter (rpc NewServer logFn)

// ---- nodes -------------------------------------------------------------
type ImportResult struct {
    NodesJSON  string // JSON array of node.Node   (== nodes_json[])
    ErrorsJSON string // JSON array of strings     (== errors[])
    Count      int32
}
func ImportNodes(text string) *ImportResult                  // == ImportNodes → node.Import
func ShareLink(nodeJSON string, v2raynVMess bool) (string, error) // == ShareLink
func ValidateNode(nodeJSON string) (string, error)           // node.Validate (normalises WG CIDRs, node/node.go:219); returns normalised JSON — for the node editor

// ---- render ------------------------------------------------------------
type RenderResult struct {
    Config        string // == config
    StatsTagsJSON string // == stats_tags[] (exit first)
    FrontConfig   string // sing-box TUN front config when coreID=="xray" && tun.enabled (new, §4.3); "" otherwise
}
// mode: 0 Full, 1 Test, 2 Export (render.Mode, render/render.go:18-25)
func RenderConfig(coreID, chainJSON /* JSON array of nodes */, settingsJSON string, mode int32) (*RenderResult, error)
// Port of app/qml/App.qml:72-91 so all shells share it.
func ApplyUIModes(settingsJSON, proxyMode /*global|rule|direct*/, routeParts /*rules|apps|both*/ string, tun bool) (string, error)

// ---- platform (host-implemented; required for TUN on Android/iOS) ------
type Platform interface {
    OpenTun(optionsJSON string) (int32, error)      // returns tun fd (Android: VpnService.Builder.establish(); iOS: utun fd after setTunnelNetworkSettings)
    AutoDetectInterfaceControl(fd int32) error      // Android: VpnService.protect(fd); iOS: no-op
    UseProcFS() bool
    FindConnectionOwner(ipProto int32, srcIP string, srcPort int32, dstIP string, dstPort int32) (*ConnectionOwner, error)
    StartDefaultInterfaceMonitor(l InterfaceListener) error
    CloseDefaultInterfaceMonitor(l InterfaceListener) error
    InterfacesJSON() (string, error)                // replaces net.Interfaces() (fails on Android 11+)
    UnderNetworkExtension() bool                    // true on iOS
    IncludeAllNetworks() bool
    ClearDNSCache()
}
type ConnectionOwner struct{ UserID int32; UserName, ProcessPath, AndroidPackagesJSON string }
type InterfaceListener interface{ UpdateDefaultInterface(name string, index int32, expensive, constrained bool) } // Go-implemented, host calls it

// ---- run ---------------------------------------------------------------
type StartOptions struct {
    CoreID        string // "" = sing-box
    Config        string // == core_config
    StatsTagsJSON string // == stats_outbounds[]
    AssetDir      string // == asset_dir (xray)
    FrontConfig   string // sing-box TUN front for xray (RenderResult.FrontConfig)
}
func Start(o *StartOptions, p Platform) error  // == Start (+ registers p as adapter.PlatformInterface in the sing-box ctx)
func Stop() error                              // == Stop{core_id:""}
func Status() string                           // == Status.running_core_id

// ---- stats -------------------------------------------------------------
type Traffic struct{ Uplink, Downlink int64 }
func QueryStats(tag string) *Traffic           // == 2× QueryStats; zero on any error (same as server.go:304-307)

// ---- tests -------------------------------------------------------------
type TestResult struct{ Ms int32; Report string } // Report == full_report
func TCPPing(address string, timeoutMs int32) (int32, error)                             // TestMode_TcpPing
func URLTest(coreID, config, url string, timeoutMs int32) (int32, error)                 // TestMode_UrlTest; config "" = running core
func Probe(name, coreID, config, address, url string, count, timeoutMs int32) (*TestResult, error) // TestMode_Probe (§2)
func FullTestJSON(coreID, config, optionsJSON string, timeoutMs int32) (*TestResult, error)    // TestMode_FullTest (optional, unused by GUI)
type Protector interface{ Protect(fd int32) bool }
func SetProtector(p Protector) // installs Control on probe direct dialers + xray internet.RegisterDialerController

// ---- QR (moved from cmd/duobaoproxy-core/qr.go) ----------------------
func DecodeQR(image []byte /* PNG/JPEG */) (string /* texts, '\n'-separated */, error) // decodeQR :19-50 (multi reader, single-reader fallback, TRY_HARDER)
func EncodeQR(text string, size int32 /* desktop: 480 */) ([]byte /* PNG */, error)    // encodeQR :54-95 (EC level M, UTF-8, margin 2)
```

How these map to existing behaviour:
- **`Start`:** calls `c.Start(ctx, core.StartOptions{...})`.
  - For sing-box, `ctx` must carry the platform: `service.ContextWith[adapter.PlatformInterface](context.Background(), wrapper)`. This works without changing `create()`, because `include.Context(parent)` keeps the registry and `ContextWithDefaultRegistry` is a no-op when one exists (sing `service/context.go:13-18`).
  - Use a **fresh** ctx per start, because the registry is a shared mutable map.
  - The wrapper is a copy of libbox's unexported `platformInterfaceWrapper` (sing-box `experimental/libbox/service.go:29-360`). Its `OpenInterface` (`:59-88`) calls host `OpenTun`, then `getTunnelName`, `dup(fd)`, `tun.New`.
  - **Do not bind libbox itself.** In 1.14 it is a daemon plus gRPC command server (`experimental/libbox/command_server.go:54-80`).
- **Test calls:** pass the same platform ctx to `Core.Dialer(ctx, cfg)` so that throw-away sing-box test instances also protect their sockets.
- **Running-core state** belongs in the package (a mutex plus `running core.Core`), as in `rpc.Server` (`server.go:40-46`).
- **iOS:** status, stats and logs live in the extension process. The app must relay them through `NETunnelProviderSession.sendProviderMessage`. Render, import, tests and QR can run in the app process.

---

## 4. Desktop/Windows-specific spots in the renderers and what mobile needs

Add a renderer selector to `render.Settings`, e.g. `"platform": "android"|"ios"|""`. It is set by go/mobile and changes the output as described below.

### 4.1 `render/singbox/singbox.go`

| Spot | Current | Mobile change |
|---|---|---|
| TUN inbound `interface_name` | `set.TUN.InterfaceName`, default `"duobao-tun"` (`:530`, `render/render.go:128-130`) | Omit it. With a platform, the name comes from the fd (`libbox/service.go:71-74`). |
| `auto_route: true` | always (`:531`) | Keep. With a platform, sing-box computes the route ranges (`BuildAutoRouteRanges`, libbox `service.go:66`) and hands them to `OpenTun`. The host must add them to `VpnService.Builder.addRoute` or `NEIPv4Settings.includedRoutes`/`excludedRoutes`. |
| `strict_route` | from settings (`:531`) | On mobile this is only passed through to the host (`TunOptions.GetStrictRoute`, libbox `tun.go:25`). There are no Windows firewall semantics. Default it to false, or hide it. |
| `stack` | default `gvisor` (`render.go:122-124`) | Android: `mixed` or `gvisor`. iOS: measure memory (`gvisor` is heavy; libbox builds with `with_low_memory` on iOS). |
| `mtu` | default 9000 (`render.go:125-127`) | iOS NE: ≤4064 (sing-box `protocol/tun/inbound.go:107-114`). Android: 9000 is fine. Or emit 0 and let sing-box choose. |
| TUN `address` | `172.19.0.1/28`, plus `fdfe:dcba:9876::1/126` with IPv6 (`:533-537`) | Fine. The host applies them (Builder.addAddress / NEIPv4Settings). DNS server address = the TUN gateway; the host sets `addDnsServer`. |
| Per-app rules | `appRules` emits `process_name` (no separator) or `process_path` (`:742-773`). `TUN.RuleProcess` becomes `process_name` (`:684-686`). Comment: "Matching is case-insensitive on Windows" (`render.go:157-158`). | **Android:** `AppRule.Process` = package name, so emit `package_name`. For `bypass` apps, prefer TUN `exclude_package`; for an allow-list (`RuleWhite`), use `include_package` (sing-box `option/tun.go:44-45`), applied by the host via `addDisallowedApplication`/`addAllowedApplication`. `block` stays as a `package_name` + `reject` rule. Requires `FindConnectionOwner` (getConnectionOwnerUid, API 29+). **iOS:** drop the per-app rules (no owner lookup inside NE). |
| `auto_detect_interface: true` | `:694`; removed only in Export (`:713-715`) | Keep, but it **requires** the platform interface. `AutoDetectInterfaceControl` becomes `protect()`, and the default interface monitor comes from ConnectivityManager. Netlink route monitoring is blocked for apps on Android 11+. |
| DNS `local` | `UnderlyingDNS` default `"local"` (`render.go:143-145`). Used for `dns-local` (`:605`), every server's `domain_resolver` (`:403-405`) and `default_domain_resolver` (`:695`). | On Android, `local` goes through the system resolver, which follows the VPN's DNS back into the TUN, so lookups of the node's own server can loop. Either implement libbox-style `LocalDNSTransport` bound to the underlying network, or default mobile to an IP resolver (e.g. `223.5.5.5`). `dhcp://` (`:363-368`) needs the platform interface too. |
| DNS hijack | the `protocol:dns → hijack-dns` route rule (`:663`) relies on sniffing; the `sniff` action is added only when `Inbound.Sniff != 0` (`:658-662`) | In TUN mode on mobile, always emit `sniff` (or add a `port:53` hijack rule). Otherwise DNS sent to the TUN gateway is not hijacked. |
| Mixed inbound on `127.0.0.1:port` | `:520-526` | On Android any app can reach loopback. In VPN mode emit it only with random credentials, or `Port=0`. Optional `platform.http_proxy` (VpnService `setHttpProxy`) can point to it. |
| Clash API | `127.0.0.1:<port>`, `external_ui: "dashboard"` (relative path) (`:718-724`) | Disable on mobile (unneeded attack surface, and the relative path depends on the working dir). |
| Cache file | not rendered. The core injects `experimental.cache_file.path = os.TempDir()/duobaoproxy-singbox-cache.db` whenever a logFn is set (`core/singbox/singbox.go:74-90`) | Make the path configurable (`SetupOptions.CacheFile`). The Android TempDir fallback is unwritable; on iOS use the App Group container. Throw-away test instances get no cache file (logFn nil, `:157`); keep it that way (bbolt lock). |
| Remote rule sets | `raw.githubusercontent.com/SagerNet/sing-geo{site,ip}/rule-set/*.srs`, `download_detour: direct`, `update_interval: 7d` (`:416-431`). `RuleSetDir` is stored in `ruleBuilder.dir` (`:599`) but **never read**. | Without the cache file, rules are fetched again on every start. Better: ship the `.srs` files and emit `type:"local", path:<AssetPath>/…`, or set the cache file. The `direct` download needs a protected socket (platform). |
| Naive | `Outbound` renders naive (`:301-316`), but `supported` lacks `node.Naive` (`:19-22`) | Unreachable today on every platform; desktop has the same gap. |
| Export mode | strips only `auto_detect_interface` (`:713-715`) | Also strip the cache path and platform-only fields on mobile. |

### 4.2 `render/xray/xray.go`

| Spot | Current | Mobile change |
|---|---|---|
| TUN | **rejects** `TUN.Enabled` in Full mode: `"xray has no TUN inbound; use sing-box for VPN mode"` (`:326-328`) | See §4.3. |
| Inbound | `mixed` on `Inbound.Address:Port` (`:343-362`). Sniffing (`destOverride http,tls,quic`, `routeOnly` = SniffForRouting) only on it. DNS port-53 UDP from `mixed-in` goes to `dns-out` (`:464`). | Becomes the loopback bridge target for the sing-box front. Force a random port with auth, and force sniffing on (so domain rules work when the front passes IPs) or have the front use fake-ip. |
| DNS | servers = remote/direct plus `"localhost"` (system) (`:420-452`) | `localhost` on Android has the same loop risk as §4.1. Use an IP resolver. |
| geo rules | emits `geosite:`/`geoip:` strings (`:274-303`). Needs `geoip.dat`/`geosite.dat` via `XRAY_LOCATION_ASSET` (`core/xray/xray.go:168-170`). GUI passes `sys.appDir()` (`app/qml/App.qml:235`). `Settings.GeoIPPath`/`GeositePath` (`render.go:105-106`) are unused. | Ship the .dat files in the app and copy them to filesDir or the App Group container. **iOS: geosite.dat is loaded into memory**, a serious risk under the ~50 MB NE limit. |
| `pinnedCert` | for `insecure` TLS, does a live `tls.DialWithDialer` during **render** (`:505-528`) | Render runs in the app process. On Android that socket may go into the VPN unless the app is excluded or protected. On iOS it goes through the tunnel. |
| Chain | `sockopt.dialerProxy` (`:379-386`); the exit is reordered first (`:398-400`) | No change. |

### 4.3 xray + TUN on mobile (new code)

**Option A (recommended).** When `coreID=="xray" && tun.enabled`:
- Render xray with TUN off and a loopback mixed inbound (random port, credentials).
- Also render a **sing-box front** config: tun → sniff → hijack-dns → (fakeip or remote DNS via socks) → final `socks` outbound to `127.0.0.1:<port>` with the credentials, keeping `direct` for bypass/package rules. This needs a new function in `render/singbox`.
- `Start` runs both: sing-box with the platform first, then xray, all in one process.
- Stats tags still come from xray.
- xray's outbound sockets must be protected on Android: `internet.RegisterDialerController` (xray `transport/internet/system_dialer.go:207`) with `VpnService.protect`, or exclude the own package.
- Today `core.Start` refuses a second running core (`rpc/server.go:102-104`). Mobile needs a combined start.

**Option B.** xray v1.260327 does ship `proxy/tun` and reads the fd from env `xray.tun.fd` (xray `common/platform/platform.go:26`, `proxy/tun/tun_android.go:29`, `tun_darwin.go:51-66`).
- It is **not linked** today (import list `core/xray/xray.go:18-59` lacks `proxy/tun`; also missing are `proxy/hysteria` and `proxy/shadowsocks_2022`).
- It has no DNS hijack, fake-ip or route config (`proxy/tun/README.md`), and the fd is passed through a process-global env var.
- Usable, but loses parity with the sing-box TUN features.

---

## 5. What would break under gomobile

1. **Code in `package main`** cannot be imported: QR (`cmd/duobaoproxy-core/qr.go`), core registration (`main.go:27-28`), `Version` (`main.go:24`), the log file at the relative path `./core.log` (`main.go:44`).
2. **`rpc` package:** `os.Exit` in `Exit` (`server.go:75`) and `Run` (`:396`), `log.Fatal*`, `watchParent` with `parent.Wait()`/`Signal(0)` (`:413-428`), and stdin token reading. Do not import it. The renderer map is unexported (`:33-36`).
3. **`os.TempDir()`** for the sing-box cache (`core/singbox/singbox.go:87`). gomobile bind does not set TMPDIR (only `x/mobile/app` touches it), so Android falls back to `/data/local/tmp`, which apps cannot write. Box start then fails whenever logging is on.
4. **No platform interface → no TUN.** Without `adapter.PlatformInterface` in the ctx:
   - On Android the TUN inbound tries to open `/dev/net/tun` itself and `BuildAndroidRules` reads the package list (sing-box `protocol/tun/inbound.go:354-356,440-441`). Both need root.
   - `auto_detect_interface` and `local` DNS misbehave inside the VPN.
   - `process_*`/`package_name` rules need `FindConnectionOwner`.
   - Go's `net.Interfaces()` fails on Android 11+ (netlink restriction); use host-provided interfaces.
5. **Process-global state in xray:** `os.Setenv("XRAY_LOCATION_ASSET")` (`core/xray/xray.go:169`), the `logSink` atomic set in `init()` and never cleared (`:140-157`), and `RegisterDialerController` (global, append-only). Call these once in `Setup`.
6. **Windows-only files** are build-tagged and safe: `probe/icmp_windows.go` (`//go:build windows`) and `probe/icmp_other.go` (`!windows`). `cmd/updater` uses `os/exec`, `syscall` and `unsafe` (`msgbox_windows.go`, `main.go`, `launcher_linux.go`) and is irrelevant; do not bind it. No `os/exec` exists in node, render, probe or core.
7. **cgo:** our tree has none. In sing-box, cgo is used in `common/certificate/system_android.go` and `common/jni/jni_android.go` (Android; an Android certificate store is always created, `box.go` near line 190) and `common/certificate/store_darwin.go` / `common/tls/apple_client_platform.go` (darwin). These compile under gomobile's cgo, but they diverge from the desktop's `CGO_ENABLED=0`, so test TLS validation on device. With cgo, Go uses the cgo resolver on android and ios (`GOROOT/src/net/conf.go:180-186`).
8. **Linkname:** if the tag set grows to match libbox (`badlinkname`, naive), add `-ldflags=-checklinkname=0` (`build_shared/flags.go:9`).
9. **Non-bindable types:** `core.Core.Protocols() []string`, `core.Dialer` (`net.Conn`), `render.Settings` (nested anonymous structs, `[]string`), `node.Node` (pointers, `json.RawMessage`), `ImportResult.Errors []string`, `probe` maps. Everything crosses as JSON strings, as proposed in §3.
10. **iOS memory:** both engines, gvisor, xray geosite and remote rule sets in one NE process. The tags `with_low_memory` and `grpcnotrace` (libbox darwin tags, `build_libbox/main.go:67,70`) are the upstream mitigations. Linking `google.golang.org/grpc` (needed by xray and the sing-box v2ray stats) is unavoidable.