# Spec: running xray-core next to sing-box on mobile (Android + iOS)

**Short version:**
- On Android, the most reliable way to stop xray's traffic looping into the VPN is to exclude our own app from the VPN, with `RegisterDialerController` added as a second layer. This matters because xray resolves DNS through the system resolver, which no socket hook can catch.
- xray-core v1.260327.0 already contains a working TUN inbound (`proxy/tun`) that takes the Android or iOS tunnel fd. It is already compiled into our binary. That is an alternative to putting sing-box in front of xray.
- Linking both engines roughly doubles the Go library size (about 30 MB per engine per ABI after stripping).

All file:line references are to the paths given in the task. `XR` = `C:/Users/Administrator/go/pkg/mod/github.com/xtls/xray-core@v1.260327.0`, `HP` = `C:/Users/Administrator/Desktop/h.proxy/go`.

---

## 1. Stopping xray's own sockets from looping into the VPN

### How xray opens outgoing sockets
- All outgoing connections go through one process-wide variable: `var effectiveSystemDialer SystemDialer = &DefaultSystemDialer{}` (`XR/transport/internet/system_dialer.go:15`).
- It is used by `DialSystem` (`dialer.go:244,282`) and by happy-eyeballs (`happy_eyeballs.go:159`).
- This covers the freedom/direct outbound, every proxy outbound, the wireguard bind, and the TCP and DoH DNS servers (`app/dns/nameserver_tcp.go:67`, `nameserver_doh.go:96`).

### API to use
```go
// XR/transport/internet/system_dialer.go:207
func RegisterDialerController(ctl control.Func) error
// sing/common/control/interface.go:10
type Func = func(network, address string, conn syscall.RawConn) error
```
Recommended wiring in `go/mobile`:
```go
var protectOnce sync.Once
func installProtect(p Protector /* gomobile iface: Protect(fd int32) bool */) {
    protectOnce.Do(func() {
        _ = internet.RegisterDialerController(func(_, _ string, c syscall.RawConn) error {
            return control.Raw(c, func(fd uintptr) error {
                if !p.Protect(int32(fd)) { return errors.New("protect failed") }
                return nil
            })
        })
    })
}
```

### When to call it and what to watch for
- **Call it once per process, before the first `core.New`.** That includes probe instances created by `Core.Dialer(cfg)` (`HP/core/xray/xray.go:210-216`), which share the same global dialer.
- **It cannot be undone.** Controllers are only ever appended (`system_dialer.go:217`). Calling it on every Start piles up duplicates, so it must sit behind a `sync.Once`.
- **The Protector must stay valid for the life of the process.** After the VpnService stops, `protect()` returns false. That is harmless because of the next point.
- **A failed protect does not stop the connection.** Controller errors are only logged (`system_dialer.go:68-70` for UDP, `:125-127` for TCP), and the connection then goes into the tunnel and loops. Count or log failures in Go.
- **The controller must call `c.Control` itself** (`control.Raw` does this). The dialer calls controllers outside `c.Control` (`:66-79`, `:123-141`).
- **Never call `UseAlternativeSystemDialer`.** It silently disables `RegisterDialerController` (`:212-215` returns an error for a non-default dialer). Its `SimpleSystemDialer` also ignores sockopt (`:183-185`).
- `RegisterListenerController` (`system_listener.go:189`) is not needed: the listeners are on loopback.

### Gaps that protect() cannot cover (the main Android risk)
- **xray's `localhost` DNS server** uses `net.LookupIP` (`XR/features/dns/localdns/client.go:24`). On Android, Go uses the cgo resolver (`GOROOT/src/net/conf.go:185`, "DNS requests don't work on Android, so prefer the cgo resolver"). That resolver goes through bionic/netd, which follows the per-UID VPN routing. The renderer always appends `"localhost"` to the DNS server list (`HP/render/xray/xray.go:441`).
- **Proxy servers given as a domain name, and freedom with the default `AsIs`,** are resolved through the same system resolver.
- **The result is a loop:** the query enters the TUN, goes to sing-box, then to xray `dns-out`, which needs the proxy, which needs that same resolution.
- **`pinnedCert`** opens a plain `net.Dialer` at render time (`HP/render/xray/xray.go:516-517`), which no xray controller can cover.

