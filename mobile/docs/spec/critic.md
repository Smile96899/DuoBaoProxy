# Completeness critique: full-parity Android + iOS port of DuoBao Proxy

Paths are relative to `C:/Users/Administrator/Desktop/h.proxy/` unless prefixed with `SB/` (sing-box@v1.14.3), `XR/` (xray-core@v1.260327.0) or `ST/` (sing-tun module).

---

## (A) Gaps: features, data or flows no section covers, or covers wrongly

### A1. Protocol support matrix per engine (needed for greying rows, the editor and the tray)
No section states the actual lists. The GUI decides support from `ListCores.protocols`, which comes from `core/*` (`App.qml:28,47`), not from the renderer.

| Engine | Protocols | Source |
|---|---|---|
| sing-box | socks, http, shadowsocks, vmess, vless, trojan, hysteria2, tuic, ssh, anytls, wireguard, hysteria, shadowtls (13) | `go/core/singbox/singbox.go:36-38`; renderer list `go/render/singbox/singbox.go:19-22` is identical |
| xray | socks, http, shadowsocks, vmess, vless, trojan, wireguard (7) | `go/core/xray/xray.go:74-76`; `go/render/xray/xray.go:24-27` |
| naive | neither engine. Naive imports from links (`naive+https`/`naive+quic`) and can be edited, but it can never connect or be tested. `Tests.run` skips it (`Tests.qml:100`), `selectNode` ignores it (`App.qml:63`). | |

- `go/mobile` should expose `Supports(core, proto)` from the same list as `Protocols()`, so the UI, renderer and engine cannot drift apart.
- With xray selected, hysteria2, tuic, ssh, anytls, hysteria and shadowtls rows are dimmed. That stays true on mobile unless the xray renderer is extended. Note that xray already links `proxy/hysteria` and ss2022 (§B1), so extending it is possible.

### A2. Throw-away test instances are full engine instances and need the platform too
- **What a test instance gets.** Every through-node test (url, udp, down, up, geo, media, ai, dns) renders a ModeTest config (`Tests.qml:116`, `App.renderSettings()` is reused).
- **Settings that survive ModeTest.** ModeTest still emits `route.auto_detect_interface: true` and `default_domain_resolver: dns-local` (`go/render/singbox/singbox.go:692-695`). Only Export deletes `auto_detect_interface` (`:713-715`). Test mode drops only the inbounds and TUN (`:511-528`) and the log (`:513-515`).
- **What goes wrong without a platform.** On mobile these instances need the same platform ctx as the main box:
  - Without it, Android `dns-local` resolves to 127.0.0.1:53 (§B3), which breaks resolution of any domain-named server.
  - Their sockets are also unprotected and loop into the VPN, unless the app's own package is excluded.
  - core-api §3 mentions passing the ctx but does not say why the test config needs it.
- **iOS.** Tests run in the app process, so while connected **every through-node test is tunnelled through the active node**, which means double proxying and wrong latency.
  - On desktop, the test instance escapes TUN through `auto_detect_interface`. On iOS that binding happens only inside the NE.
  - Parity options:
    - Run tests in the extension via `sendProviderMessage`.
    - Or bind the app's test sockets to the physical interface (`IP_BOUND_IF`). That is unverified on iOS app processes.

### A3. Live latency spawns a new instance every 5 s
- `latencyTimer` (`App.qml:209-213`) calls `Tests.run([selectedId],["url"],true)` every 5000 ms while connected. Each call renders and starts a **new** engine instance (`Tests.qml:116,129`) instead of using the running core.
- The Go side already supports the running core: `UrlTest` with an empty `core_config` uses the running core's dialer (`go/rpc/server.go:144-159`).
- **iOS:** a new sing-box or xray instance every 5 s, inside or next to a ~50 MB extension, is a real risk.
- **Mobile recommendation:**
  - For the connected node, call `URLTest(core, "", url, timeout)` against the running core.
  - On iOS, run it inside the extension.
  - Pause it while the UI is in the background.

