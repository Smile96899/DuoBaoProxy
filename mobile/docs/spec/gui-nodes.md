# DuoBao Proxy: spec for the node UI and its flows (desktop reference for the mobile port)

All paths are relative to `C:/Users/Administrator/Desktop/h.proxy/`. Line numbers are from the current `main` (0a3b6a2). "NP" means `app/qml/pages/NodesPage.qml` and "App" means `app/qml/App.qml`.

---

## 0. Data model these screens use

- **Store file:** `data.json` in AppData, with keys `nodes`, `groups`, `settings`, `ui` and `next_id` (`app/src/store.cpp:12-27, 55-68`).
  - **Node entry:** `{id, group, lat, res?, node:{…}}`.
    - `node` is Go `node.Node` JSON (`go/node/node.go:119-158`).
    - `lat` is the last real-connection latency: `0` means untested, `-1` means failed (`app/qml/Tests.qml:135`).
    - `res[kind]` holds the result of each test kind (`Tests.qml:4-6, 129-138`).
  - **Group:** `{name, sub_url, sub_interval_min, updated, upload/download/total/expire…}` (`store.h:4`, `GroupsPage.qml:22-27`).
- **First-run defaults** (`store.cpp:30-50`):
  - `ui`: one group `默认`, `core=sing-box`, `selected=-1`, `proxy_mode=global`, `mode=system`.
  - `settings`: `inbound {127.0.0.1, 2080, sniff}`; routing that sends cn/private direct and blocks ads; `final=proxy`; remote DNS `https://dns.google/dns-query`, direct DNS `https://doh.pub/dns-query`; `tun {gvisor, mtu 9000}`; `mux {h2mux, 8, off}`.
- **Node order:** one global array. A group's list is that array filtered by `group` (NP:295-300). `moveNodes` only rewrites `group` and does not change array position (App:125).
- **No dedupe anywhere.** `addNodes` always appends with new ids (`store.cpp:80-90`).
- **`"默认"` (`App.defaultGroup`, App:133)** can never be deleted. The tray, clipboard import and new manual nodes land in it.

---

## 1. Nodes page: features and controls

### 1.1 Header row (NP:343-363)
- **Title:** `节点` followed by **`store.nodes.length`**. This is the total count, not the filtered one.
- **Search box** (placeholder `搜索名称 / 地址`), **hidden when `compact`**, i.e. in the portrait layout (NP:351; `Portrait.qml:57` passes `compact: true`). Portrait therefore has no search at all.
  - Matching: case-insensitive substring of `nodeName + server + group`, concatenated (NP:297).
  - While a query is active, empty groups are hidden. With no query, every group in `store.groups` order shows, empty ones included (NP:290-294).
- **Sort dropdown**, persisted in `ui.sort` (NP:356-360, 272). Options:
  - `默认排序` (`default`): store order.
  - `按延迟` (`latency`): `Tests.sortValue` on the **first latency kind among the currently shown result columns, else `url`** (NP:274). Failed or untested sorts last (`Infinity`, `Tests.qml:282-288`). `loss` sorts by `loss*100000+avg`.
  - `按速度` (`speed`): by `down` bps, descending.
  - `按地区` (`geo`): country code string; untested is `"~"`, which sorts last.
  - `按名称` (`name`): lowercased `nodeName`.
  - Sorting is applied **within each group** (NP:301). While any non-default sort is active, drag-reorder is disabled (NP:453, 491).
- **`+` button** (`RoundAdd`) opens the add menu (NP:323-336):
  - `导入链接 / 订阅` opens the ImportDialog with empty text (§3.1).
  - `手动添加` is a submenu listing every entry of `NodeEditor.protocols`: SOCKS5, HTTP, Shadowsocks, VMess, VLESS, Trojan, Hysteria2, TUIC, SSH, AnyTLS, WireGuard, Hysteria (v1), ShadowTLS. Each opens `nodeEditor.openNew(v)` (§2).

### 1.2 Selection bar (NP:367-373)
- Visible while any node or any group is picked.
- Text is `已选 N 个分组` if groups are picked, else `已选 N 个节点`.
- Button `取消选择` clears both selections.

### 1.3 Result-column header (NP:377-396)
- **When it shows:** only when `Tests.shownKinds` is non-empty. That is the kinds of the last non-quiet `Tests.run` (`Tests.qml:88-97`). It is empty at launch.
- **Content:** label `测试结果` on the left. On the right, one title per visible column using `Tests.kind(k).s`: `真连接 TCP ICMP UDP 丢包 下载 上传 地区 流媒体 AI DNS`.
- **`×` button:** sets `shownKinds = []`, which hides the columns. Results stay stored.
- **Fitting columns:** they fit from the left into `list.width - 238` px using each kind's `w + 12`. Columns that don't fit are dropped from the end (NP:261-271).

