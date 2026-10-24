# DuoBao Proxy: persisted data model and renderer mapping (spec for the Android/iOS port)

## 0. Where the file lives and how it is written

- The path is `QStandardPaths::AppDataLocation + "/data.json"` (`app/src/store.cpp:13-15`). The org is `DuoBao` and the app is `DuoBaoProxy` (`app/src/main.cpp:34-35`), so on Windows the file is `%APPDATA%/DuoBao/DuoBaoProxy/data.json`.
- `Store::save` writes exactly five top-level keys as indented JSON through `QSaveFile`, which makes each write atomic (`store.cpp:55-68`). Every mutator saves at once; there is no batching.
- **Unknown top-level keys are dropped on save.** `load` reads only `nodes`, `groups`, `settings`, `ui` and `next_id` (`store.cpp:23-27`). Unknown keys *inside* `settings`, `ui`, node entries and groups do survive, because those are whole `QVariantMap` round-trips. Any field a mobile client adds must therefore go inside one of those objects, never at the top level.
- All numbers go through `QVariant`/JS `Number`, which are doubles. Qt writes integral doubles without a decimal point, so `2080` stays `2080`. Mobile code must emit integers where Go expects `int` (`port`, `sniff`, `mtu`, …), or `json.Unmarshal` fails.
- Other state is not in data.json: auto-start lives in the registry Run key (`app/src/system.cpp:147-159`) and the OS proxy in the registry (`system.cpp:79-83`).

---

## 1. data.json schema

```jsonc
{
  "nodes":   [NodeEntry],
  "groups":  [Group],
  "settings": Settings,   // = render.Settings JSON, sent to RenderConfig after renderSettings()
  "ui":       Ui,
  "next_id":  int         // monotonic id allocator, default 1 (store.cpp:27)
}
```

### 1.1 `nodes[]` (NodeEntry)

| key | type | written by | notes |
|---|---|---|---|
| `id` | int | `Store::addNode/addNodes` (`store.cpp:73-74, 85`) | `next_id++`. This is a local id, not stable across devices. |
| `group` | string | addNode(s), `updateNode({group})` (`App.qml:125,152`), `moveNode` / `moveNodeToGroup` (`store.cpp:177,187`), NodeEditor (`NodeEditor.qml:140`) | The **group name**, not an id. A node can reference a group name that is missing from `groups[]`. NodesPage then shows it as an implicit group (`NodesPage.qml:294-298`). |
| `lat` | int | addNode sets `0`; `Tests.save` sets `res.url.ms` or `-1` on failure (`Tests.qml:135`); NodeEditor resets it to `0` | Legacy latency: 0 = untested, -1 = failed. The tray and the fallback reader use it (`Tests.qml:205`, `App.qml:39`). |
| `res` | object `{kind: Result}` | `Tests.save` (`Tests.qml:129-136`); NodeEditor resets it to `{}` | Absent until the first test. |
| `node` | node.Node object (§2) | addNode(s) from Go `ImportNodes` output, or NodeEditor draft | Stored as an object, not a string. `store.nodeJson(id)` serializes it compactly for gRPC (`store.cpp:123-127`). |

Entries are ordered. The array order is the manual order inside a group (`moveNode`, `store.cpp:163-181`). `moveNodeToGroup` appends the node to the end of the array.

**`res[kind]` shapes.** The kinds are `url tcp icmp udp loss down up geo media ai dns` (`Tests.qml:11-23`).

| kind | success shape | source |
|---|---|---|
| url, tcp, icmp, udp | `{ms:int}` (at least 1) | `Tests.qml:186,195` |
| loss | `{avg:int, min:int, max:int, jitter:int, loss:float 0..1}`; adds `err:"超时"` if loss ≥ 1 | `probe/extra.go:49-53`, `Tests.qml:196` |
| down, up | `{bps:float}` | `rpc/server.go:263,273` |
| geo | `{ip:string, cc:string}` | `probe/extra.go:166-167` |
| media | `{netflix?, disney?, youtube?: {ok:bool, region?:string, note?:string}}` | `probe/extra.go:197-199, 343` |
| ai | `{chatgpt?, claude?, gemini?: {ok, region?, note?}}` | `probe/extra.go:347` |
| dns | `{leak:bool, local:string, servers:[{ip, cc, country, asn}]}` | `probe/extra.go:354-365` |
| any failure | `{err:"超时"\|"失败", detail:string}` | `Tests.qml:167,175` |