### A4. Autostart never connects on desktop
- `setAutoStart` registers `"<exe>" -tray` on Windows, macOS and Linux, never `-connect` (`app/src/system.cpp:159,168,181`). `-connect` is only a manual command-line flag (`main.cpp:50`, `Main.qml:118-128`).
- gui-nodes §5 maps 开机自启 to "Always-on VPN / On-Demand". That **adds** a behaviour the desktop does not have.
- For parity, 开机自启 means "start in the background, not connected". Treat auto-connect as a separate, new option:
  - Android: a `BOOT_COMPLETED` receiver plus `VpnService.prepare` already granted.
  - iOS: `NEOnDemandRule`.

### A5. Re-attaching to a running tunnel, and crash or revoke handling
- **Desktop.** `launch()` calls `status()` (`core_client.cpp:85,173-178`), and `ListCores.running` sets `runningCore` (`:143`). The desktop always spawns a fresh core, so this never matters there.
- **Mobile.** The VPN service or extension outlives the UI, and no section specifies re-sync. On launch the UI must:
  - read the state (Android: bind to the service; iOS: `NEVPNManager.connection.status`);
  - restore `statsTags`;
  - restart traffic polling;
  - restore `appliedSettings`, the settings snapshot used to decide whether to reconnect (`App.qml:26,106,205`). The service must persist it.
- **Core death on desktop.** `QProcess::finished` clears `runningCore` and logs `核心已退出（code）` (`core_client.cpp:47-52`), but `stopped` is never emitted. As a result:
  - `App.onStopped` does not run;
  - the **system proxy stays on**;
  - the traffic timer keeps polling and returns zeros;
  - the tray stays in the connected state (`App.qml:247-254` is skipped).
- **Mobile.** Map `VpnService.onRevoke`, NE `stopTunnel(reason)` and extension jetsam to a proper stopped event. Show the reason, clear the rates and set the tile or notification to off.

### A6. 重启核心 (`SettingsPage.qml:89-90`)
- It calls `core.shutdown(); core.launch()`: it sends `Exit`, waits 1.5 s, then kills and respawns the process (`core_client.cpp:88-99`).
- While connected this drops the tunnel **without** `onStopped` (the same issue as A5).
- Mobile has no process to restart. Map it to "stop the tunnel and re-run `Setup`", or drop it and reword the HelpPage entry `重启核心`.

### A7. Data directory, log files and data exchange
- **What desktop has:**
  - `数据目录` shows `store.dataDir` and opens it with `file:///` (`SettingsPage.qml:87-88`).
  - `gui.log` holds QML warnings, is truncated beyond 512 KiB, and lives in AppData (`main.cpp:17-29`).
  - The core's working directory is AppData (`core_client.cpp:70-73`), so `core.log` (50 KiB cap) also lives there.
- **What desktop lacks.** There is no UI to import or export data.json. data-schema proposes data.json as the cross-device exchange format.
- **For mobile parity plus exchange:**
  - Replace 数据目录/打开 with "export / import data.json" (Android SAF, iOS share sheet or document picker).
  - Add "share logs" for `core.log` and the in-memory logs.
- **Merge rule for imported data:** re-assign ids and remap `ui.selected` (data-schema §5 point 4).

### A8. `ui` defaults are per key on every load; `settings` defaults are all-or-nothing
- `store.cpp:31-38`: each `ui` key (`layout theme core selected proxy_mode mode test_url`) is filled when missing, on **every** load.
- `settings` is seeded only when the whole object is empty (`:39`).
- Mobile must copy this exactly, otherwise a desktop data.json with a partial `ui` behaves differently.
- `test_url` is seeded to `http://cp.cloudflare.com/`. `resetOpts("url")` sets it to `""` (`Tests.qml:73`), and a later load does **not** re-seed it, because `contains` is true.

### A9. Change notification differs between `setSetting` and `setUi`
- `setUi` returns early on an equal value (`store.cpp:222`).
- `setSetting` always emits and saves (`:202-214`). The reconnect decision depends entirely on the string compare of `settingsJson()` against `appliedSettings` (`App.qml:205`).
- `ui` changes never trigger the debounce. Each `ui` write site calls `reconnect()` itself:
  - `Landscape.qml` dropdowns;
  - `SettingsPage.qml:56`;
  - `RoutesPage.qml:36`;
  - `App.qml:95,276`.