### Recommendation for Android
Exclude our own package from the VPN. This covers every socket and every DNS lookup in the process, for both engines.
- **Global mode, or a "disallow these apps" list:** call `Builder.addDisallowedApplication(packageName)`. With sing-box this is `"exclude_package": ["<own pkg>"]` on the tun inbound.
- **"Allow only these apps" list:** our own package is simply never added. Note that Android does not allow `addAllowedApplication` and `addDisallowedApplication` together.

Keep the protect controller anyway; it costs nothing as a second layer.

### iOS
Not needed. Sockets opened by the NEPacketTunnelProvider process are not sent through its own tunnel, and this extension is where both engines run. This needs a check on a real device if `includeAllNetworks` is turned on. Use the same `sync.Once` hook only if testing shows a leak, binding sockets to the physical interface with `IP_BOUND_IF`.

---

## 2. Config for this mode

### Required changes to render/xray
- **Allow TUN in this mode.** `Render` currently refuses TUN (`HP/render/xray/xray.go:326-328`). Add a mode or flag (for example `render.ModeMobileTUN`) that keeps the TUN settings but renders the loopback inbound.
- **Inbound:** keep `"mixed-in"`. The DNS rule depends on that tag (`:464`). Use `listen: 127.0.0.1`, a random free port, and `udp: true`. Add a random user/password, because any app on the device can reach loopback. With a password set, xray only accepts UDP from source IPs that logged in over TCP (`XR/proxy/socks/server.go:49,173,189`). The UDP ASSOCIATE reply uses the local address by default (`XR/proxy/socks/protocol.go:194-201`), which is fine on 127.0.0.1.
- **Sniffing must be on in this mode** (the code at `:350-355` skips it when the user disabled it). Without it xray only ever sees IP addresses from the TUN, so no domain rule can match. Use `routeOnly: true` unless the user picked "for destination".
- **DNS:** do not hijack DNS in sing-box. Let UDP port 53 reach xray, where the existing rule (`:464`) sends it to `dns-out`. In mobile mode, drop `"localhost"` (`:441`) unless our own app is excluded from the VPN.
- **Freedom:** `direct`/`bypass` (`:402-403`) should get `"settings":{"domainStrategy":"UseIP"}` when the sniffed destination is used, so resolution goes through xray's DNS and not the system resolver.
- **FakeDNS (optional):** map `set.TUN.FakeDNS` to xray fakedns. The `app/dns/fakedns` package is already imported (`HP/core/xray/xray.go:22`).
- **Per-app routing** (`set.Apps`, `TUN.RuleProcess`) cannot be done in xray. On Android it goes into the VpnService builder (include/exclude package lists). On iOS it is not available.

### xray config sketch
```json
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "tag": "mixed-in", "protocol": "mixed", "listen": "127.0.0.1", "port": 47011,
    "settings": {"auth": "password", "accounts": [{"user": "r4nd", "pass": "r4nd"}], "udp": true},
    "sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true}
  }],
  "outbounds": [
    {"tag": "proxy", "protocol": "vless", "...": "..."},
    {"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "UseIP"}},
    {"tag": "bypass", "protocol": "freedom", "settings": {"domainStrategy": "UseIP"}},
    {"tag": "block",  "protocol": "blackhole"},
    {"tag": "dns-out","protocol": "dns"}
  ],
  "dns": {"tag": "dns-in", "servers": [
    {"address": "https://dns.google/dns-query", "domains": ["..."]},
    {"address": "https://doh.pub/dns-query", "skipFallback": true, "domains": ["geosite:cn", "full:<server>"]}
  ]},
  "routing": {"domainStrategy": "AsIs", "rules": [
    {"type": "field", "inboundTag": ["mixed-in"], "port": 53, "network": "udp", "outboundTag": "dns-out"},
    "...the rest exactly as rendered at :465-489..."
  ]}
}
```
FakeDNS variant:
- Add `"fakedns": [{"ipPool": "198.18.0.0/15", "poolSize": 65535}]`.
- Put `{"address": "fakedns", "domains": [<proxy domains>]}` first in `dns.servers`.
- Change sniffing to `"destOverride": ["http", "tls", "quic", "fakedns"], "routeOnly": false`. The fake addresses have to be swapped back for real domains.