### 1.2 `groups[]` (Group)

The comment in `store.h:4` says `used`; that is stale. The real fields are below.

| key | type | written by |
|---|---|---|
| `name` | string (unique, the key) | `addGroup` deduplicates by name (`store.cpp:129-134`); `App.createGroup` (`App.qml:134-139`); group editor (`GroupsPage.qml:55-56`) |
| `sub_url` | string (`""` = manual group) | group editor |
| `sub_interval_min` | int, 0 = off, 0..1440 | group editor (`GroupsPage.qml:47,55`); auto-update timer checks it every 60 s (`GroupsPage.qml:146-153`) |
| `updated` | string, JS `toISOString()` (UTC) | subscription refresh (`GroupsPage.qml:23`) |
| `upload`, `download`, `total` | number, bytes (use Int64) | every `k=v` pair of the `subscription-userinfo` header, stored as `Number(v)` (`GroupsPage.qml:24-27`) |
| `expire` | number, unix **seconds** | same header (`GroupsPage.qml:128` multiplies by 1000) |

Rules and quirks:
- A default group `"默认"` is created when `groups` is empty (`store.cpp:30`). It can never be deleted (`App.qml:133,143`) because imports land there.
- A refresh does `updateGroup(patch)`, then `removeGroupNodes(name)`, then re-imports into the same group (`GroupsPage.qml:28-30`). Node ids therefore change on every refresh, and `ui.selected` can dangle.
- **Desktop bug:** renaming a group through `updateGroup(oldName, {name:new})` does not rewrite `nodes[].group`. Those nodes become orphans that show under the old name.
- Order is the user order (`moveGroup`).
- Unknown header keys are also stored as group fields.

### 1.3 `settings{}`

This is the persisted `render.Settings` (`go/render/render.go:38-112`). `setSetting` accepts a key of at most two levels: `"a"` or `"a.b"` (`store.cpp:202-214`). Arrays and objects from QML are unwrapped from `QJSValue` (`store.cpp:198-200`).

First-run defaults are seeded **only when `settings` is empty** (`store.cpp:39-50`).

