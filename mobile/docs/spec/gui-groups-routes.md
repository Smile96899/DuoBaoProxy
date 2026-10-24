# Spec: groups, subscriptions, routing, app routing and connections (desktop behaviour, and how it maps to mobile)

Line numbers are for the files as they are in the repo. Chinese labels are quoted verbatim.

---

## 1. Groups and subscriptions

### 1.1 Data model (`data.json` → `groups[]`)
`Store` keeps `groups` as an ordered list of plain maps, saved as a whole on every change (`app/src/store.cpp:24,55-68`). There is no schema. These are the fields actually used:

| key | type | written by | meaning |
|---|---|---|---|
| `name` | string | editor / `App.createGroup` | Primary key. Nodes point to it by string (`nodes[].group`). |
| `sub_url` | string | editor | Empty means a manual group (`GroupsPage.qml:44,55`). |
| `sub_interval_min` | int | editor | Auto-update interval in minutes; 0 turns it off. Validator 0..1440 (`GroupsPage.qml:47`). |
| `updated` | ISO-8601 string | update | `new Date().toISOString()` (`GroupsPage.qml:23`). |
| `upload`, `download`, `total`, `expire` | number | update | Copied from `subscription-userinfo` (§1.3). `expire` is unix seconds. |

- If `groups` is empty on first load, it is seeded with `{name:"默认"}` (`store.cpp:30`).
- `"默认"` is protected by `App.removeGroups`/`mergeGroups` (`App.qml:133,142-156`). Tray and clipboard imports land there (`App.qml:183,190-194`).
- Node entries look like `{id, group, lat, node:{…}}` (`store.cpp:74,85`).

### 1.2 Create / edit / delete / reorder

There are two UIs for this.

**GroupsPage "分组与订阅"** (`GroupsPage.qml`)
- Header: title "分组与订阅", a "全部更新" button that calls `updateGroup` for every group (manual ones are skipped because `sub_url` is empty, line 15), and a `RoundAdd` button that opens the editor empty (`:66-72`).
- Editor popup (`:35-61`):
  - Title is "添加分组" or "编辑分组".
  - Fields: "名称", "订阅链接（留空则为手动分组）", and "自动更新间隔（分钟，0 关闭）".
  - Buttons: "删除分组" (edit only), "取消", "保存". "保存" is enabled when the trimmed name is not empty.
  - Save calls `store.updateGroup(oldName, {name, sub_url, sub_interval_min})` or `store.addGroup(patch)` (`:53-57`).
  - `Store::addGroup` silently ignores a duplicate name (`store.cpp:129-134`). `updateGroup` patches by name with no duplicate check (`store.cpp:136-146`).
- Delete from the editor calls `store.removeGroup` directly, which also deletes all nodes in the group (`store.cpp:148-154`). There is no confirmation, no "默认" guard and no selection cleanup (`GroupsPage.qml:50`).
- Rows (`:81-141`):
  - A drag handle "≡" reorders rows. On release the nearest other row centre becomes the target, then `store.moveGroup(from,to)` runs, which is `QList::move` (`:96-117`, `store.cpp:156-161`).
  - Each row shows the name, a detail line, a used/total bar, an "编辑" button and an "更新" button (the latter only when `sub_url` is set).

**NodesPage group-header context menu** (`NodesPage.qml:98-176`)
- Menu items: "从剪贴板导入节点", "新建分组", "移到其他分组并删除" (submenu of target groups), "选择此分组"/"取消选择此分组", "全选分组", "删除分组" (with a count suffix "（N 个）" when several are picked).
- "新建分组" popup: placeholder "分组名称"; shows "已有同名分组" on conflict; buttons "取消"/"创建". It calls `App.createGroup`, which trims, rejects empty or duplicate names, and adds `{name}` (`App.qml:134-139`).
- Delete confirmation: title "删除分组「X」？" or "删除 N 个分组？". Body: "其中的 N 个节点会一起删除，无法恢复。" plus "「默认」分组不会删除。" when it applies. Then `App.removeGroups`, which filters out "默认" and calls `dropMissingSelection` (`App.qml:141-147`).
- Merge: `App.mergeGroups(names,target)` reassigns every node to the target, then removes the source groups except "默认" (`App.qml:149-156`).
- Group drag reorder in NodesPage also uses `store.moveGroup` (`NodesPage.qml:429`). NodesPage lists groups in store order, empty ones included. Nodes whose `group` matches no group still appear under a pseudo-group with that name (`NodesPage.qml:287-301`).