- Mobile must do the same, or reconnect whenever the rendered output changes.

### A10. Desktop system-proxy bypass list
- `ProxyOverride` = `localhost;127.*;10.*;172.16-31.*;192.168.*;<local>` (`system.cpp:83`).
- If Android `platform.http_proxy` or VpnService `setHttpProxy` (API 29+) is offered as a "system proxy" equivalent, reuse this list as `bypass_domain` for parity.
- macOS sets HTTP, HTTPS and SOCKS proxies, and Linux sets gsettings (`:88-114`). Neither matters for mobile.

### A11. Local mixed inbound on mobile: 本地端口 and 允许局域网
- **Desktop:** the settings rows are `SettingsPage.qml:59-62`. The inbound has no auth UI; `inbound.username/password` exist only in data (`render.go`, rendered at `singbox.go:520-524`).
- **Android:** any app can reach 127.0.0.1. 允许局域网 (`0.0.0.0`) is still useful for hotspot sharing.
  - Recommendation: keep both rows on Android.
  - When the port is on, generate credentials or warn the user.
  - With the xray bridge, use a separate internal port (core-api §4.3) so the user's port and credentials are not reused.
- **iOS:** an NE can listen on loopback, but reachability from other apps is not guaranteed. Hide both rows, and render `port: 0` unless the xray bridge needs it.

### A12. IPv6 default and leaks (not covered)
- `tun.ipv6` defaults to off (no first-run key; `store.cpp:48`). The TUN then gets only `172.19.0.1/28` (`singbox.go:533-537`).
- With auto_route this adds IPv4 routes only. Desktop help says "IPv6 按系统原样".
- **Android:** IPv6 bypasses the VPN when no v6 route exists, which leaks on IPv6-capable networks.
- **iOS:** without `NEIPv6Settings`, the same applies.
- Parity keeps the desktop semantics. Mobile clients usually add `::/0` and reject it. Decide explicitly and reword the help text.

### A13. `strict_route` and the Kill Switch on mobile
- On desktop it adds Windows firewall rules (HelpPage text).
- **Android:** the only true kill switch is the system "Always-on VPN + Block connections without VPN" setting. Open it with an intent to `Settings.ACTION_VPN_SETTINGS`; `strict_route` itself does nothing there.
- **iOS:** the nearest equivalent is `includeAllNetworks` on `NETunnelProviderProtocol`. It forces the gvisor stack and makes `mixed`/`system` error out (`ST/stack.go:43-60`, per singbox-mobile).
- Map the 严格路由 row to these. Do not pass the flag through silently.

### A14. MTU from settings overrides sing-box's NE default
- Settings always carry `tun.mtu` (9000 from first run, `store.cpp:48`; `render.go` `Defaults` also gives 9000). The renderer emits it explicitly (`singbox.go:531`).
- So sing-box's own NE default of 4064 (`SB/protocol/tun/inbound.go:107-116`, applied only when `mtu == 0`) **never applies**. iOS would run at 9000.
- The mobile override must set `mtu` to 0 or 4064 or lower on iOS, and 1500 with the xray bridge (xray-mobile §5).

### A15. Subscription fetch path and Android 9+ cleartext
- The desktop XHR goes direct: Qt's `QNetworkAccessManager` does not use the system proxy unless it is told to. That is Qt knowledge, not code shown here. It sets only the User-Agent (`GroupsPage.qml:16-18`).
- **Android:** excluding the app's own package also keeps fetches direct, which matches desktop.
- **iOS:** fetches from the app process go through the tunnel while connected.
- **`http://` subscription URLs:**
  - Android 9+ blocks cleartext unless `usesCleartextTraffic` or a network security config allows it.
  - iOS ATS blocks them unless `NSAllowsArbitraryLoads` is set.
  - Desktop allows them.
  - The same applies to the default test URL `http://cp.cloudflare.com/` (`Tests.qml:29`), although the Go HTTP client is not subject to ATS or the cleartext policy, only native fetches are.
  - Recommendation: fetch subscriptions in Go.