| key | Go type / json | first-run default (store.cpp) | Go `Defaults()` if zero (`render.go:115-152`) | written by UI |
|---|---|---|---|---|
| `log_level` | string | `"info"` | `"info"` | none |
| `inbound.address` | string | `"127.0.0.1"` | `"127.0.0.1"` | SettingsPage "允许局域网" toggle sets `"0.0.0.0"` or `"127.0.0.1"` (`SettingsPage.qml:62`) |
| `inbound.port` | int (0 disables the mixed inbound, `render.go:43`) | `2080` | none | SettingsPage "本地端口" (`SettingsPage.qml:60`) |
| `inbound.username`, `inbound.password` | string | none | none | none |
| `inbound.sniff` | int enum: 0 disabled, 1 for routing, 2 for destination (`render.go:30-34`) | `1` | none | none |
| `inbound.domain_strategy` | string | none | none | none (not read by either renderer as far as grep shows) |
| `custom_inbounds` | raw JSON array | none | none | none |
| `apps` | `[{name?, process, outbound}]`, outbound = proxy, bypass or block (`render.go:155-161`) | none | none | AppsPage (`AppsPage.qml:17-32`) writes `{name, process: "<exe basename>", outbound:"proxy"}` and edits `outbound` |
| `tun.enabled` | bool | `false` | none | **overridden at render time** (§3), never by the UI |
| `tun.stack` | string: gvisor, system, mixed | `"gvisor"` | `"gvisor"` | SettingsPage "协议栈" (`SettingsPage.qml:77`) |
| `tun.mtu` | int | `9000` | `9000` | none |
| `tun.ipv6` | bool | none | none | SettingsPage IPv6 (`:83`) |
| `tun.strict_route` | bool | none | none | SettingsPage "严格路由" (`:79`) |
| `tun.fake_dns` | bool | none | none | SettingsPage FakeIP (`:81`) |
| `tun.interface_name` | string | none | `"duobao-tun"` | none |
| `tun.rule_white`, `tun.rule_process[]`, `tun.rule_cidr[]` | bool, []string, []string | none | none | none |
| `routing.proxy_domain`, `direct_domain`, `block_domain` | []string, v2fly syntax (`render.go:214-240`) | direct `["geosite:cn","geosite:private"]`, block `["geosite:category-ads-all"]` | none | RoutesPage RuleBox (`RoutesPage.qml:62-64`, `RuleBox.qml:34`) |
| `routing.proxy_ip`, `direct_ip`, `block_ip` | []string (CIDR or `geoip:`) | direct `["geoip:cn","geoip:private"]` | none | RoutesPage RuleBox (`:65-67`) |
| `routing.final` | string: proxy, bypass or block (`"block"` maps to bypass in sing-box) | `"proxy"` | `"proxy"` | RoutesPage "未匹配流量", proxy or bypass only (`:72`) |
| `routing.custom_rules` | raw JSON array (core-native) | none | none | none (renderSettings only clears it) |
| `routing.domain_strategy`, `outbound_domain_strategy` | string | none | none | none |
| `routing.dns_routing` | bool | `true` | none | RoutesPage "DNS 分流" (`:78`) |
| `routing.remote_dns` | string | `https://dns.google/dns-query` | same | RoutesPage (`:74`) |
| `routing.remote_dns_strategy` | string | none | none | none |
| `routing.direct_dns` | string | `https://doh.pub/dns-query` | same | RoutesPage (`:76`) |
| `routing.direct_dns_strategy` | string | none | none | none |
| `routing.dns_final_out` | string: proxy or bypass | none | `"proxy"` | none |
| `routing.underlying_dns` | string | none | `"local"` | none |
| `routing.custom_dns` | raw JSON object; replaces the whole dns block | none | none | none |
| `mux.protocol` | string: h2mux, smux, yamux | `"h2mux"` | `"h2mux"` | none |
| `mux.padding` | bool | none | none | none |
| `mux.concurrency` | int | `8` | `8` (so 0 cannot disable mux) | none |
| `mux.default_on` | bool | `false` | none | none |
| `fingerprint` | string, global uTLS default | none | none | none |
| `skip_cert_verify` | bool | none | none | none |
| `geoip_path`, `geosite_path` | string | none | none | none; **no renderer reads them** (grep finds no use outside `render.go`) |
| `rule_set_dir` | string | none | none | none; passed to `ruleBuilder.dir` (`render/singbox/singbox.go:599`) but **never read** (`singbox.go:411-431`) |
| `clash_api_port`, `clash_api_secret` | int, string | none | none | none; when port > 0, sing-box gets `experimental.clash_api` on `127.0.0.1:port` with `external_ui:"dashboard"` (`singbox.go:718-724`) |

Fields marked "written by UI: none" are reachable only by editing data.json by hand. Mobile must **seed the same first-run defaults as `store.cpp:39-50`**. Go `Defaults()` cannot supply `port 2080`, `sniff 1`, `dns_routing true` or the geosite/geoip lists.

`raw JSON` fields are `json.RawMessage`. In data.json they are JSON values (an array or object), never strings.

### 1.4 `ui{}`