### 1.4 Group headers (NP:458-485)
- **Content:** a `CheckDot` (shown only while groups are picked), a `▸` that rotates 90° when expanded, the group name, and the count of shown items.
- **Left click:**
  - Normally toggles collapse. The collapse state lives only on the page (`collapsed`, NP:11) and is **not persisted**.
  - Ctrl+click toggles this group in the group selection.
  - Shift+click selects the range from the anchor group.
  - Any click while groups are picked toggles that group (NP:73-87).
- **Right click** opens the group menu (§1.7). If the header is part of a multi-group selection, the menu acts on all picked groups (NP:88-94).
- **Drag** (vertical, threshold 8 px) reorders groups via `store.moveGroup` to the slot of the nearest header (NP:419-430, 465-476).
- **Exclusivity:** picking groups clears node picks and vice versa (NP:27, 35, 71, 87, 89).

### 1.5 Node row (`NodeRow.qml`)
- **Height:** 44, or 54 when compact (`NodeRow.qml:29`).
- **Background:** accent-soft if it is the selected node (`entry.id === App.selectedId`), panel2 on hover, a 1.5 px border when picked (:24-27).
- **Unsupported node:** if the current core doesn't list the protocol, the row is drawn at opacity `.45` and shows the red text `<coreId> 不支持` (:22, 25, 60).
- **Left status dot:** 8 px. Shown when not compact and not selecting. Its colour is `Theme.lookColor(App.look)` (grey when off, green when on, red when TUN) if the row is the active node, otherwise fg3 (:49, `Theme.qml:38`).
  - While a multi-selection is active, a `CheckDot` replaces the dot (:50).
- **Text lines:**
  - Line 1 is `App.nodeName(n)`: the name, or `server:port` if the name is empty (`App.qml:48`).
  - Line 2 is a monospace protocol pill plus `server:port`. It uses raw `port`, not Go's `DisplayAddress`, so Hysteria2 hop ports are not shown (:57-59 vs `node.go:162-167`).
- **Result cells:** one per visible column (:64-80).
  - While queued or running, the cell shows `测试中…`.
  - Otherwise it shows `Tests.cell()` (`Tests.qml:224-245`):
    - Latency kinds: `"<ms> ms"`. Colour is good below 80, warn below 150, else bad (`Theme.qml:39-45`).
    - `loss`: `"<avg>ms · <pct>%"`. Bad above 20%, warn above 0.
    - Speeds: `x.x MB/s` or `N KB/s`.
    - `geo`: `"CC 中文国名"`.
    - `media`/`ai`: e.g. `N✓ D✗ Y?` / `GPT✓ Cl✓ Ge✗`.
    - `dns`: `泄漏` / `无泄漏`.
    - Errors: `超时` / `失败`, in bad colour.
- **Click** (NP:34-49):
  - A plain click calls `App.selectNode(id)`. It is ignored if the core doesn't support the node, and **reconnects if already connected** (App:61-68).
  - Ctrl+click, or any click while nodes are picked, toggles the node's pick.
  - Shift+click with an anchor replaces the selection with the range in on-screen order across groups.
- **Double-click** opens the editor for this node and clears picks (:42; NP:498).
- **Right click** opens the node menu. If the row is part of a multi-selection larger than one node, the menu acts on all picked nodes (NP:51-59).
- **Drag** (only in `default` sort): drops next to the nearest row (before or after) via `store.moveNode`, which adopts the target's group. It can also drop onto an empty or collapsed group header via `store.moveNodeToGroup`, which appends to the end of the array. If nothing is nearer than the row's own slot, the row snaps back (NP:432-457; `store.cpp:163-193`).
- **Empty list:** shows `还没有节点。点右上角「+」粘贴链接或订阅。` (NP:503-508).

### 1.6 Node context menu (NP:179-241)
When several nodes are picked, menu titles get the suffix `（N 个）` (`counted()`).

| Item | Enabled when | Action |
|---|---|---|
| `编辑` | single node | `nodeEditor.openFor(id)` |
| `选择并启动` | single, protocol supported, and not already (connected and selected) | `App.connectNode(id)`: select, then connect (or reconnect via select) (App:120-123) |
| `停止` | `connected && !busy` | `App.disconnect()` |
| `测试` › `真连接延迟` / `TCP 延迟` / `ICMP Ping` / `UDP 延迟` / `丢包 / 抖动` / — / `下载速度` / `上传速度` / `出口 IP 与地区` / — / `流媒体解锁` / `AI 服务可用性` / `DNS 泄漏检测` / — / `全部测试` | always | `Tests.run(ids, [kind])`; `全部测试` runs all 11 kinds |
| `分享` › `复制链接` / `显示二维码` | always | `App.shareNodes(ids, "link"\|"qr")` (§3.5) |
| `移动到分组` › one entry per group | a group is enabled if any target node isn't already in it | `App.moveNodes`, then clear picks; logs `已移动 N 个节点到「G」` |
| `选择此节点` / `取消选择此节点` | single | toggle pick |
| `全选本组` | always | adds every shown node of the right-clicked node's group to the picks (NP:26-31) |
| `删除` (red) | always | 1 node: deleted immediately, with no confirm. More than 1: popup `删除 N 个节点？` [`取消`] [`删除`] (NP:60-63, 243-256) |