### 1.3 Subscription fetch (`GroupsPage.qml:14-33`)
- It is a plain QML `XMLHttpRequest`, so the fetch lives in the GUI, not the Go core (comment at `:6-7`). The request is `GET sub_url`.
- The only custom header is `User-Agent: DuoBaoProxy/<Qt.application.version>`. The version comes from `DBP_VERSION` = CMake `PROJECT_VERSION` (`main.cpp:36`, `CMakeLists.txt:2,83`), so currently `DuoBaoProxy/1.0`.
- There is no timeout, no retry, and no proxying through the running core. Redirects follow XHR defaults.
- A status other than 200 logs `订阅 <name> 更新失败：HTTP <status>` and stops.
- **`subscription-userinfo` parsing** (`:22-27`):
  - The header value is split on `;`. Each part is trimmed and split on `=`. A part is used only when it yields exactly 2 pieces, and then `patch[key] = Number(value)`.
  - Any key is copied as is, so the standard `upload`, `download`, `total` and `expire` all land in the group. A non-numeric value becomes `NaN`. Keys are not lowercased.
  - `updated` is always set.
- **Body parsing** is done by the core: `App.importText(body, groupName)` → gRPC `ImportNodes` → `node.Import` (`App.qml:183`, `go/node/import.go:22-54`). The rules, in order:
  1. Trim the body.
  2. If it has no `://`, try base64. The decoded text is used only if it contains `://` (`:28-32`).
  3. If the text contains `proxies:`, parse it as Clash/Clash.Meta YAML (`:33-40`, `importClash` `:145`).
  4. Otherwise treat it as share links, one per line. `#` comments and blank lines are skipped. A line that fails adds an error of the form `"<first 40 chars>…: err"` (`:41-52`).
  - sing-box JSON subscriptions are **not** supported.

### 1.4 Update behaviour: nodes are replaced, not merged
The order is: `store.updateGroup(name, patch)` → `store.removeGroupNodes(name)` → `App.importText(body, name)`. The import is asynchronous, and `onImported` → `store.addNodes(nodes, App.importGroup)` assigns fresh ids (`GroupsPage.qml:28-30`, `App.qml:263-270`, `store.cpp:80-90,111-116`).

Consequences the mobile port should fix rather than copy:
- **Ids, latency and test results are lost** on every update. If the selected node was in the group, `selectedId` now points to a deleted id. `dropMissingSelection` is not called. `onImported` only auto-selects when `selectedId<0`.
- **The group empties itself when parsing fails.** Nodes are deleted before parsing, so a body that yields 0 nodes, or a core-not-ready error (`core_client.cpp:229-236`), leaves the group empty.
- **Race:** `App.importGroup` is a single global (`App.qml:181-183`). "全部更新" fires all XHRs in parallel. Two imports in flight can put nodes into the wrong group.
- **Renaming a group orphans its nodes.** `updateGroup` changes `name` but not `nodes[].group`.
- **Auto-update only runs while the groups page is open.** The 60 s `Timer` (`GroupsPage.qml:146-153`) lives inside `GroupsPage`. That page is created by a `Loader` only while it is the current page (`Landscape.qml:60-71`, `Portrait.qml:21-31`). The rule is: update when `now - updated > sub_interval_min*60000` (if `updated` is missing, it counts as 0).

Recommended mobile behaviour:
- Fetch in Go (`go/mobile`) with the same UA, optionally through the proxy.
- Parse first. Replace only if at least 1 node was parsed.
- Keep ids and latency of nodes whose (protocol, server, port, credentials) match.
- Fix selection.
- Run scheduled updates with WorkManager or BGAppRefresh.
- Rename nodes' `group` together with the group.

### 1.5 Per-group display (`GroupsPage.qml:122-136`)
- Line 1: the group name.
- Line 2: `"<n> 个节点"`, and for subscription groups additionally:
  - `" · 已用 " + bytes(upload+download) + " / " + bytes(total)` when `total` is set;
  - `" · " + yyyy-MM-dd(expire*1000) + " 到期"` when `expire` is set;
  - `" · 更新于 " + MM-dd HH:mm(updated)`, or `" · 未更新"`.
  - `Theme.bytes` does the formatting.
- Bar: 4 px tall, shown when `total` is set. Fill = `min(1,(up+down)/total)`, `fg2` on `panel2`.

---

## 2. Routing page "路由" (`RoutesPage.qml`)

### 2.1 Header (`:21-40`)
- Title "路由" (hidden when compact).
- `Seg` with two tabs: "规则分流" and "应用分流 <N>" (N = `settings.apps.length`). They switch a `StackLayout` between the rule lists and an embedded `AppsPage` (`:44-83`).
- Compact (portrait) only: a proxy-mode `Dropdown` with "规则"/"全局"/"直连" (`ui.proxy_mode`: `rule|global|direct`, default `global`, `store.cpp:36`). It runs `setUi` then `App.reconnect()` (`:32-37`).
- Landscape only: a "导出 JSON" button (§2.6).