| key | type / values | default (`store.cpp:31-38`) | read / written |
|---|---|---|---|
| `layout` | `landscape` \| `portrait` | `landscape` | `Main.qml:8`; `App.setLayout` (`App.qml:69`). Desktop only. |
| `theme` | `system` \| `light` \| `dark` \| `mixed` | `system` | `Main.qml:22`; `SettingsPage.qml:52` |
| `core` | `sing-box` \| `xray` | `sing-box` | `App.qml:11,58` |
| `selected` | int node id, -1 = none | `-1` | `App.qml:12,59,66,127,141,268` |
| `proxy_mode` | `rule` \| `global` \| `direct` | `global` | `App.qml:76`; `Landscape.qml:43-44`; `RoutesPage.qml:15,36` |
| `mode` | `system` \| `tun` | `system` | `App.qml:33,91,241,276`; `Landscape.qml:48-49`; `SettingsPage.qml:56`; `AppsPage.qml:14` |
| `test_url` | string, legacy | `http://cp.cloudflare.com/` | `Tests.qml:59`; cleared by `resetOpts("url")` (`:73`). Note that it overrides `tests.url.url` whenever that is empty. |
| `route_parts` | `rules` \| `apps` \| `both` | absent, treated as `both` (`App.qml:94`) | `App.setRouteParts` (`:95`) |
| `sort` | `default` \| `latency` \| `speed` \| `geo` \| `name` | absent, treated as `default` | `NodesPage.qml:272,359` |
| `tests` | `{kind: {field: value}}` | absent | `Tests.setOpt` / `resetOpts` (`Tests.qml:62-74`); defaults in `Tests.qml:28-40` |

Defaults for `ui.tests` fields (`Tests.qml:28-40`):

```
url:   {url: "http://cp.cloudflare.com/", timeout: 8}
tcp:   {timeout: 5}
icmp:  {timeout: 3}
udp:   {server: "8.8.8.8:53", domain: "www.google.com", timeout: 5}
loss:  {count: 10, timeout: 2}
down:  {url: "https://speed.cloudflare.com/__down?bytes=25000000", timeout: 15}
up:    {url: "https://speed.cloudflare.com/__up", size: 10 /*MB*/, timeout: 15}
geo:   {url: "https://www.cloudflare.com/cdn-cgi/trace", timeout: 8}
media: {services: ["netflix","disney","youtube"], timeout: 15}
ai:    {services: ["chatgpt","claude","gemini"], timeout: 15}
dns:   {timeout: 25}
```

Timeouts are in seconds. An empty value or empty array falls back to the default (`Tests.qml:55-61`).

---

## 2. node.Node JSON schema (`go/node/node.go`)

Top level (`node.go:119-158`):

| json | Go type | omitempty | used by |
|---|---|---|---|
| `protocol` | string | **no** | all: `socks http shadowsocks vmess vless trojan hysteria2 tuic naive ssh anytls wireguard hysteria shadowtls` (`node.go:14-28`) |
| `name` | string | yes | all; the display falls back to `server:port` |
| `server` | string | **no** | all |
| `port` | int | **no** | all, 1..65535 |
| `username` | string | yes | socks, http, naive, ssh |
| `password` | string | yes | socks, http, shadowsocks, trojan, hysteria2, tuic, naive, ssh, anytls, shadowtls (the ShadowTLS password) |
| `uuid` | string | yes | vmess, vless, tuic |
| `method` | string | yes | shadowsocks; shadowtls (inner SS method) |
| `plugin` | string | yes | shadowsocks, e.g. `obfs-local;obfs=http;obfs-host=x` |
| `alter_id` | int | yes | vmess |
| `vmess_security` | string | yes | vmess |
| `flow` | string | yes | vless |
| `socks_version` | int, 4 or 5 | yes | socks |
| `uot` | int: 0 off, 1 or 2 version | yes | shadowsocks |
| `stream` | Stream | yes | socks, http, ss, vmess, vless, trojan, anytls; shadowtls uses `sni` and `utls` |
| `hysteria2` | Hysteria2Options | yes | hysteria2 |
| `tuic` | TUICOptions | yes | tuic |
| `wireguard` | WireGuardOptions | yes | wireguard |
| `hysteria` | HysteriaOptions | yes | hysteria (v1) |
| `shadowtls` | ShadowTLSOptions | yes | shadowtls |
| `quic_tls` | QUICTLS | yes | hysteria2, tuic, hysteria |
| `naive` | NaiveOptions | yes | naive |
| `custom_outbound` | raw JSON object, merged over the rendered outbound | yes | all |
| `custom_config` | raw JSON object, merged over the full config (exit node only, `singbox.go`) | yes | all |