`App.removeNodes` clears `selectedId` if it was deleted and logs `已删除 N 个节点` (App:126-130). It **does not stop a running core** whose node was deleted.

Quirk: deleting a single picked node through the no-confirm path does not clear `picked`. The stale id keeps the selection bar showing.

### 1.7 Group context menu (NP:99-135)
When several groups are picked, titles get the suffix `（N 个）`.

- **`从剪贴板导入节点`** (single group only): `App.importClipboard(group)`. It goes straight to import with no dialog (§3.2).
- **`新建分组`** opens a popup (NP:137-155):
  - Title `新建分组`, field placeholder `分组名称`.
  - Shows a red `已有同名分组` if the trimmed name already exists.
  - Buttons `取消` and `创建`. `创建` is enabled only when the trimmed name is non-empty and not taken. Enter submits.
  - Calls `App.createGroup`, which trims and rejects duplicates (App:134-139).
- **`移到其他分组并删除`** opens a submenu of all groups; the menu's own groups are disabled. `App.mergeGroups` moves every node into the target, then removes each source group except `默认` (App:149-156). Logs `已移动 N 个节点到「T」`.
- **`选择此分组` / `取消选择此分组`** (single group): toggles the group pick.
- **`全选分组`:** picks every shown group.
- **`删除分组`** (red): enabled when at least one non-`默认` group is targeted. It opens a confirm popup (NP:157-177):
  - Title: `删除分组「X」？` for one group, `删除 N 个分组？` for several.
  - Body: `其中的 K 个节点会一起删除，无法恢复。`, plus `「默认」分组不会删除。` if `默认` was among the targets.
  - Buttons: [`取消`] [`删除`].
  - `store.removeGroup` **deletes the group's nodes too** (`store.cpp:148-154`). Afterwards `dropMissingSelection()` runs (App:141-147). Logs `已删除 N 个分组`.

### 1.8 Keyboard (NP:96-97)
- `Ctrl+A` (StandardKey.SelectAll) picks every shown node.
- `Esc` clears both selections while any are active.

### 1.9 Building blocks
- **`Dropdown`:** a pill showing `<label>  ▾` that opens a `PickPopup` beneath it (`Dropdown.qml`).
- **`PickPopup`:** a vertical list. The current item is bold on a panel2 background. A click closes the popup and emits `picked(v)`. It closes on Esc or a press outside (`PickPopup.qml`).
- **`PillMenu`:** a styled `Menu`. Items can have `danger` (red text). Disabled items use fg3. Submenus show `›` (`PillMenu.qml`).
- **`Seg`:** a segmented control (`Seg.qml`).
- **`CheckDot`:** a 16 px circle that shows `✓` when checked.

### 1.10 Status card, portrait only (`StatusCard.qml`; used at `Portrait.qml:16`)
- Background is accent-soft when connected, panel2 otherwise.
- **Row 1:**
  - Logo `Mark` tinted by `App.look`.
  - **Core dropdown** listing `App.cores` ids. Picking one calls `App.setCore(v)`, which disconnects if connected **without reconnecting**, persists `ui.core`, and deselects the node if the new core doesn't support it (App:54-60).
  - On/off `Toggle`, disabled while `busy`, wired to `App.toggle()`.
- **Row 2:** node name. Shows `未选择节点` when nothing is selected.
- **Row 3**, three equal columns:
  - `App.statusText` over the latency. Latency text is `未测` when 0, `超时` when below 0, else `N ms` (`Theme.qml:46-50`). It shows `—` when disconnected.
  - `↓ 下行` over `bytes/s`.
  - `↑ 上行` over `bytes/s`.
- **Landscape equivalent** (`Landscape.qml:101-115, 151-190`):
  - Top bar: Toggle, a look-coloured dot, the status text, a proxy-mode dropdown (`规则`/`全局`/`直连` → `ui.proxy_mode`, then reconnect), and a mode dropdown (`系统代理`, plus `TUN` only for sing-box → `ui.mode`, then reconnect).
  - Detail column:
    - `当前节点`, then the name and protocol pill, the latency (`— ms` when 0), and `<core> 不支持` when relevant.
    - A 2×2 grid: `下行`, `上行`, `本次下载`, `本次上传`.
    - `地址` / `分组` / `内核`.
    - `最近日志`: the last 200 lines, red for `错误|致命|崩溃|失败|超时`, warn colour for `警告`.

---

## 2. NodeEditor (`app/qml/components/NodeEditor.qml`)