### 2.2 Rule lists (`settings.routing.*`, all `string[]`)
They are laid out in a grid of 6 `RuleBox`es: 1, 2 or 3 columns depending on width; box height 140, or 110 when compact (`:58-68`).

| title | key | first-run default (`store.cpp:41-47`) |
|---|---|---|
| "走代理 · 域名" | `routing.proxy_domain` | — |
| "直连 · 域名" | `routing.direct_domain` | `geosite:cn`, `geosite:private` |
| "拦截 · 域名" | `routing.block_domain` | `geosite:category-ads-all` |
| "走代理 · IP" | `routing.proxy_ip` | — |
| "直连 · IP" | `routing.direct_ip` | `geoip:cn`, `geoip:private` |
| "拦截 · IP" | `routing.block_ip` | — |

These defaults are applied only when the whole `settings` object is empty.

**RuleBox** (`components/RuleBox.qml`):
- It is a multiline monospace `TextArea` that does not wrap, showing `lines.join("\n")` (`:24`).
- Save rules (`:32-38`): split on lines, replace `。` with `.`, trim, drop empty lines. `store.setSetting(key, list)` runs only if the list changed. The save fires on `editingFinished` and 600 ms after typing stops.
- `#` lines are stored as typed and ignored by the parser.

**Accepted syntax** (`go/render/render.go:214-256`)
- Domain lines are v2fly style and matched case-insensitively:
  - `geosite:<name>`
  - `full:<d>` (exact)
  - `domain:<d>` (suffix)
  - `keyword:<s>`
  - `regexp:<re>` (case is kept)
  - a bare value is treated as a suffix.
- IP lines: `geoip:<cc>`; anything else is passed through as a CIDR with no validation.

**Rendering**
- sing-box (`singbox.go:416-483`):
  - `full`→`domain`, `domain`→`domain_suffix`, `keyword`→`domain_keyword`, `regexp`→`domain_regex`.
  - `geosite:x` becomes a remote rule set `geosite-x` (`.srs` from `raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/…`, `download_detour:"direct"`, `update_interval:"7d"`). `geoip:x` works the same way from sing-geoip.
  - `geoip:private` becomes `ip_is_private:true`.
  - Mobile notes: GitHub raw may be unreachable on Chinese networks without the proxy. `RuleSetDir` is set but unused (`:599`). The cache file goes to `os.TempDir()` (`core/singbox/singbox.go:87`), which needs a writable app path on mobile.
- xray (`xray.go:274-303`): strings are re-prefixed (`full:`/`domain:`/`keyword:`/`regexp:`/`geosite:`, `geoip:`). It needs `geoip.dat`/`geosite.dat` in `XRAY_LOCATION_ASSET`, which is set to `sys.appDir()` (`core/xray/xray.go:168-170`, `App.qml:235`).

### 2.3 Rule order (who wins)
- sing-box (`singbox.go:652-705`):
  1. `sniff` (if `inbound.sniff≠0`)
  2. `protocol:dns → hijack-dns`
  3. `routing.custom_rules` (raw JSON array)
  4. block domain → reject
  5. block IP → reject
  6. apps (§3)
  7. proxy domain
  8. direct domain → `bypass`
  9. proxy IP
  10. direct IP → `bypass`
  11. reject UDP 135/137/138/139/5353
  12. reject multicast dst/src
  13. TUN only: `tun.rule_process`/`rule_cidr` (out `bypass`, or `proxy` if `rule_white`)
  14. `route.final`
- xray (`xray.go:461-490`):
  1. mixed-in UDP 53 → `dns-out`
  2. custom rules
  3. block domain
  4. proxy domain
  5. direct domain
  6. block IP
  7. proxy IP
  8. direct IP
  9. UDP NetBIOS/mDNS → block
  10. multicast → block
  11. catch-all `tcp,udp` → final
  - There are no app rules. The order also differs from sing-box: domain lists come before block IP.

### 2.4 "兜底与 DNS" section (`RoutesPage.qml:70-78`)

| label | key | control | default |
|---|---|---|---|
| "未匹配流量" | `routing.final` | Dropdown: "代理"=`proxy`, "直连"=`bypass` | `proxy`. Go also accepts `block`, which sing-box maps to `bypass` (`singbox.go:698-701`). |
| "远程 DNS" | `routing.remote_dns` | Input, saved on editingFinished | `https://dns.google/dns-query` (`store.cpp:46`, `render.go:134-136`) |
| "直连 DNS" | `routing.direct_dns` | Input | `https://doh.pub/dns-query` |
| "DNS 分流" | `routing.dns_routing` | Toggle | `true` |