Sub-objects (all fields are `omitempty` unless noted):

- **Stream** (`node.go:33-57`):
  - `net` (tcp, ws, http, grpc, httpupgrade, xhttp; the default `tcp` is applied by `stream()`, `node.go:160-168`)
  - `sec` (`""` or `"tls"`), `pac_enc`, `path`, `host`, `h_type`
  - `sni`, `alpn` (comma list), `cert`, `utls`, `insecure` (bool)
  - `ed_name`, `ed_len` (int)
  - `pbk`, `sid`, `spx` (Reality)
  - `mux_s` (int: 0 follows global, 1 on, 2 off)
- **Hysteria2Options** (`:59-65`): `obfs_password`, `up_mbps` int, `down_mbps` int, `hop_ports` (e.g. `"20000-30000"`), `hop_interval` int.
- **TUICOptions** (`:67-73`): `congestion_control`, `udp_relay_mode`, `udp_over_stream` bool, `zero_rtt` bool, `heartbeat`.
- **WireGuardOptions** (`:76-83`):
  - **required, not omitempty:** `private_key`, `peer_public_key`, `local_address` (comma CIDRs; `Validate` adds `/32` or `/128` in place, `node.go:219, 258-276`)
  - optional: `pre_shared_key`, `reserved` (`"a,b,c"`, 0-255 each), `mtu` int
- **HysteriaOptions** (`:86-91`): `auth`, `up_mbps`, `down_mbps`, `obfs`.
- **ShadowTLSOptions** (`:96-99`): `version` int (default 3), `ss_password` (**not omitempty**, the inner SS password).
- **QUICTLS** (`:102-108`): `sni`, `alpn`, `ca`, `insecure`, `disable_sni`.
- **NaiveOptions** (`:110-116`): `protocol` (https or quic), `extra_headers`, `sni`, `certificate`, `insecure_concurrency` int.

`Validate` requirements (`node.go:187-244`):
- every protocol needs non-empty protocol and server, and port in 1..65535
- ss: `method` + `password`
- vmess, vless, tuic: `uuid`
- trojan, hysteria2: `password`
- naive: `username` + `password`
- wireguard: the three required keys above
- hysteria: `hysteria.auth`
- shadowtls: `password` + `shadowtls.ss_password` + `method`
- ssh: `username`
- anytls: `password`
- socks and http have no extra requirement

Any other protocol value is an error.

The NodeEditor writes only these subsets (`NodeEditor.qml:66-117`):
- it drops keys whose value is `""` or `false` (`:62`)
- integer fields are numbers
- new-node seeds (`:33-37`): trojan and anytls get `stream:{sec:"tls"}`; ss and shadowtls get `method:"aes-256-gcm"`; shadowtls gets `shadowtls:{version:3}`; wireguard gets `wireguard:{local_address:"10.0.0.2/32"}`

---

## 3. How `renderSettings()` builds the RenderConfig settings (`app/qml/App.qml:72-93`)

```js
s = JSON.parse(store.settingsJson())          // persisted settings{}, compact
s.routing ||= {}; s.tun ||= {}
mode = ui.proxy_mode || "global"
if (mode == "global") {
  s.routing.proxy_domain = s.routing.direct_domain = s.routing.proxy_ip = s.routing.direct_ip = []
  s.routing.custom_rules = []; s.routing.final = "proxy"
  // NOTE: block_domain/block_ip and apps are KEPT (ads blocked, app rules apply)
} else if (mode == "direct") {
  s.routing.final = "bypass"
  // NOTE: all lists and apps are KEPT, so proxy_domain/proxy_ip still go to proxy
}                                             // "rule": unchanged
parts = ui.route_parts || "both"
if (parts == "rules") s.apps = []
if (parts == "apps")  { all six routing lists = []; s.routing.custom_rules = [] }
s.tun.enabled = (ui.mode == "tun") && coreId == "sing-box"   // coreHasTun, App.qml:29
```