### 2.1 Opening
- **`openNew(protocol)`** (:28-39):
  - Group is `默认`, mode is `form`.
  - Draft is `{protocol, server:"", port:<default>}`.
  - Default ports (:22-27): socks 1080, http 8080, shadowsocks 8388, wireguard 51820, ssh 22, everything else 443.
  - Protocol-specific presets:
    - trojan, anytls: `stream:{sec:"tls"}`
    - shadowsocks, shadowtls: `method:"aes-256-gcm"`
    - shadowtls: `shadowtls:{version:3}`
    - wireguard: `wireguard:{local_address:"10.0.0.2/32"}`
- **`openFor(id)`:** deep-copies `entry.node` and takes the entry's group (default `默认`) (:41-49).

### 2.2 Layout (:147-231)
- **Title:** `添加节点` or `编辑节点`, then a monospace protocol pill.
- **Segmented control:** `常用` (form) / `JSON`.
- **Form:**
  - First row: `分组`, a dropdown of all groups.
  - Then one row per spec: a 110 px label, then an `Input` (text/int), a `Toggle` (bool) or a `Dropdown` (choice).
- **JSON mode:** a monospace TextArea holding `JSON.stringify(draft, null, 2)`.
- **Errors:** a red label.
- **Buttons:** `取消`, `保存`.

### 2.3 Field-writing rules
- **`setAt`** creates missing parent objects. **Assigning `""`, `false` or `undefined` deletes the key** (:56-63).
- **Int fields** use `IntValidator 0–65535` for every int field, including Mbps and AlterID (:211). An empty int deletes the key; otherwise the value is `Number(text)` (:188).
- **Choice fields** show `d` (default) when the key is missing, without writing it (:204).
- **Bool fields** write `true`, or delete the key.

### 2.4 Fields per protocol
Every protocol starts with these three fields (:85-89):

| Label | JSON path | Type | Hint |
|---|---|---|---|
| `名称` | `name` | text | `留空则显示地址` |
| `地址` | `server` | text | |
| `端口` | `port` | int | |

Then the protocol-specific fields (:90-112):

| Protocol | Fields (label → path : type, *hint*) |
|---|---|
| socks, http | `用户名`→`username`:text; `密码`→`password`:text |
| shadowsocks | `加密方式`→`method`:text *如 aes-256-gcm、2022-blake3-aes-128-gcm*; `密码`→`password`; `插件`→`plugin`:text |
| vmess | `UUID`→`uuid`; `AlterID`→`alter_id`:int; `加密`→`vmess_security`:text *auto* |
| vless | `UUID`→`uuid`; `Flow`→`flow`:text *xtls-rprx-vision* |
| trojan | `密码`→`password` |
| hysteria2 | `密码`→`password`; `混淆密码`→`hysteria2.obfs_password`; `上行 Mbps`→`hysteria2.up_mbps`:int; `下行 Mbps`→`hysteria2.down_mbps`:int; `端口跳跃`→`hysteria2.hop_ports`:text *如 20000-30000* |
| tuic | `UUID`→`uuid`; `密码`→`password`; `拥塞控制`→`tuic.congestion_control`:text *bbr、cubic* |
| naive (editable but **not offered in 手动添加**) | `用户名`→`username`; `密码`→`password`; `SNI`→`naive.sni` |
| ssh | `用户名`→`username`; `密码`→`password` |
| anytls | `密码`→`password` |
| wireguard | `本机私钥`→`wireguard.private_key`; `对端公钥`→`wireguard.peer_public_key`; `预共享密钥`→`wireguard.pre_shared_key`; `本机地址`→`wireguard.local_address` *10.0.0.2/32,fd00::2/128*; `Reserved`→`wireguard.reserved` *WARP 用，如 1,2,3*; `MTU`→`wireguard.mtu`:int |
| hysteria (v1) | `认证字符串`→`hysteria.auth`; `上行 Mbps`→`hysteria.up_mbps`:int; `下行 Mbps`→`hysteria.down_mbps`:int; `混淆密码`→`hysteria.obfs` |
| shadowtls | `ShadowTLS 密码`→`password`; `版本`→`shadowtls.version`:int *3*; `握手 SNI`→`stream.sni` *如 www.microsoft.com*; `指纹`→`stream.utls` *chrome*; `SS 加密方式`→`method`; `SS 密码`→`shadowtls.ss_password` |

**Extra field block** (:113-115):
- **hysteria2, tuic, hysteria** get the QUIC TLS fields (:78-82): `SNI`→`quic_tls.sni`, `ALPN`→`quic_tls.alpn`, and `跳过证书验证`→`quic_tls.insecure`:bool.
- **shadowtls, wireguard, ssh** get no extra fields.
- **vmess, vless, trojan, anytls**, and **any other protocol whose draft already has `stream`** (for example an imported socks or ss node with a stream), get the stream/TLS fields (:66-77):