**DNS address syntax**
- sing-box (`singbox.go:353-407`) accepts:
  - `local`/`localhost`/empty → `local`;
  - `dhcp://auto|<iface>`;
  - `fakeip`;
  - `udp|tcp|tls|https|quic|h3://host[:port][/path]` (`http` is treated as `https`);
  - a bare host, which becomes `udp://`.
- Remote DNS detours through `proxy`. Direct DNS uses no detour. Both resolve their own hostname through `dns-local` (`underlying_dns`, default `local`).
- xray passes the string through unchanged (`xray.go:424-425`), so the sing-box-only forms (`local`, `dhcp://`, `fakeip`) do not apply there.

**DNS rules**
- sing-box (`singbox.go:615-642`):
  - With `dns_routing` on: proxy-domain list → `dns-remote`, direct-domain list → `dns-direct`.
  - Server domains of the node chain always go to `dns-direct`.
  - Reject query types 32/33 and `*.lan`.
  - TUN with `fake_dns` adds a fakeip server and rule.
  - `final` is `dns-remote`, or `dns-direct` if `dns_final_out=bypass`.
  - `independent_cache:true`.
- xray: `domains` are attached to the remote/direct servers. Server order follows `dns_final_out`. `localhost` is the last fallback (`xray.go:420-459`).
- Keys with no UI: `remote_dns_strategy`, `direct_dns_strategy`, `dns_final_out`, `underlying_dns`, `custom_dns` (replaces the whole dns object), `domain_strategy`, `outbound_domain_strategy`, `custom_rules`, `custom_inbounds`, `clash_api_port/secret` (`render.go:69-111`).

### 2.5 Sniff
- `settings.inbound.sniff`: 0 = off, 1 = for routing, 2 = override destination (`render.go:27-34`). The first-run default is `1` (`store.cpp:40`).
- **There is no UI for it.**
- sing-box: any value other than 0 adds `{"action":"sniff"}`. Mode 2 behaves the same as 1 (`singbox.go:658-662`).
- xray: `sniffing:{enabled, destOverride:[http,tls,quic], routeOnly: sniff==1}` on mixed-in (`xray.go:350-355`).

### 2.6 How the mode and "分流方式" settings change what is sent (`App.qml:72-95`)
`renderSettings()` copies `settings` and then adjusts it:
- `proxy_mode=global`: clears `proxy_domain`, `direct_domain`, `proxy_ip`, `direct_ip` and `custom_rules`, and sets `final=proxy`. Block lists and apps stay.
- `proxy_mode=direct`: `final=bypass`; the lists are kept.
- `ui.route_parts` ("分流方式" in SettingsPage: "规则分流"=`rules` / "应用分流"=`apps` / "同时生效"=`both`, default `both`; `SettingsPage.qml:57-58`):
  - `rules` clears `apps`;
  - `apps` clears all six lists and `custom_rules`.
  - A change triggers a reconnect (`App.qml:95`).
- `tun.enabled = ui.mode==="tun" && core==sing-box`.
- **Live apply:** `store.settingsChanged` starts an 800 ms debounce, then reconnects if the settings JSON differs from the one used at start (`App.qml:199-206`).

**"导出 JSON"** (`RoutesPage.qml:38-39,87-96`): calls `core.renderConfig(req, coreId, [selected node], renderSettings(), mode=2)`, which is `ModeExport` (`rpc/gen/libcore.pb.go:80-82`). It renders a full config and drops `auto_detect_interface` (`singbox.go:713-715`).
- Success copies the config to the clipboard and logs `配置已复制到剪贴板（<core>）`.
- An error logs `导出失败：<err>`.
- With no node selected, it fails with a node-JSON error.

---

## 3. App routing "应用分流" (`AppsPage.qml`)