Call sites:
- **Connect:** `core.renderConfig(req, coreId, [store.nodeJson(selectedId)], JSON.stringify(renderSettings()), 0 /*ModeFull*/)` (`App.qml:107`). The chain is always a single node; the GUI does no chaining.
- **Tests** use the same settings with mode `1` (ModeTest), which renders no inbounds, rules or TUN and disables the log (`Tests.qml:165`, `singbox.go:510-515`).
- **Export** uses mode `2` (`RoutesPage.qml:39`). ModeExport only deletes `route.auto_detect_interface` (`singbox.go:713-715`). The comment "without machine-specific paths" (`render.go:23`) is not otherwise implemented.

After the render:
- `startCore(coreId, config, tags, sys.appDir())` (`App.qml:235`). The 4th argument is `AssetDir`. **It is not part of Settings.** It goes through `rpc/server.go:109`, and xray uses it to set the `XRAY_LOCATION_ASSET` env var (`core/xray/xray.go:168-169`).
- System proxy: in `ui.mode=="system"` the GUI sets the Windows proxy to `settings.inbound.address:port`, falling back to `127.0.0.1:2080` (`App.qml:241-242`).

Reconnect triggers:
- Any `settingsChanged` while connected starts an 800 ms debounce. It reconnects only if the **raw** `settingsJson()` differs from the JSON the core was started with (`App.qml:199-206`, `appliedSettings` set at `:106`).
- `ui` changes such as `proxy_mode`, `mode` and `route_parts` call `App.reconnect()` explicitly at each write site.

Go side: `json.Unmarshal` into `render.Settings` (`rpc/server.go:362-367`) ignores unknown keys. Each renderer calls `set.Defaults()` (`singbox.go:507`, `xray.go:321`).

**xray rejects `tun.enabled=true`** with `"xray has no TUN inbound"` (`render/xray/xray.go:326-327`). It also ignores `apps`.

---

## 4. Machine-specific settings and what mobile must change

**Desktop/machine-specific values in Settings and the call path:**

| field | why it is machine-specific | mobile action |
|---|---|---|
| `inbound.address` / `port` | Local mixed listener, also used for the OS proxy | The VPN path does not need it. Use `port:0` to disable it (`render.go:43`). Keep `127.0.0.1:<port>` only if you want a local proxy for other apps, or `0.0.0.0` for hotspot sharing. **Required** internally if xray runs behind sing-box TUN (below). |
| `apps[].process` | Windows exe basename or path, rendered as `process_name` / `process_path` (`singbox.go:742-772`) | Android needs a `package_name` rule, which the renderer does not have yet: add something like `AppRule.package` or map it in go/mobile. iOS: unsupported, so ignore `apps`. When importing desktop data, keep `apps` untouched and do not render it. |
| `tun.rule_process` | process names | same as `apps` |
| `tun.interface_name` (default `duobao-tun`) | the Windows adapter name | ignored with a platform-provided TUN fd |
| `tun.stack`, `tun.mtu`, `tun.strict_route` | desktop tuning | Keep stack and MTU as stored, but test them in NE/VpnService. `strict_route` is meaningless on mobile. |
| TUN address | Hard-coded `172.19.0.1/28` (+ `fdfe:dcba:9876::1/126` when `ipv6`), plus `auto_route:true` (`singbox.go:529-537`) | The platform interface must build the VpnService/NE routes from these. Keep `auto_route:true`; libbox-style OpenTun derives routes from it. |
| `route.auto_detect_interface:true` | Always on in Full mode (`singbox.go:700-704`) | On Android and iOS this needs a sing-box platform interface (interface monitor + `ProtectFd` / `VpnService.protect`). Without one it fails or loops. go/mobile must supply a PlatformInterface. |
| `routing.underlying_dns:"local"` | the OS resolver | Works only through the platform interface on mobile. Otherwise set an explicit IP DNS. |
| `geoip_path` / `geosite_path` | unused | Ignore. xray geo data comes from **AssetDir** (env `XRAY_LOCATION_ASSET`, process-global). Ship `geoip.dat` / `geosite.dat` in APK assets or the app bundle, copy them to filesDir or the App Group container, and pass that path. |
| `rule_set_dir` | unused (`singbox.go:411-431`) | sing-box remote `.srs` rule sets are downloaded from raw.githubusercontent.com via `direct`, `update_interval 7d`, **with no `cache_file`**, so they re-download on every start. Mobile should get a renderer change that sets `experimental.cache_file.path` (and/or a local rule-set path) under the app files dir or App Group, using `rule_set_dir`. |
| `clash_api_port` / `secret` | an optional localhost controller with `external_ui:"dashboard"` (a relative path) | Leave it at 0 unless the connections page needs it. If used, pick a free loopback port and do not rely on `external_ui`. |
| `log_level` | none | Mobile log volume matters, and the iOS NE has a memory limit. Consider `warn` as the mobile runtime default without persisting it. |

