# DuoBao Proxy mobile port — design decisions

This is the contract every implementer follows. The research it is based on is in `mobile/docs/spec/*.md` (cite those for details; this file wins on any conflict).

## 0. Goals

- Native Android (Kotlin + Jetpack Compose) and native iOS (SwiftUI + Packet Tunnel), feature parity with the desktop app where the OS allows it (platform limits: `spec/critic.md` §C).
- Both engines (sing-box + xray). Shared Go code: `go/node`, `go/render`, `go/probe`, `go/core` — exposed to the hosts through a new gomobile package `go/mobile`. No gRPC on mobile.
- Size first: arm64-v8a only for release (x86_64 added only to debug builds for the emulator), R8 + resource shrinking, no ML Kit, geo data not bundled in the APK.
- Data stays JSON-compatible with desktop `data.json` (`spec/data-schema.md`).

## 1. Go refactor (desktop must keep working, all existing tests must pass)

- **`go/api`** (new, transport-free): the bodies of every RPC in `go/rpc/server.go` move here — `ListCores`, `ImportNodes`, `ShareLink`, `RenderConfig`, `Start`, `Stop`, `Status`, `Test` (TcpPing / UrlTest / FullTest / Probe), `QueryStats` — plus the renderer registry (public). `go/rpc/server.go` becomes a thin gRPC adapter over `go/api`. Semantics stay 1:1 (`spec/core-api.md` §1–§2). `api.Start` accepts a `context.Context` that the caller may pre-populate (the mobile platform interface goes in there).
- **`go/qr`** (new): `Decode(image []byte) ([]string, error)`, `DecodeLuminance(y []byte, w, h, stride int) ([]string, error)`, `Encode(text string, size int) ([]byte, error)` moved from `cmd/duobaoproxy-core/qr.go`; the CLI subcommands call it.
- **`go/render`**: `Settings` gains `Platform string \`json:"platform,omitempty"\`` (`""` desktop, `"android"`, `"ios"`), set only by `go/mobile`. Desktop output must be byte-identical when `Platform == ""` (add a golden test).

## 2. Renderer behaviour when `Platform != ""`

sing-box (`render/singbox`):
- TUN inbound: no `interface_name`; always `auto_route: true`; `strict_route` false (the UI maps 严格路由 to the OS kill switch instead, §6); address `172.19.0.1/28` + **always** `fdfe:dcba:9876::1/126` — when `tun.ipv6` is off, add a route rule rejecting IPv6 destinations (`ip_version: 6 → reject`) so v6 cannot leak around the VPN.
- MTU: iOS → `min(mtu, 4064)`; Android → as set; xray-front mode → 1500.
- TUN mode always emits the `sniff` action and the `protocol: dns → hijack-dns` rule.
- `stack`: as set; iOS default `mixed` when the setting is empty.
- Android per-app: rows of `settings.apps` with `platform: "android"` become `package_name` rules (proxy/direct/block); desktop rows (no platform) are ignored on mobile and vice-versa (desktop ignores `platform: "android"` rows). `tun.rule_process` / `rule_white` lists become `include_package` (white) or `exclude_package`. The app's own package (`Settings.OwnPackage`, new field) is always in `exclude_package` in exclude mode and never in include mode.
- iOS: per-app rules dropped; add `{"type":"oom-killer"}` service.
- DNS `local` stays `local` — `go/mobile` registers a platform local-DNS transport (§3) so it resolves on the underlying network.
- No Clash API on mobile. Mixed inbound: Android only when `inbound.port > 0` (user setting 本地端口/允许局域网); iOS never.
- Cache file: set by `go/mobile` (absolute path under BasePath), not `os.TempDir()`.
- Export mode additionally strips platform-only fields.