| Label | Path | Type | Values / hint |
|---|---|---|---|
| `传输方式` | `stream.net` | choice | `tcp ws grpc http httpupgrade xhttp`, default shown `tcp` |
| `TLS` | `stream.sec` | choice | `""`→`关闭`, `tls`→`开启` |
| `SNI` | `stream.sni` | text | |
| `Host` | `stream.host` | text | |
| `路径` | `stream.path` | text | |
| `ALPN` | `stream.alpn` | text | *逗号分隔，如 h2,http/1.1* |
| `指纹` | `stream.utls` | text | *chrome、firefox、safari…* |
| `Reality 公钥` | `stream.pbk` | text | |
| `Reality ShortID` | `stream.sid` | text | |
| `跳过证书验证` | `stream.insecure` | bool | |

Fields the form does **not** expose, so they are reachable only in JSON mode (Go `node.go`): `socks_version`, `uot`, `stream.h_type`, `cert`, `ed_name`, `ed_len`, `spx`, `mux_s`, `pac_enc`, `hysteria2.hop_interval`, `tuic.udp_relay_mode` / `udp_over_stream` / `zero_rtt` / `heartbeat`, `quic_tls.ca` / `disable_sni`, `naive.protocol` / `extra_headers` / `certificate` / `insecure_concurrency`, `custom_outbound`, `custom_config`.

### 2.5 Mode switch and save (:119-145)
- **Form → JSON:** serialises the draft.
- **JSON → form:** `JSON.parse`. On failure it shows `JSON 格式错误：<msg>` and stays in JSON mode.
- **Save:**
  1. In JSON mode, parse first.
  2. Validate the address: empty after trim gives `地址不能为空`.
  3. Validate the port: `Number(port)` outside 1–65535 gives `端口要在 1–65535 之间`. The port is then coerced to a number.
  4. **New node:** `store.addNode(json, group)`. If it returns -1 (empty object), show `节点数据无效`. Logs `已添加节点 <name>`.
  5. **Existing node:** `updateNode(id, {node, group, lat:0, res:{}})`, which **resets the test results**. Logs `已保存节点 <name>`. If this node is the one connected, `App.reconnect()`.
- There is **no protocol-specific validation in the UI.** Missing uuid or password surfaces later, at render or start, from Go `Validate()` (`node.go:186-215`).
- **Quirk:** `specs` depends on `draft`, but `setAt` mutates `draft` in place, so the field list only re-evaluates on reopen or a JSON round-trip.

---

## 3. Import, share and export

### 3.1 ImportDialog (`ImportDialog.qml`)
- **Title** `导入节点`, then a monospace TextArea (180 px), placeholder `vmess:// ss:// vless:// trojan:// hy2:// tuic:// …`.
- **Row** `加入分组` with a group dropdown (initially `默认`) and a button `从剪贴板`, which replaces the text with `sys.clipboardText()`.
- **Buttons** `取消` and `导入`. `导入` is enabled only when the trimmed text is non-empty. It calls `App.importText(text, group)` and closes.
- **`openWith(text, group)`:** the `group` argument preselects a group, but no caller passes one (NP:325). Otherwise the dropdown keeps its previous choice, falling back to the first group if that group was deleted (:16-21).

### 3.2 Import paths into `App.importText(text, group)`
`App.importText` sets `importGroup = group || "默认"`, then `core.importNodes(text)` (App:183).

1. **ImportDialog** (above).
2. **Group menu `从剪贴板导入节点`:** `importClipboard(group)`. Empty clipboard logs `剪贴板里没有可导入的内容` (App:185-189).
3. **Tray `导入 › 从剪贴板导入`:** `importFromTrayText(clipboard)` with `importFromTray = true`. Empty shows the tray toast `导入` / `没有可导入的内容` (App:190-194, 277).
4. **Tray `导入 › 扫描屏幕上的二维码`:** `sys.scanScreenQr()` (`system.cpp:455-489`):
   - Grabs all screens side by side into one PNG in the temp dir.
   - Runs `DuoBaoProxyCore qr <file>` (`go/cmd/duobaoproxy-core/qr.go:19-49`: gozxing multi-reader, falling back to the single reader, TRY_HARDER). Exit code 2 means no code was found. Decoded texts come back one per line.
   - Errors: `截屏失败`, `屏幕上没有找到二维码`, `二维码识别失败`. Each is logged and shown as the toast `扫描二维码` (App:283-286).
   - Success: `importFromTrayText(text)` into `默认`.
   - There is **no QR import from an image file**, and no QR import inside the main window.
5. **Command line `-import-file <path>`:** read in `main.cpp:51-57`, imported into `默认` once the cores are listed (`Main.qml:55-61`).
6. **Subscriptions:** the GroupsPage, not this dialog.
   - `updateGroup(g)`: XHR `GET sub_url` with `User-Agent: DuoBaoProxy/<ver>`.
   - Non-200 logs `订阅 <name> 更新失败：HTTP <code>`.
   - Parses the `subscription-userinfo` header into numeric fields plus `updated` (ISO time) on the group.
   - Then **`removeGroupNodes(name)`** and `importText(body, name)` (`GroupsPage.qml:14-32`).
   - Auto-update: a 60 s timer updates every group whose `sub_interval_min` has elapsed (`GroupsPage.qml:148-155`).