**Mobile-specific render overrides.** These belong in the mobile equivalent of `renderSettings()` and should not be persisted:
- `tun.enabled = true` always; mobile always runs in VPN mode. `ui.mode` stays a desktop concept, so leave it untouched in exchanged data.
- `ui.proxy_mode` and `ui.route_parts` logic: identical to §3.
- xray engine: xray's renderer refuses TUN, so go/mobile must render xray with `tun.enabled=false` and a loopback inbound, plus a separate sing-box TUN config whose outbound is socks to that loopback port. The DNS/rule ownership split has to be decided there; the current renderers do not support this.
- `apps` → Android package rules; dropped on iOS.

---

## 5. Recommended Kotlin/Swift model (JSON-compatible)

Principles:
1. **Treat `node` as an opaque JSON object** on the native side, as desktop does. Go (`go/mobile`) parses, validates, renders and builds share links; the editor edits by path, like `NodeEditor.getAt/setAt`. A typed mirror is optional, for UI convenience only, and must re-encode with omitempty semantics.
2. **Keep `settings` and `ui` as JSON objects with typed accessors**, so unknown or desktop-only keys (`layout`, `mode`, `apps`, `custom_*`) round-trip untouched.
3. Encoding: omit empty strings, false and 0 where Go uses omitempty. Never encode `null`. Integers stay integers. `upload`, `download`, `total` and `expire` are Int64. `loss` and `bps` are doubles.
4. **ids are local.** When importing a desktop data.json, keep the ids and `next_id`. When merging nodes into existing data, re-assign ids and remap `ui.selected`.
5. Mobile-only keys go **inside `ui`**, for example `ui.mobile = {...}`. Desktop drops unknown top-level keys.
6. Reproduce `store.cpp:30-50` exactly on first run, including group `"默认"`.

Kotlin (kotlinx.serialization):