xray (`render/xray`), only meaningful with TUN on mobile:
- New front mode: when `Platform != "" && TUN.Enabled`, xray renders a loopback `mixed-in` on `127.0.0.1:<random port>` with random user/password, `udp: true`, sniffing forced on (`routeOnly` unless the user chose destination override), freedom outbounds `domainStrategy: UseIP`; and `render/singbox` exposes `RenderXrayFront(settings, port, user, pass)` producing the sing-box TUN front: tun inbound (same rules as above, MTU 1500, no sniff/hijack) → socks5 outbound to xray (TCP+UDP); Android package rules in the front get a `direct` outbound. `RenderResult.FrontConfig` carries it. Start order: xray, then the front; stop in reverse. Stats come from xray tags only.

## 3. `go/mobile` API (gomobile; Java package `com.duobao.core`, ObjC prefix `DBC`)

Everything crosses as basic types / JSON strings. All calls are blocking; hosts call them off the main thread.

```go
// lifecycle
type SetupOptions struct {
    BasePath  string // Android filesDir / iOS App Group container; cache.db lives here
    TempPath  string // Android cacheDir / iOS tmp
    AssetPath string // geoip.dat / geosite.dat (xray) and local .srs
    Platform  string // "android" | "ios"
    OwnPackage string // Android package name, "" on iOS
    Debug     bool
}
func Setup(o *SetupOptions) error   // registers engines, sets TMPDIR, XRAY_LOCATION_ASSET, crash output; idempotent
func Version() string               // -X duobaoproxy/mobile.version
func ListCoresJSON() string
func Supports(coreID, protocol string) bool

// logs (process-wide, both engines)
type LogHandler interface { WriteLog(level int32, line string) }
func SetLogHandler(h LogHandler)

// socket protection for everything the process dials while a VPN is up
// (xray RegisterDialerController + probe/direct dialers + sing-box AutoDetectInterfaceControl).
type Protector interface { Protect(fd int32) bool }
func SetProtector(p Protector)      // once per process; Android only

// local DNS on the underlying network (Android: DnsResolver.rawQuery on the non-VPN default network)
type LocalDNS interface { Exchange(message []byte) ([]byte, error) }
func SetLocalDNS(d LocalDNS)

// nodes
type ImportResult struct { NodesJSON, ErrorsJSON string; Count int32 }
func ImportNodes(text string) *ImportResult
func ShareLink(nodeJSON string, v2raynVMess bool) (string, error)
func NormalizeNode(nodeJSON string) (string, error)

// subscriptions (fetched in Go: direct, protected, no cleartext/ATS issues)
type SubResult struct { Body, UserInfo string; Status int32 } // UserInfo = raw subscription-userinfo header
func FetchSubscription(url, userAgent string, timeoutMs int32) (*SubResult, error)

// render
func PrepareSettings(settingsJSON, proxyMode, routeParts string, tun bool) (string, error) // port of App.qml renderSettings()/:72-91, + Platform/OwnPackage injection
type RenderResult struct { Config, StatsTagsJSON, FrontConfig string }
func RenderConfig(coreID, chainJSON, settingsJSON string, mode int32) (*RenderResult, error)

// run — Host is implemented by the VpnService / NEPacketTunnelProvider
type Host interface {
    OpenTun(tunConfigJSON string) (int32, error) // see spec/singbox-mobile.md §2; iOS returns -1 after setTunnelNetworkSettings completed
    StartNetworkMonitor(l *NetworkListener) error // must report the current default network before returning
    CloseNetworkMonitor() error
    InterfacesJSON() (string, error)
    FindOwnerJSON(proto int32, src string, sport int32, dst string, dport int32) (string, error)
}
type NetworkListener struct { /* Go */ }
func (l *NetworkListener) UpdateDefaultInterface(name string, index int32, expensive, constrained bool)
type StartOptions struct { CoreID, Config, StatsTagsJSON, FrontConfig string }
func Start(o *StartOptions, h Host) error
func Stop() error
func Status() string                 // running core id or ""
type Traffic struct { Uplink, Downlink int64 }
func QueryStats(tag string) *Traffic

// tests — config "" means "use the running core" (live latency must use this)
func TCPPing(address string, timeoutMs int32) (int32, error)
func URLTest(coreID, config, url string, timeoutMs int32) (int32, error)
type TestResult struct { Ms int32; Report string }
func Probe(name, coreID, config, address, url string, count, timeoutMs int32) (*TestResult, error)

// QR
func DecodeQR(image []byte) (string, error)                                   // '\n'-joined texts
func DecodeQRLuminance(y []byte, width, height, rowStride int32) (string, error) // CameraX Y plane
func EncodeQR(text string, size int32) ([]byte, error)                        // PNG
```