### A16. Settings rows not mapped in any section
| Desktop row | Mobile mapping (not yet specified) |
|---|---|
| `检查更新` → `https://github.com/Smile96899/DuoBaoProxy/releases` (`SettingsPage.qml:85-86`) | Android sideload: same URL, or an APK update check. iOS: TestFlight or the store; hide the button. `go/cmd/updater` is Windows/Linux only and is never wired into the GUI. |
| `开发者` → `https://t.me/DuoBaoToolsBot` (`Rail.qml:73-80`) | Put it in Settings or an About screen. |
| `布局` (`ui.layout`) | Drop it, but **preserve the key** for round trips. |
| `模式` (`ui.mode`) | Hide it. Treat it as TUN at render time and never write it (data-schema §4). |

### A17. App routing on Android: what the stored record must hold
- **Desktop record:** `{name, process: <exe basename>, outbound}` (`AppsPage.qml:29-31`). `add()` also rewrites `/` to `\` and strips `file:///` (`:22`). Buttons:
  - `按进程名` is shown on every OS (`:116`);
  - the picker `RoundAdd` is shown on Windows only (`:117`).
  - So macOS and Linux have only manual entry.
- **Android:** store `process: <package name>` plus `platform: "android"` (or a separate `package` key), so desktop rows are not rendered as packages and Android rows are not rendered as `process_name`.
- **The renderer cannot yet tell rows apart.** It emits `process_name` / `process_path` from `Process` alone (`singbox.go:742-773`). `go/mobile` must filter rows by platform before rendering.

### A18. Subscription update UX details
- **全部更新** (`GroupsPage.qml:70`) fires every request in parallel with no progress state.
- The group editor's **删除分组** (`:50`) deletes the group with no confirm and no 默认 guard. That makes it the only UI path that can delete `默认`; after a restart the group is reseeded only if `groups` is empty (`store.cpp:30`).
- Mobile should route every delete through `App.removeGroups` (`App.qml:142-147`).

---

## (B) Contradictions between sections, resolved against the code