**Core side** (`go/node/import.go:22-54`):
- Trim the text.
- If it has no `://`, try base64 and use the decoded text if that contains `://`.
- If it contains `proxies:`, parse as Clash YAML.
- Otherwise parse each line with `ParseLink`, skipping blank lines and `#` comments. Failures become `"<first 40 chars>…: <err>"`.
- Link schemes: `socks socks4 socks4a socks5 http https ss vmess vless trojan naive+https naive+quic hysteria2 hy2 hysteria tuic` (`link.go:17-40`).
- **Pitfall:** a pasted `https://` subscription URL is **not fetched**. It is parsed as an HTTPS proxy node (`link.go:21`). The dialog title says `订阅`, but only subscription *content* works there.

**`onImported(nodes, errors)`** (App:263-270):
- `n = store.addNodes(nodes, importGroup)`.
- Logs `导入 n 个节点到「G」`, plus `，k 行失败` when there were failures, followed by each error indented by two spaces.
- Tray-initiated imports also show a toast `导入`.
- If nothing was selected, auto-selects `store.nodes[0]`.
- With `-connect`, auto-connects after an import (`Main.qml:62`).

**Bug to avoid on mobile:** `importGroup` and `importFromTray` are single global variables (App:181-182). Two concurrent imports, for example two subscriptions refreshing in the same timer tick, can land in the wrong group. Mobile should pass the group through with the request, or import synchronously through gomobile.

### 3.3 Share flow (App:158-176, 255-261)
1. `shareNodes(ids, mode)` queues the ids.
2. `shareNext()` calls `core.shareLink(nodeJson)` **one at a time, in order**.
3. Each reply:
   - Success: push `{name, link}`.
   - Error: log `分享失败：<err>` and skip. Go returns `<protocol> 没有通用的分享链接格式` for protocols with no link format. Link formats exist only for socks, http, ss, vmess, vless, trojan, naive, hysteria2, hysteria and tuic, so wireguard, ssh, anytls and shadowtls cannot be shared (`go/rpc/server.go:330-343`, `go/node/link.go:358-378`).
4. When the queue is empty:
   - `link` mode: `sys.copyText(links.join("\n"))`, then log `已复制分享链接` or `已复制 N 条分享链接`.
   - `qr` mode: `sys.makeQrImages(links)` (`system.cpp:491-512`). It runs `DuoBaoProxyCore qr-encode <tmpdir>` with links on stdin, producing 480×480 PNGs (EC level M, margin 2, UTF-8; `qr.go:54-84`). The previous batch directory is deleted first.
     - `onQrImagesReady`: an error logs `二维码生成失败：…`. Otherwise `App.qrItems = [{name, link, image}]`, and `Main.qml:41-42` opens the QrDialog.
   - If no link succeeded, nothing happens beyond the logs.
- The proto also has a `V2RaynVmess` flag for the v2rayN vmess format. The GUI never sets it (`server.go:335`).

### 3.4 QrDialog (`QrDialog.qml`)
- Title `分享二维码`, then the node name, then the QR code on a white rounded card (always white, so it scans in dark theme), then the link in mono with a middle elide.
- When there are several items: `上一个` / `i / N` / `下一个`. The index resets to 0 whenever the items change.
- Buttons `复制链接` (copies the current link and logs `已复制分享链接`) and `关闭`.

### 3.5 Export (`RoutesPage.qml:38-39, 88-97`)
- Button `导出 JSON`, **landscape only** (hidden when compact).
- Calls `renderConfig(req, coreId, [nodeJson(selectedId)], renderSettings(), mode 2 = Export)` (`libcore.pb.go:80-82`).
- On success it copies the config to the clipboard and logs `配置已复制到剪贴板（<core>）`. On failure it logs `导出失败：<err>`.
- With no node selected, the chain is `[""]`, which fails as `node: unexpected end of JSON input` (`server.go:348-352`).
- There is **no node or data export**. Share links are the only node export.

---

## 4. Connect state machine (App.qml)

### 4.1 State
- **`connected`** is `core.runningCore !== ""` (App:13). `runningCore` is set by:
  - a successful `Start` (`core_client.cpp:238-241`);
  - `ListCores` when an entry has `running` (:225);
  - `Status` on launch (:167, 255-259).

  It is cleared by `Stop` (:247-252) or the core process exiting (:131-136, which also logs `核心已退出（code）`).
- **`busy`** is true during start or stop.
- **`statusText`** starts as `未连接`.
- **`latency`**, **`upRate`/`downRate`/`upTotal`/`downTotal`**, and **`appliedSettings`**.
- **`pendingTests`** is a request-id map shared with export. It also carries a special `"reconnect"` flag.
- **`nextReq`** is a counter shared with Tests.qml.
- **Derived values:**
  - `coreHasTun = coreId === "sing-box"` (App:29).
  - `tunActive = connected && coreHasTun && ui.mode === "tun"`.
  - `look` is `off`, `on` or `tun`, pushed to the tray icon (App:33-35).

