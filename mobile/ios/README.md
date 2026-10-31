# DuoBao Proxy · iOS

SwiftUI app plus a Packet Tunnel extension, following `mobile/docs/DESIGN.md` §5.
**This code was written on Windows and has never been compiled.** Expect a round of
compile fixes the first time it is built on a Mac (mostly gomobile's Swift names).

## Layout

| Path | What |
| --- | --- |
| `project.yml` | XcodeGen spec: app `com.duobao.proxy`, extension `com.duobao.proxy.tunnel`, App Group `group.com.duobao.proxy` |
| `Shared/` | Code used by both targets: JSON value type, App Group paths, app ⇄ extension messages, the Swift wrapper of the Go core (`CoreBridge.swift`), and app-only data code (`Store`, `Prefs`, `Rules`, `Countries`) |
| `PacketTunnel/` | `NEPacketTunnelProvider`: starts the Go engine with the config the app rendered; implements the Go `Host` (utun settings, network monitor) |
| `App/` | The app: `AppModel` (controller, port of Android `AppController`), `TunnelController` (VPN profile), views |
| `App/Resources/` | `countries.txt` (map shapes, same file as Android), asset catalog (logo, icon) |
| `Frameworks/` | `Dbcore.xcframework`, built from `go/mobile` (not in git) |

## How it works

1. The app renders the config in-process (`PrepareSettings` + `RenderConfig`, same Go code as Android and desktop).
2. It starts the tunnel with the config in the start options.
3. The extension calls `Setup` + `Start` with itself as the `Host`. `OpenTun` applies
   `NEPacketTunnelNetworkSettings` and returns `-1`; Go finds the utun fd itself (`go/mobile/tun_darwin.go`).
4. The app asks the extension for traffic and live latency with `sendProviderMessage` (`TunnelRequest` / `TunnelReply`).
5. `data.json` lives in the App Group container, same schema as desktop and Android. Logs go to `tunnel.log` there.

iOS-specific settings: 按需连接 (on-demand rule), 排除本地网络 (`excludeLocalNetworks`),
Kill Switch (`includeAllNetworks`). There is no per-app routing and no local proxy port on iOS.

## Build on a Mac

Requirements: Xcode 16+, Go (version from `go/go.mod`), Homebrew.

```bash
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
gomobile init
brew install xcodegen
```

```bash
TARGET=ios bash go/build_mobile.sh      # → mobile/ios/Frameworks/Dbcore.xcframework
cd mobile/ios && xcodegen               # → DuoBaoProxy.xcodeproj
open DuoBaoProxy.xcodeproj
```

In Xcode, set your Team on both targets (or `DEVELOPMENT_TEAM` in `project.yml`). Your Apple
developer account needs the **Network Extensions** capability and the App Group
`group.com.duobao.proxy`. A VPN only runs on a real device, not in the simulator.

Without a Mac, the `iOS build` GitHub Actions workflow (`.github/workflows/ios.yml`) does the
same steps on a macOS runner and builds for the simulator without signing, which is enough to
see the compile errors.

## Known gaps vs Android

- Node editor (手动添加 takes a share link instead of a field-by-field form).
- Notifications for 订阅到期 / 流量不足 are stored as settings but not scheduled yet.
- 自动切换最快节点 is a stored setting; the switching loop is not ported yet.
- The app icon is the 256 px logo scaled up; replace `AppIcon.appiconset/icon-1024.png` with a real 1024 px icon.