| # | Sections | Claim A | Claim B | Resolution (code) |
|---|---|---|---|---|
| B1 | core-api §4.3 Option B vs xray-mobile §2 | `proxy/tun` is "not linked"; `proxy/hysteria` and `shadowsocks_2022` are missing | `proxy/tun` is already compiled in | **xray-mobile is right.** `go/core/xray/xray.go:33` imports `main/json`, which imports `infra/conf` (`XR/main/json/*.go:11`). `infra/conf/tun.go:4` imports `proxy/tun`, which registers itself (`XR/proxy/tun/handler.go:161`). `infra/conf/hysteria.go` and `shadowsocks.go` likewise import `proxy/hysteria` and ss2022. All three are linked; only the renderer and `Protocols()` leave them out (`go/render/xray/xray.go:24-27`). |
| B2 | core-api §4.1 vs singbox-mobile §2 | TUN DNS server = "the TUN gateway" | the address after the first TUN address | **singbox-mobile is right.** `ST/tun.go:149-162`: with no `dns_address`, the DNS server is `Inet4Address[0].Addr().Next()`. With the rendered `172.19.0.1/28` (`singbox.go:533`) that is **172.19.0.2**. With IPv6 it is `fdfe:dcba:9876::2`. The singbox-mobile and xray-mobile sketches use `/30`; the real renderer emits `/28`. |
| B3 | core-api §4.1 vs singbox-mobile §1 | Android `local` DNS "goes through the system resolver, follows the VPN DNS, and loops" | `local` reads `/etc/resolv.conf`, which is missing, and falls back to 127.0.0.1:53 | **singbox-mobile is right.** `SB/dns/transport/local/local_other.go` is `!darwin`. `systemconfig/source_resolv.go` is `!windows && !(darwin && cgo)`, so it is used on Android. iOS (darwin + cgo) uses `source_darwin.go` and works. Consequence: `dns-local` is the `domain_resolver` of every DoH server and `default_domain_resolver` (`singbox.go:403-405,605,695`). On Android, `dns.google` and `doh.pub` cannot be resolved, so **all DNS fails** until the renderer replaces `local` with a host-provided resolver or IP servers. |
| B4 | data-schema §4 `rule_set_dir` vs core-api §4.1 / singbox-mobile §4 | "no `cache_file`, so rule sets re-download on every start" | the core injects a cache file | **core-api is right.** `go/core/singbox/singbox.go:68-89`: when `logFn != nil` (a real Start), a cache file is injected at `os.TempDir()/duobaoproxy-singbox-cache.db`. Remote rule-set contents persist in cache.db (`SB/route/rule/rule_set_remote.go`). Test instances (`logFn` nil) have no cache, but ModeTest also emits no rules. The mobile fix is only the path: `os.TempDir()` on Android is `/data/local/tmp`. |
| B5 | core-api §4.3 Option A vs xray-mobile §2 | sing-box front does sniff, then hijack-dns, then fakeip or remote DNS | the front does **not** sniff or hijack; UDP 53 reaches xray's `dns-out` | **Use xray-mobile's approach.** It keeps desktop xray DNS behaviour: `mixed-in` UDP 53 goes to `dns-out` (`go/render/xray/xray.go:464`), and domain-split servers are configured at `:420-452`. Forced sniffing must then be on in xray (`:350-355`), because domain rules otherwise see only IPs. If app rules are applied in the front (Android), the front also needs a `direct` outbound plus `package_name` rules. The xray-mobile sketch has only the `xray` outbound. Desktop disables apps for xray (`AppsPage.qml:15`), so this is beyond parity. |
| B6 | gui-groups §3.2 vs xray-mobile §1 | "Always exclude the app's own package" alongside `include_package` for allow-lists | do not add the own package in allow mode | Android forbids mixing `addAllowedApplication` and `addDisallowedApplication`. **Exclude mode:** add the own package to `exclude_package`. **Include mode** (`tun.rule_white`): leave it out. Either way the own process ends up outside the VPN. |
| B7 | core-api §4.1 vs actual default | iOS "MTU ≤4064, or emit 0" | — | Settings always contain 9000, so sing-box's 4064 NE default never applies (`SB/protocol/tun/inbound.go:107-116`). The mobile override must set it (A14). |
| B8 | gui-nodes vs code | line numbers: tray `system.cpp:356-451`, scan `455-489`, QR `491-512`, autostart `226-268`, traffic `core_client.cpp:342-377` | — | The actual lines are: `setupTray` `system.cpp:280`, `rebuildTrayMenus` `:318`, `scanScreenQr` `:374-408`, `makeQrImages` `:410-425`, `autoStart`/`setAutoStart` `:145-187`, `installedApps` `:237`, `pollTraffic` `core_client.cpp:268-295`. The behaviour described is correct. |
| B9 | gui-nodes §0 vs code | "first-run defaults" for `ui` | — | `ui` defaults are per key on every load; only `settings` is first-run-only (A8). |
| B10 | gui-nodes §5 vs code | autostart becomes Always-on/On-Demand auto-connect | — | Desktop autostart is `-tray` only and never connects (A4). |
| B11 | core-api §2 vs singbox-mobile | — | — | No conflict, but complementary: ICMP on iOS works over unprivileged `SOCK_DGRAM` ICMP (`probe/icmp_other.go:17-44`). In the containing app it is tunnelled while connected, the same as the other direct probes (core-api §2 caveat). |

---

## (C) Platform limitations (feature → Android → iOS)