### 4.2 `connect()` (App:98-108)
1. If `connected || busy`, return.
2. If `selectedId < 0`, set `statusText = "先选一个节点"` and return.
3. If the core doesn't support the protocol, set `statusText = "<core> 不支持 <protocol>"` and return.
4. Set `busy = true` and `statusText = "正在连接…"`. Register `pendingTests[req] = {kind:"start"}` and snapshot `appliedSettings = settingsJson()`.
5. Call `core.renderConfig(req, coreId, [nodeJson(selected)], JSON(renderSettings()), 0 /*Full*/)`. The chain is always a single node.

**`renderSettings()`** (App:72-93) starts from the settings JSON and applies:
- **Proxy mode `global`** (the default): clears `proxy_domain`, `direct_domain`, `proxy_ip`, `direct_ip` and `custom_rules`, and sets `final=proxy`. Block lists are kept.
- **Proxy mode `direct`:** `final=bypass`. Rules are kept.
- **Proxy mode `rule`:** settings unchanged.
- **`route_parts`:**
  - `rules`: `apps=[]`.
  - `apps`: clears every domain and IP list, including block lists, and `custom_rules`.
  - `both` (the default): no change.
- `tun.enabled = ui.mode==="tun" && coreHasTun`.

### 4.3 Responses
1. **`onRendered`** for kind `start` (App:229-236):
   - Error: `busy=false`, `statusText=error`, log `生成配置失败：<err>`.
   - Otherwise: keep `statsTags`, then `core.startCore(coreId, config, tags, sys.appDir())`. The asset dir holds geo files and is the app directory.
2. **`onStarted(err)`** (App:237-246):
   - Always `busy=false`.
   - Error: `statusText=err`. The error is also logged as `错误：…` by `setError`.
   - Success:
     1. `statusText="已连接"`.
     2. If `ui.mode==="system"`, set the OS proxy to `inbound.address:port`.
     3. `core.watchTraffic(tags)`. The C++ side polls `QueryStats` on **only the first tag** (the exit `proxy`) every 1000 ms. The rate is the delta between polls (0 on the first poll) and is clamped to at least 0 (`core_client.cpp:342-377`).
     4. Tray: state connected, tooltip `"DuoBao Proxy for Windows · <node>"`.
     5. A quiet `Tests.run([selectedId], ["url"], true)` for an immediate latency.
   - Note: with xray and `ui.mode==="tun"` (stale), neither the system proxy nor TUN is applied. Only the local inbound runs.
3. **`disconnect()`:** if not connected, return. Otherwise `busy=true` and `core.stopCore()` (App:109-113). It does not check `busy`. The node menu's `停止` does (NP:189).
4. **`onStopped(err)`** (App:247-254):
   - `busy=false`; `statusText = err || "未连接"`.
   - `latency`, `upRate` and `downRate` go to 0. Totals are kept, so `本次下载`/`本次上传` show the last session.
   - If the system proxy is on, turn it off.
   - Tray: state off, tooltip `platformName`.
   - If `pendingTests.reconnect` is set: delete it and `connect()`.
5. **`reconnect()`:** only if connected. Sets the `reconnect` flag, then `disconnect()`. It is stop-then-start, with no hot reload (App:114).
6. **`toggle()`:** `connected ? disconnect() : connect()`, used by the switches and the tray.

### 4.4 Triggers for reconnect
- `selectNode` while connected (App:67).
- The editor saving the active node (`NodeEditor.qml:142`).
- Proxy-mode or mode dropdowns (`Landscape.qml:109, 114`), tray mode pick (App:276), `setRouteParts` (App:95).
- **Settings changes, debounced:** `store.settingsChanged` while connected restarts an 800 ms one-shot timer. When it fires, the app reconnects only if `settingsJson() !== appliedSettings` (App:199-206). A burst of edits therefore causes one reconnect, and a no-op edit causes none.
- `setCore` while connected only **disconnects** (App:56).

### 4.5 Latency refresh
- A timer runs every 5000 ms while `connected`, repeating: `Tests.run([selectedId], ["url"], quiet=true)` (App:209-213).
- A quiet run doesn't change `shownKinds`.
- The test uses a **throw-away instance**, not the running core: `renderConfig` mode 1 (Test), then `urlTest` with that config (`Tests.qml:154-181`). It is deduplicated per `id:kind` while one is in flight (:103-104).
- `Tests.save` updates `res.url` and `lat`, and sets `App.latency` when the id is the selected one (:129-138).
- The default URL is `http://cp.cloudflare.com/` with an 8 s timeout (:29). Errors map to `超时` if the message matches `/timeout|deadline|i\/o timeout/`, else `失败` (:167).
- The test queue allows at most 6 concurrent jobs, and speed tests run one at a time (:81-121).