### 3.1 Desktop
- **Storage:** `settings.apps = [{name, process, outbound}]` (`AppsPage.qml:17-19,29-32`; Go `AppRule` `render.go:154-161`). `outbound` is one of `proxy`, `bypass` or `block`.
- **What is stored:** the **exe basename only**. `addApp` stores `path.split("\\").pop()` (`:30-31`). That holds even for the picker, Browse, and manual input with a full path, so the `process_path` branch in Go (`singbox.go:759-769`) is never reached from the GUI. `name` is the shortcut name or the filename without `.exe`/`.lnk` (`:24`).
- **Ways to add:**
  1. `RoundAdd` button (Windows only) opens a "选择应用" picker over `sys.installedApps()`:
     - It scans `*.lnk` on the user and public Desktop and both Start Menus, recursively.
     - It skips names containing "uninstall"/"卸载", resolves targets, keeps existing `.exe` only, dedupes by path, and sorts by name (`system.cpp:237-268`).
     - Has a "搜索" box, already-added rows dimmed with "已添加" (case-insensitive basename), the empty text "没有找到应用", and a "浏览…" button that opens a file dialog "选择应用程序" (filters "程序或快捷方式 (*.exe *.lnk)", "所有文件 (*)").
     - A `.lnk` is resolved by `sys.resolveShortcut`. If it does not resolve, the error popup "这个快捷方式没有指向程序文件" appears (`AppsPage.qml:20-28,34-93`, `system.cpp:225-235`).
  2. "按进程名" popup: title "按进程名添加", placeholder "chrome.exe 或完整路径", buttons "取消"/"添加" (`:95-106`). `。`→`.` is normalised (`:22`). Manual and Browse do not dedupe.
- **Rows:** initial-letter avatar, name, process (mono), a `Seg` with "代理"/"直连"/"拦截", and a "×" remove button (`:132-147`).
- Header: "应用分流" plus the count, or "N 个应用" when embedded. Empty state: "还没有应用规则".
- Only sing-box is supported (`:15`). Otherwise a warning "`<core>` 内核不支持应用分流，切换到 sing-box 后生效。" is shown and the list is dimmed (`:120-131`).
- **Rendering:** the rules are grouped by outbound in first-seen order. Each group produces one `process_name` rule and one `process_path` rule, with `block` turned into `action:"reject"`. They sit after the block lists and before the proxy/direct lists (`singbox.go:667-673,742-773`). They work in TUN and in system-proxy mode for local connections (`AppsPage.qml:7-8`, `singbox.go:738-741`).

### 3.2 Android mapping
- Replace the exe basename with the **package name**. Extend `AppRule`, either with a new `package` field or with a platform-tagged `process` (the desktop's exe basename), so the shared `data.json` keeps both, and add `label` from PackageManager.
- **Picker:** `PackageManager.getInstalledApplications` with icon, label, package and a search box. It needs `QUERY_ALL_PACKAGES`, or a `<queries>` intent filter.
- **Rendering:** emit sing-box `package_name` rules (`sing-box@v1.14.3/option/rule.go:165`), not `process_name`, in the same position. Owner lookup goes through libbox `PlatformInterface.FindConnectionOwner` / `UseProcFS` (`experimental/libbox/platform.go:5-12`). The Kotlin side must implement them, using `ConnectivityManager.getConnectionOwnerUid` and a uid→package lookup.
- **Optional optimisation:** if every `bypass` app should skip the TUN entirely, use `VpnService.Builder.addDisallowedApplication`. sing-box `tun.exclude_package` / `include_package` (`option/tun.go:44-45`, libbox `TunOptions.GetIncludePackage/GetExcludePackage` `tun.go:32-33`) is the native path.
  - "拦截" must stay a route `reject` rule, because VpnService cannot block an app.
  - Always exclude the app's own package.
- The xray engine has no process or package matching. On Android, with sing-box handling the TUN and handing traffic to xray, app rules can still be applied in the sing-box layer. That is a design choice for `go/mobile`; the desktop disables app routing for xray.
- System-proxy mode does not exist on mobile, so app routing is VPN only.

### 3.3 iOS mapping
- iOS does not expose per-app routing to a Packet Tunnel; per-app VPN needs MDM.
- Hide the "应用分流" tab and the "分流方式" setting, and treat `route_parts` as `rules`.
- Keep `settings.apps` untouched on import/export so desktop and Android data survive a round trip.

---

## 4. ConnsPage "连接" (hidden)
- `ConnsPage.qml` is a placeholder. The header is "连接". The centred text depends on state:
  - xray: "xray 内核不提供连接列表。切换到 sing-box 查看。"
  - not connected: "未连接。"
  - otherwise: "连接列表接口尚未实现。"
- It has no data source: no RPC, no Clash API polling.
- It is **not routed**. `Landscape.qml`/`Portrait.qml` Loaders and the nav models (`Rail.qml:60-61`, `Portrait.qml:45`) only list `nodes/groups/routes/logs/settings`.
- A possible backend later is the sing-box Clash API (`clash_api_port`, `singbox.go:718-724`). Nothing calls it today.
- For mobile parity there is nothing to port. If it is wanted, it would be a new feature, using the libbox command server's connection list or the Clash API `/connections`.