| Desktop feature | Android | iOS |
|---|---|---|
| System proxy mode (`ui.mode=system`, `system.cpp:76-118`) | Not available without root. VPN only. Optionally `setHttpProxy` (API 29+), which only proxy-aware apps honour, with the bypass list from `system.cpp:83`. | Not available. VPN only. `NEProxySettings` inside the tunnel is optional. |
| TUN (sing-box) | VpnService plus our PlatformInterface (protect, monitor, interfaces, owner finder) | Packet Tunnel; utun fd found by scanning; `UnderNetworkExtension=true`; ~50 MB memory limit |
| xray engine | Works behind a sing-box front over a loopback socks bridge, or with xray's linked `proxy/tun` (B1). Needs own-package exclusion plus `RegisterDialerController` protect. | Same options. The two-engine memory budget must be measured. geosite.dat matchers cost heap. |
| Protocols | sing-box 13 / xray 7 (A1). Naive unsupported everywhere. | Same |
| App routing (`settings.apps`) | Yes: `package_name` rules via `FindConnectionOwner` (API 29+), `include_package`/`exclude_package`. `block` stays a route reject. | **No** (per-app VPN needs MDM). Hide 应用分流 and 分流方式, force `route_parts=rules`, keep `apps` in the data. |
| `tun.rule_process` / `rule_white` | Packages, same as apps | No |
| `strict_route` / Kill Switch | No effect. Point users to the system "Always-on + Block connections without VPN" setting. | `includeAllNetworks` (forces gvisor) |
| `tun.stack` | gvisor, system or mixed | gvisor is forced when `includeAllNetworks` is on. Prefer system or mixed for memory, which needs measurement. |
| MTU | 9000 OK; 1500 with the xray bridge | ≤4064 must be set explicitly (A14) |
| IPv6 off | IPv6 leaks outside the VPN (A12) | Same |
| DNS `local` / `underlying_dns` | Broken (127.0.0.1 fallback, B3). Needs a host resolver or IP DNS. | Works (darwin cgo) |
| `dhcp://` DNS | Needs the platform; not built (`with_dhcp` is off) | Upstream enables `with_dhcp` on Darwin |
| Direct probes (TCP, ICMP, loss, local dnsleak half) | Correct if the own package is excluded or sockets are protected | Tunnelled while connected unless run in the extension |
| Through-node tests and 5 s latency | Must use the platform ctx (A2). Prefer the running core (A3). | Tunnelled through the active node from the app process; run them in the extension |
| Traffic stats and logs | Same process, or bind to the service | In the extension; relay through `sendProviderMessage` |
| Tray, tooltip, toasts, tray menu | Foreground-service notification (stop action, speed), Quick Settings tile, snackbars | VPN status in Settings, optional widget or Live Activity, in-app banners |
| Autostart (`-tray`, no connect) | Boot receiver; auto-connect is a new opt-in (Always-on) | On-Demand rules only; there is no "launch at login" |
| Screen QR scan (`system.cpp:374-408`) | Camera plus gallery image, decoded in-process (gozxing via `go/mobile`, or ML Kit) | Camera plus Photos (AVFoundation/Vision, or Go decode) |
| QR generation (subprocess + temp PNG, `system.cpp:410-425`) | In-process PNG plus share sheet | Same |
| Clipboard import and 从剪贴板 | Readable only in the foreground (Android 10+) | Paste prompt (iOS 16+) |
| Windows app picker / shortcuts (`system.cpp:225-268`) | PackageManager list (`QUERY_ALL_PACKAGES` or `<queries>`) | N/A |
| `-import-file`, `-connect` | Share intent / `ACTION_VIEW`, optional URL scheme | Share extension / URL scheme / Files |
| 允许局域网 / 本地端口 | Usable (hotspot sharing); add credentials | Hide it; port 0 unless the bridge needs it |
| 重启核心 | Stop the tunnel and re-run `Setup` (A6) | Same, or drop it |
| 数据目录 / 打开 | Export or import data.json and logs via SAF | Files / share sheet |
| 检查更新 | GitHub APK link | TestFlight or App Store; hide the button |
| Layout landscape/portrait, Ctrl+L, dark title bar | Drop it (keep `ui.layout`); tablet layout is optional | Same |
| Clash API (`clash_api_port`) | Disable it | Disable it (memory) |
| Geo assets (`geoip.dat` 16.5 MB + `geosite.dat` 11.4 MB, `.srs` from GitHub raw) | Copy to filesDir; downloads need a protected socket; GitHub may be blocked | App Group container; memory risk for xray geosite |
| Binary size | About 60 MB per ABI for both engines (xray-mobile §5); ship an AAB or ABI splits | Similar; both engines link into the extension |
| Distribution | Sideload or Play (VPN policy declaration) | Paid Apple developer account; VPN apps are not allowed in the China App Store; signing needs a Mac |