Throw-away test instances get a context carrying the local-DNS transport and protector (no TUN).

Build (`go/build_mobile.sh`): `gomobile bind -target android/arm64[,android/amd64 for debug] -androidapi 29 -javapkg com.duobao.core -trimpath -tags with_quic,with_wireguard,with_utls,with_gvisor,badlinkname,tfogo_checklinkname0 -ldflags "-s -w -checklinkname=0 -buildid= -X duobaoproxy/mobile.version=$VERSION -X github.com/sagernet/sing-box/constant.Version=$SB" -o mobile/android/app/libs/dbcore.aar ./mobile`. iOS adds `with_low_memory,with_dhcp`, `-target ios,iossimulator -iosversion 15.0`, output `mobile/ios/Frameworks/Dbcore.xcframework`.

## 4. Android app (`mobile/android`)

- `applicationId com.duobao.proxy`, minSdk **29** (DnsResolver.rawQuery, getConnectionOwnerUid), target/compile 36. Gradle 9.x wrapper, AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00, kotlinx-serialization, CameraX for scanning (decode in Go, no ML Kit), Navigation Compose. No other third-party libraries.
- **One process.** The VpnService runs in the app process so UI and VPN share one Go runtime (stats, logs, status read directly). The service is a foreground service (type `specialUse`/`systemExempted` as allowed for VPN) with a notification showing node + live speed + 断开 action; Quick Settings tile; boot receiver for 开机自启 (starts nothing unless the new opt-in 开机自动连接 is on).
- `Protector` = current VpnService.protect, returns true when no VPN is up. `LocalDNS` = `DnsResolver.rawQuery` on the default non-VPN network.
- **Data**: `data.json` in `filesDir`, same schema as desktop; nodes keep their raw JSON object (unknown fields preserved). Store semantics copy `app/src/store.cpp` exactly, including per-key `ui` defaults on every load and all-or-nothing `settings` defaults (`spec/critic.md` A8/A9). Export / import data.json via SAF.
- **Screens** (bottom navigation): 节点 · 分组 · 路由 (rules, DNS, 应用分流 as a sub-screen with the installed-app picker) · 日志 · 设置 (incl. 测试设置, 帮助, 关于开发者 → https://t.me/hyk857, 检查更新 → GitHub releases). A connect button/status card on the 节点 screen (the desktop StatusCard). Behaviour and Chinese labels follow `spec/gui-*.md` verbatim; desktop-only rows are mapped per `spec/critic.md` §C.
- Theme: colour tokens from `spec/gui-settings-shell.md` (Theme.qml), modes 跟随系统 (default) / 浅色 / 深色 / 深浅组合; the rabbit logo from `app/res`.
- Import entry points: paste/clipboard, share intent (`ACTION_SEND text/plain`), `ACTION_VIEW` for share-link schemes, QR from camera and from a gallery image.
- Live latency: every 5 s while connected **and the UI is visible**, `URLTest(core, "", url)` against the running core.

## 5. iOS app (`mobile/ios`) — after Android is stable

SwiftUI app + `PacketTunnel` extension, App Group `group.com.duobao.proxy`. Engines, status, stats, logs, tests and live latency run in the extension; the app talks to it with `sendProviderMessage` (JSON). Render/import/QR may run in the app. Per-app routing hidden. Built through a macOS CI job; signing needs the user's Apple developer account.

## 6. Platform mapping of desktop rows (summary of `spec/critic.md` §C)

系统代理 mode → hidden (VPN only). 严格路由 → Android: open system VPN settings (Always-on + block); iOS: `includeAllNetworks`. 布局 → hidden, key preserved. 重启核心 → stop tunnel + re-run Setup. 数据目录 → 导出/导入数据 + 分享日志. 本地端口/允许局域网 → Android only. Clash API → off.