### 4.6 Startup
- On `core.ready`:
  - `listCores()`.
  - If not connected and the OS proxy already points to our inbound, clear it and log `已清除上次遗留的系统代理设置` (App:217-226).
- After cores are listed: run the `-import-file` import, or, with `-connect`, connect (`Main.qml:53-63`).

### 4.7 Tray (`system.cpp:356-451`)
- **Icon:** `logo-off/on/tun.png` by `look`. Left click or double click shows the window.
- **Menu**, rebuilt on every model or state change:
  - `启动` / `停止`.
  - `启动方式` › radio `系统代理`, plus `TUN` when the core has TUN.
  - `选择节点` › one submenu per group (flat when only one group). Entries read `name\tN ms` or `name\t超时`, are checked when selected, and are disabled when the core doesn't support them. With no nodes there is a single disabled `还没有节点`.
  - `导入` › `从剪贴板导入` / `扫描屏幕上的二维码`.
  - `显示窗口`.
  - `退出`, which disconnects and then quits (`Main.qml:50`).
- **Toasts** use `notify(title, text)` with 3 s visibility.

---

## 5. Desktop-only items and their mobile meaning

| Desktop item | Source | Mobile meaning |
|---|---|---|
| System proxy registry / networksetup / gsettings, leftover-proxy cleanup, `inbound.address:port` as the OS proxy | `system.cpp:157-218`, App:220-225, 241-242, 251 | None. Always VPN (TUN). The `mode` dropdown (`系统代理`/`TUN`) and tray `启动方式` go away. The inbound is internal only. |
| `coreHasTun` = sing-box only | App:29 | Must become "always TUN". With xray, sing-box owns TUN and forwards to xray (per plan). |
| Tray icon, tooltip, look tint, tray menu, toasts | `system.cpp:356-451` | Android: foreground-service notification with a stop action, plus a Quick Settings tile. iOS: VPN status in Settings, widget optional. Toasts become snackbars or notifications. |
| Hide-to-tray on close; `-tray`, `-connect`, `-import-file` args | `Main.qml:45-63`, `main.cpp:49-57` | App lifecycle; auto-connect becomes Always-on VPN / On-Demand. `-import-file` becomes share-intent or URL-scheme import. |
| Screen QR scan (grab all monitors, `DuoBaoProxyCore qr`) | `system.cpp:455-489` | Camera scanner plus pick-image-from-gallery, decoding in-process (expose gozxing decode via `go/mobile`, or use ML Kit / AVFoundation). |
| QR images via subprocess and temp PNG files | `system.cpp:491-512` | Generate in-process (Go returns PNG bytes, or a native generator). Add a native share sheet for the link and image. |
| `DuoBaoProxyCore` child process, gRPC with a random port and token, `appDir()` as asset dir | `core_client.cpp:141-181`, App:235 | Replaced by gomobile calls. The asset dir becomes the app's files dir with bundled geo files. |
| Ctrl/Shift-click multi-select, Ctrl+A, Esc, right-click menus, double-click edit, hover states, mouse drag reorder | NP:34-97, `NodeRow.qml:32-43` | Long-press enters selection mode with a contextual action bar. Per-row overflow menu or long-press for the same menu items. Tap to edit. Drag handles for reorder. |
| Landscape/portrait layout switch (`Ctrl+L`, `ui.layout`), window minimum sizes, dark title bar | `Main.qml:8-34, 64`, `system.cpp:125-138` | None. Use the portrait flow (StatusCard plus bottom tabs `节点 分组 路由 日志 设置`, `Portrait.qml:269`) as the base, and give search back to compact mode. |
| Clipboard via QClipboard | `system.cpp:272-273` | Native clipboard. Android 10+ reads only in the foreground. iOS shows a paste prompt. |
| Windows installed-apps / shortcut resolution (app routing) | `system.cpp:306-352` | Android: PackageManager list. iOS: impossible. |
| Autostart registry / LaunchAgent | `system.cpp:226-268` | Android boot receiver plus always-on VPN. iOS On-Demand rules. |
| `platformName` in the tooltip | `system.cpp:113-121` | Not needed. |

---

## 6. Gaps to keep or fix in the port

1. Make sure the user reads the subscription-URL pitfall in §3.2 correctly. Pasting an `https://` URL into the import dialog creates an HTTP proxy node instead of fetching the subscription. Mobile should detect a single http(s) URL and offer to add it as a subscription.
2. Fix the global `importGroup` race (§3.2).
3. Deleting the connected node or group, or switching core, leaves the core running or stopped inconsistently (App:126-130, 56).
4. Hop ports are not shown in the row. Naive cannot be added by hand. Many fields are editable only in JSON mode (§2.4).
5. The `V2RaynVmess` share option is unused.
6. Collapse state isn't persisted, while the sort mode is (`ui.sort`).