```kotlin
val DbpJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

@Serializable data class DataFile(
    val nodes: MutableList<NodeEntry> = mutableListOf(),
    val groups: MutableList<Group> = mutableListOf(Group(name = "默认")),
    val settings: JsonObject = DefaultSettings,        // render.Settings, opaque + accessors
    val ui: JsonObject = DefaultUi,
    @SerialName("next_id") val nextId: Int = 1,
)

@Serializable data class NodeEntry(
    val id: Int,
    val group: String,
    val lat: Int = 0,                                  // 0 untested, -1 failed
    val res: Map<String, JsonObject> = emptyMap(),     // kind -> TestResult (see §1.1)
    val node: JsonObject,                              // node.Node, passed verbatim to go/mobile
)

@Serializable data class Group(
    val name: String,
    @SerialName("sub_url") val subUrl: String = "",
    @SerialName("sub_interval_min") val subIntervalMin: Int = 0,
    val updated: String? = null,                       // ISO-8601 UTC
    val upload: Long? = null, val download: Long? = null,
    val total: Long? = null, val expire: Long? = null, // expire = unix seconds
    // + preserve unknown header keys: use a JsonObject-backed wrapper if needed
)

// Optional typed view of node.Node, for the editor only
@Serializable data class ProxyNode(
    val protocol: String, val name: String? = null, val server: String, val port: Int,
    val username: String? = null, val password: String? = null, val uuid: String? = null,
    val method: String? = null, val plugin: String? = null,
    @SerialName("alter_id") val alterId: Int? = null,
    @SerialName("vmess_security") val vmessSecurity: String? = null,
    val flow: String? = null, @SerialName("socks_version") val socksVersion: Int? = null,
    val uot: Int? = null, val stream: Stream? = null,
    val hysteria2: JsonObject? = null, val tuic: JsonObject? = null, val wireguard: JsonObject? = null,
    val hysteria: JsonObject? = null, val shadowtls: JsonObject? = null,
    @SerialName("quic_tls") val quicTls: JsonObject? = null, val naive: JsonObject? = null,
    @SerialName("custom_outbound") val customOutbound: JsonElement? = null,
    @SerialName("custom_config") val customConfig: JsonElement? = null,
)
@Serializable data class Stream(
    val net: String? = null, val sec: String? = null, @SerialName("pac_enc") val pacEnc: String? = null,
    val path: String? = null, val host: String? = null, @SerialName("h_type") val hType: String? = null,
    val sni: String? = null, val alpn: String? = null, val cert: String? = null, val utls: String? = null,
    val insecure: Boolean? = null, @SerialName("ed_name") val edName: String? = null,
    @SerialName("ed_len") val edLen: Int? = null, val pbk: String? = null, val sid: String? = null,
    val spx: String? = null, @SerialName("mux_s") val muxS: Int? = null,
)
```

Use nullable fields with `explicitNulls=false` to get omitempty. Do not write `false`, `0` or `""` for unset values; collapse them to `null`, matching `NodeEditor.setAt` (`:62`). The exceptions are the non-omitempty keys `protocol`, `server`, `port`, `wireguard.private_key`, `wireguard.peer_public_key`, `wireguard.local_address` and `shadowtls.ss_password`.

Swift: same shape, using `Codable` with an `AnyCodable`/`JSONValue` enum for the `node`, `settings`, `ui` and `res` objects:

```swift
struct DataFile: Codable { var nodes: [NodeEntry]; var groups: [Group]
    var settings: JSONValue; var ui: JSONValue
    var nextId: Int; enum CodingKeys: String, CodingKey { case nodes, groups, settings, ui, nextId = "next_id" } }
struct NodeEntry: Codable { var id: Int; var group: String; var lat: Int = 0
    var res: [String: JSONValue]? ; var node: JSONValue }
struct Group: Codable { var name: String; var subUrl: String?; var subIntervalMin: Int?
    var updated: String?; var upload: Int64?; var download: Int64?; var total: Int64?; var expire: Int64?
    enum CodingKeys: String, CodingKey { case name, subUrl = "sub_url", subIntervalMin = "sub_interval_min",
        updated, upload, download, total, expire } }
```

For iOS, put data.json in the **App Group container**. The Packet Tunnel extension then receives only the rendered config, so the extension never parses data.json.

Exchange format: the full data.json is directly importable on all three platforms. For node-only exchange, use the same `NodeEntry` array, or the Go share links through `ShareLink`. Settings exchange should copy only the portable keys: `routing.*`, `mux.*`, `tun.{stack,mtu,ipv6,fake_dns}`, `log_level`, `fingerprint`, `skip_cert_verify`. Leave `inbound.*`, `apps`, `tun.interface_name`/`rule_process`, `clash_api_*` and the path fields as they are on the receiving device.