### sing-box front config sketch
```json
{
  "log": {"level": "warn"},
  "inbounds": [{
    "type": "tun", "tag": "tun-in",
    "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
    "mtu": 1500, "auto_route": true, "stack": "mixed",
    "exclude_package": ["<own package>"]
  }],
  "outbounds": [{
    "type": "socks", "tag": "xray", "server": "127.0.0.1", "server_port": 47011,
    "version": "5", "username": "r4nd", "password": "r4nd"
  }],
  "route": {
    "rules": [{"ip_cidr": ["224.0.0.0/3", "ff00::/8"], "action": "reject"}],
    "final": "xray"
  }
}
```
- Leave out `sniff` and `hijack-dns`. The current renderer adds both (`HP/render/singbox/singbox.go:661,663`).
- The DNS address given to VpnService / NE must fall inside the TUN range so that queries reach xray.
- Leave the socks `network` option unset so both TCP and UDP are allowed.
- **Order:** start xray first so the port is listening, then sing-box. Stop them in reverse.

### Alternative worth testing: xray's own TUN
xray's TUN inbound reads the fd from the env flag `xray.tun.fd`:
- Android: `XR/proxy/tun/tun_android.go:29`
- iOS: `tun_darwin.go:110-127`

It is already linked, because `main/json` pulls in `infra/conf`, which imports `proxy/tun` (`XR/infra/conf/tun.go:4`; registered at `infra/conf/xray.go:37`).
- **What it gives:** no socks hop and no second set of NAT tables. Route port 53 to `dns-out` as today. Per-app routing still comes from the VpnService builder.
- **Limits:** no ICMP (`XR/proxy/tun/README.md`, "LIMITATION"), and the fd is passed through a process-wide environment variable.

---

## 3. geoip.dat / geosite.dat location and size

**How the location is chosen:**
- `GetAssetLocation` looks at env `xray.location.asset`, then its normalised form `XRAY_LOCATION_ASSET` (`XR/common/platform/platform.go:13,43-54,72-74`).
- If neither is set, it uses the executable's directory (`others.go:17`), then tries `/usr/local/share/xray`, `/usr/share/xray` and `/opt/share/xray` (`others.go:19-24`).
- On Android the executable is `app_process`, so the default is always wrong.

**What this means on mobile:**
- `AssetDir` must always be set. `Core.Start` already calls `os.Setenv("XRAY_LOCATION_ASSET", …)` before `LoadConfig` (`HP/core/xray/xray.go:168-171`).
- The variable is read on every lookup, not cached, and the geo files are read while the config is loaded (`infra/conf/router.go:181-218`).
- The variable is process-wide. Probe instances (`xray.go:210-216`) don't set it, but test configs have no rules (`render/xray/xray.go:412`).

**Where to keep the files:**
- Android: `context.filesDir`.
- iOS: the App Group container, read by the extension.

**How the files are loaded:**
- xray scans the file with a 64 KB buffer until it finds the requested code (`router.go:181-191`, `find` at `:238+`). Only that one entry is decoded, so memory stays small, but every `geosite:`/`geoip:` reference rescans the file at start.
- Building the domain matcher afterwards (`app/router/config.go:110-131`) is where geosite:cn costs heap memory.
- `xray.mph.cache` (`platform.go:28`) can load a prebuilt matcher instead (`router/config.go:114-120`). But it needs a `ruleTag` on every rule, and it also changes DNS (`app/dns/dns.go:90`). Treat it as an optimisation for later.

**Size:**
- The current files are `geoip.dat` 16,570,425 B and `geosite.dat` 11,384,991 B, about 28 MB together (`app/installer/stage/`).
- The default rules only use `geosite:cn`, `geosite:private`, `geoip:cn` and `geoip:private` (`app/src/store.cpp:42-43`).
- Options: download the files on first run, or ship trimmed cn+private .dat files and download the full set when the user adds other codes.

---

## 4. Traffic stats

- `withStats` turns on outbound uplink/downlink counting (`HP/core/xray/xray.go:81-114`). It only runs if `len(opts.StatsOutbounds) > 0` (`:171`), so mobile must pass the tags.
- Counters are created per outbound tag when the outbound starts (`XR/app/proxyman/outbound/handler.go:35-56`), so they exist from the start.
- `Stats(tag)` reads `outbound>>>tag>>>traffic>>>uplink/downlink` (`xray.go:226-243`). The values are running totals (`features/stats/stats.go:17`), so the UI computes speed from the difference between polls.
- **Proxy traffic:** the `proxy` tag (`render.Tag`, `HP/render/render.go:189-194`). Chained hops are `hop-N` (`StatsTags`, `render/xray/xray.go:395`).
- **Direct traffic:** `direct` + `bypass`. **DNS:** `dns-out`.
- The sing-box `xray` socks outbound sees total traffic (proxy + direct + DNS). Use it only as an overall total and never add it to xray's numbers, or traffic is counted twice.
- Counters measure payload bytes, not bytes on the wire.

---

## 5. Risks

### Binary size
I built scratch binaries for `android/arm64` with `CGO_ENABLED=0`, the `build_core.sh:16` tags and `-s -w`:

| Build | Stripped | gzip -9 |
| :-- | --: | --: |
| xray only (imports from `xray.go:14-59`) | 32.6 MB | 11.0 MB |
| sing-box only (box + include + v2rayapi) | 29.1 MB | 9.8 MB |
| full desktop core (both engines + gRPC) | 61.8 MB | 20.3 MB |

- Almost nothing is shared between the two. There are two gvisor copies (`go.mod:139` sagernet, `:199` gvisor.dev) and two wireguard-go copies (`:157`, `:195`).
- xray's `infra/conf` also pulls in hysteria/apernet quic-go and `proxy/tun`.
- Expect about 60 MB per ABI. Ship an AAB or per-ABI splits; three ABIs in one APK is about 180 MB of `.so`.

### iOS memory
- The Network Extension limit is about 50 MB. Code pages are file-backed, but the Go heap, two routers and DNS caches, gvisor buffers and the geosite:cn matcher all count.
- Set `debug.SetMemoryLimit` to about 35 MB and a lower GOGC, measure with Instruments, and consider xray's own TUN on iOS.

### UDP between gvisor and xray
- **Large packets get cut.** xray's buffer is `buf.Size = 8192` (`XR/common/buf/buffer.go:13`), and the socks UDP path reads into one buffer per packet (`server.go:231-233`). The default TUN MTU is 9000 (`HP/render/render.go`, `Defaults`), so set MTU to 1500 in this mode.
- **Extra cost per UDP flow.** sing-box's socks outbound opens one UDP ASSOCIATE per flow: a TCP control connection plus a UDP socket on loopback. DNS bursts therefore cost file descriptors. If that matters, send port 53 to a dedicated dokodemo-door UDP inbound in xray instead.
- **Session lifetime.** A UDP session lasts until its TCP control connection closes (sing-box UDP timeout, about 5 minutes).
- **QUIC** works over socks UDP but is slower. Offer an option to block UDP 443.
- **No ping.** ICMP is not proxied in either path.