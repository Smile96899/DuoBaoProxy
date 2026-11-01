# DuoBao Proxy: shell, settings, tests, logs, help and theme spec for mobile

All paths are relative to `C:/Users/Administrator/Desktop/h.proxy/app/`. `store.ui.*` is the UI section of `data.json` and is written with `store.setUi(key, v)`. `store.settings.*` is the settings section and is written with `store.setSetting("a.b", v)`. When a settings change lands while the proxy is connected, the core restarts after an 800 ms debounce, and only if `settingsJson` actually changed (`qml/App.qml:200-206`).

---

## 1. Navigation structure

### App window (`qml/Main.qml`)
- The layout comes from `store.ui.layout`, which is `"landscape"` (default) or `"portrait"` (`Main.qml:8`). Minimum and initial size is 900×560 in landscape and 360×640 in portrait. Switching layout resets the window to that size (`Main.qml:13-34`).
- The base font is `Theme.font` at 14 px (`Main.qml:19-20`). On startup the window sets `Theme.mode = store.ui.theme || "system"` and keeps a dark title bar in sync with `Theme.dark` (`Main.qml:22-24`).
- Closing the window hides it to the tray. The tray's 退出 item disconnects and then quits (`Main.qml:46-51`).
- Ctrl+L toggles the layout (`Main.qml:64`).
- Command-line `importText` and `autoConnect` run after the core lists its engines (`Main.qml:53-63`).
- The QR dialog opens whenever `App.qrItems` is non-empty (`Main.qml:41-42`).
- The current page is `App.page`, one of `nodes|groups|routes|logs|settings` (default `"nodes"`). It is shared by both layouts (`App.qml:16`).

**There are no icons anywhere in the navigation. Every nav item is a text label.**

### Landscape (`qml/Landscape.qml`)
The window has three parts, left to right: **Rail | (top bar + page) | detail column**.

- **Rail** (`components/Rail.qml`):
  - 64 px wide, colour `railPanel2`, with a 1 px `railLine` on its right edge (`Rail.qml:10-11,81`).
  - From the top:
    1. `Mark` logo, 30 px (`:18`).
    2. A core-picker pill, 52×32, radius 16. Its text is the core id in mono 10 px; `"sing-box"` is shown as `"sing\nbox"`. Tapping it opens a `PickPopup` (170 px wide) beside the button, listing `App.cores` (`:22-43`).
    3. A 36×1 divider (`:44`).
  - **Nav** is a vertical segmented control centred on the rail's full height. The container is 52 px wide, radius 26, colour `railBg`. Each item is 48×42, radius 21. The selected item is filled with `railPanel` and uses text `railFg`; other items use `railFg2`; text is 12 px (`:51-72`).
  - Nav order and labels: **节点** (`nodes`), **分组** (`groups`), **路由** (`routes`), **日志** (`logs`), **设置** (`settings`) (`:60-61`).
  - At the bottom is a **开发者** text link, 12 px, colour `railFg3` (`railFg` on hover). It opens `https://t.me/DuoBaoToolsBot` (`:73-80`).
- **Top bar** (`Landscape.qml:28-53`): 56 px high, colour `panel`, 18 px side margins, 14 px spacing, with a bottom line. From left:
  1. `Toggle` for connect/disconnect.
  2. An 8 px status dot coloured `Theme.lookColor(App.look)`.
  3. `App.statusText`. Values are `未连接`, `正在连接…`, `已连接`, `先选一个节点`, `<core> 不支持 <proto>`, or an error string (`App.qml:15,100-103,240,249`).
  4. A spacer.
  5. Dropdown **规则 / 全局 / 直连** → `store.ui.proxy_mode`, values `rule|global|direct`, **default `"global"`**. Changing it calls `App.reconnect()`.
  6. Dropdown **系统代理 / TUN** → `store.ui.mode`, values `system|tun`, default `"system"`. TUN is offered only when `App.coreHasTun`, which is true when `coreId === "sing-box"` (`App.qml:29`).
- **Page area**: a Loader switches on `App.page` and falls back to NodesPage (`Landscape.qml:60-72`).
- **Detail column** (`Landscape.qml:76-148`): 300 px wide when the window is ≥1100 px, otherwise 260; colour `panel`; 16 px margins; 10 px spacing. Contents:
  - Caption **当前节点** (11 px, `fg3`, letter-spacing 1), then the node name (15 px, DemiBold).
  - A protocol chip (mono 11 px on `panel2`, radius 4). When connected, the latency (`— ms` if 0, else `Theme.latText`). When the core cannot run the protocol, `<core> 不支持` in `bad`.
  - A 2×2 grid of 40 px tiles (`panel2`, radius 8): **下行**, **上行** (rate + `/s`), **本次下载**, **本次上传** (totals). Labels are 10 px `fg3`; values are mono 13 px Medium and shrink to fit, down to 10 px.
  - A key/value grid at 12 px: **地址** (`server:port`, mono, elide middle), **分组**, **内核**.
  - Caption **最近日志**, then a `bg` box (radius 8, min 80 px) holding the last 200 log lines. Lines are mono 11 px, auto-scroll to the end, and use the same colour rule as LogsPage (§4).

### Portrait (`qml/Portrait.qml`)
The screen is **StatusCard (14 px margin) | page | bottom tab bar**.

- **StatusCard** (`components/StatusCard.qml`):
  - Radius 12; colour `accentSoft` when connected, otherwise `panel2`, with a 150 ms colour animation; 16 px padding; 12 px spacing.
  - Row 1: Mark 28 px, a core Dropdown, a spacer, then the Toggle.
  - Row 2: the node name (17 px, DemiBold).
  - Row 3 holds three equal columns:
    - Column 1: `statusText` (11 px; `accent` when connected, else `fg2`) over the latency (mono 26 px Medium, shrinking to 12; shows `—` when not connected).
    - Column 2: **↓ 下行** over the rate (mono 20 px, shrinking to 11).
    - Column 3: **↑ 上行** over the rate, same style.
  - The portrait layout has no proxy_mode or system/TUN control on the main screen. Mode is reachable only through Settings › 模式.
- **Tab bar** (`Portrait.qml:35-54`):
  - 64 px high, colour `railPanel2`, with a top line in `railLine`.
  - Five equal tabs: **节点, 分组, 路由, 日志, 设置**, with the same values as the rail.
  - The selected tab gets a 64×32 pill (radius 16) in `railPanel` and text `railFg` DemiBold 12 px. Unselected tabs use `railFg3` Normal.
- In portrait, pages are built with `compact: true`, except LogsPage (`Portrait.qml:57-61`).

---

## 2. Settings

### SettingsPage (`qml/pages/SettingsPage.qml`)
- Content is a single column, `min(width-36, 640)` wide, starting at x=18 / y=12. Each row is a `Field` (§6).
- 测试设置 and 操作说明 are sub-pages that cover the list via Loader. Their state is held in the local flags `testsOpen` and `helpOpen` (`:11-24`).

Rows, in order:

| # | Label (verbatim) | Storage | Type / options | Default | Effect / cite |
|---|---|---|---|---|---|
| 1 | **操作说明** › | — | Nav row (56 px, 1 px bottom line, `›` in `fg3` 18 px) | — | Opens HelpPage (`:38-47`) |
| 2 | **布局** | `ui.layout` | Dropdown: `landscape` 横屏 / `portrait` 竖屏 | `landscape` | `App.setLayout` (`:49-50`, `App.qml:69`). On mobile, map to phone/tablet or drop. |
| 3 | **主题** | `ui.theme` | Dropdown: `system` 跟随系统 / `light` 浅色 / `dark` 深色 / `mixed` 深浅组合 | `system` | Sets `Theme.mode` (`:51-52`) |
| 4 | **开机自启** | `sys.autoStart` (OS, not data.json) | Toggle | OS state | Starts at login, minimised to tray (`:53-54`) |
| 5 | **模式** | `ui.mode` | Dropdown: `system` 系统代理, plus `tun` TUN only if `coreHasTun` | `system` | Then `App.reconnect()` (`:55-56`) |
| 6 | **分流方式** | `ui.route_parts` | Dropdown: `rules` 规则分流 / `apps` 应用分流 / `both` 同时生效 | `both` (`App.qml:94`) | `App.setRouteParts` reconnects only if the value changed (`App.qml:95`) |
| 7 | **本地端口** | `settings.inbound.port` | Int input, 90 px, mono, validated 1–65535 | `2080` | Saved on editingFinished (`:59-60`) |
| 8 | **允许局域网** | `settings.inbound.address` | Toggle; on means `"0.0.0.0"`, off means `"127.0.0.1"` | off (`127.0.0.1`) | (`:61-62`) |
| 9 | **测试设置** › | — | Nav row | — | Opens TestSettings (`:65-74`) |
| 10 | **协议栈** (shown only if `coreHasTun`) | `settings.tun.stack` | Dropdown: `gvisor` / `system` / `mixed` | `gvisor` | (`:76-77`) |
| 11 | **严格路由 / Kill Switch** (only if `coreHasTun`) | `settings.tun.strict_route` | Toggle (bool) | false | (`:78-79`) |
| 12 | **FakeIP** (only if `coreHasTun`) | `settings.tun.fake_dns` | Toggle | false | (`:80-81`) |
| 13 | **IPv6** (only if `coreHasTun`) | `settings.tun.ipv6` | Toggle | false | (`:82-83`) |
| 14 | **版本**, detail `"粗制滥造的v" + app version` | — | Flat PillButton **检查更新** | — | Opens `https://github.com/Smile96899/DuoBaoProxy/releases` (`:85-86`) |
| 15 | **数据目录**, detail = `store.dataDir` | — | Flat PillButton **打开** | — | Opens `file:///<dataDir>` (`:87-88`) |
| 16 | **核心日志** | — | Flat PillButton **重启核心** | — | `core.shutdown(); core.launch()` (`:89-90`) |

`settings.tun.enabled` is not a user setting. It is derived as `ui.mode==="tun" && coreHasTun` when settings are rendered (`App.qml:91`).

### TestSettings (`qml/pages/TestSettings.qml`)
- **Header:**
  - A back label: `‹ 设置` on the list view, `‹ 测试设置` on a single test. 13 px, `fg2`, turning `fg` on hover (`:25-30`).
  - A title, 15 px DemiBold: `测试设置` on the list view, or the test's `l` label (`:31`).
- **List view:** one row per `Tests.kinds` entry showing its `l` and a `›` (`:34-52`). The `d` descriptions are not shown.
- **Single test view:** one `Field` per `Tests.fields[kind]` entry (`:55-102`). Inputs by type:
  - `text`: a 300 px Input. Saves the trimmed value if it changed.
  - `int`: a 90 px mono Input, centred, validated 1–3600.
  - `services`: toggle chips, 32 px high, radius 16, with 14 px horizontal padding and 8 px spacing. On: fill `fg`, text `panel`, prefixed `✓ `. Off: transparent with a 1 px `line` border. **At least one chip must stay on** (`:93-95`).
  - The `h` hint strings exist in Tests.qml but are **not displayed**. Mobile can show them as field detail.
  - A flat button **恢复默认** calls `Tests.resetOpts(kind)` (`:103-108`).
- Storage is `ui.tests[kind][field]`. Empty strings and empty arrays fall back to the default. For `url.url` only, the legacy key `ui.test_url` is checked before the default. Resetting `url` also clears `test_url` (`Tests.qml:55-74`).

Per-test fields, all from `Tests.qml:28-54`:

| kind | field | label | type | default | hint (h) |
|---|---|---|---|---|---|
| url | url | 测试网址 | text | `http://cp.cloudflare.com/` | 返回 2xx / 3xx 即视为成功 |
| url | timeout | 超时（秒） | int | 8 | |
| tcp | timeout | 超时（秒） | int | 5 | |
| icmp | timeout | 超时（秒） | int | 3 | |
| udp | server | DNS 服务器 | text | `8.8.8.8:53` | IP:端口 |
| udp | domain | 查询域名 | text | `www.google.com` | |
| udp | timeout | 超时（秒） | int | 5 | |
| loss | count | 次数 | int | 10 | |
| loss | timeout | 每次超时（秒） | int | 2 | |
| down | url | 下载网址 | text | `https://speed.cloudflare.com/__down?bytes=25000000` | 文件越大测得越准 |
| down | timeout | 最长时间（秒） | int | 15 | |
| up | url | 上传网址 | text | `https://speed.cloudflare.com/__up` | 需接受 POST 上传 |
| up | size | 上传大小（MB） | int | 10 | |
| up | timeout | 最长时间（秒） | int | 15 | |
| geo | url | 查询网址 | text | `https://www.cloudflare.com/cdn-cgi/trace` | 需返回 Cloudflare trace 格式（ip= / loc=） |
| geo | timeout | 超时（秒） | int | 8 | |
| media | services | 检测项目 | services | `["netflix","disney","youtube"]` | |
| media | timeout | 超时（秒） | int | 15 | |
| ai | services | 检测项目 | services | `["chatgpt","claude","gemini"]` | |
| ai | timeout | 超时（秒） | int | 15 | |
| dns | timeout | 超时（秒） | int | 25 | |

Service chip tables, each entry being `[id, full name, short code]` (`Tests.qml:26-27`):
- media: `netflix`/Netflix/N, `disney`/Disney+/D, `youtube`/YouTube Premium/Y
- ai: `chatgpt`/ChatGPT/GPT, `claude`/Claude/Cl, `gemini`/Gemini/Ge

---

## 3. Test system (`qml/Tests.qml`, a singleton)

### Kinds (`Tests.qml:11-23`)
Columns: `k` = id, `l` = menu label, `s` = list column title, `w` = column width in px, `sec` = menu section, `d` = description.

| k | l | s | w | sec | d |
|---|---|---|---|---|---|
| url | 真连接延迟 | 真连接 | 64 | 0 | 通过节点访问一个网址，计算往返时间 |
| tcp | TCP 延迟 | TCP | 60 | 0 | 直接连接节点服务器的端口，不经过代理 |
| icmp | ICMP Ping | ICMP | 60 | 0 | 直接 ping 节点服务器；很多服务器禁 ping |
| udp | UDP 延迟 | UDP | 60 | 0 | 通过节点发一次 DNS 查询，也能看出是否支持 UDP |
| loss | 丢包 / 抖动 | 丢包 | 92 | 0 | 连续多次 TCP 连接，统计平均、抖动和丢包 |
| down | 下载速度 | 下载 | 76 | 1 | 通过节点下载测速文件 |
| up | 上传速度 | 上传 | 76 | 1 | 通过节点上传测速数据 |
| geo | 出口 IP 与地区 | 地区 | 76 | 1 | 通过节点查询对方看到的 IP 和所在地区 |
| media | 流媒体解锁 | 流媒体 | 96 | 2 | Netflix、Disney+、YouTube Premium 能否使用 |
| ai | AI 服务可用性 | AI | 96 | 2 | ChatGPT、Claude、Gemini 能否访问 |
| dns | DNS 泄漏检测 | DNS | 60 | 2 | 检查 DNS 查询有没有绕过代理（使用 bash.ws） |

- `latencyKinds = [url, tcp, icmp, udp, loss]` (`:78`); `allKinds` is every kind in table order (`:76`).
- **Where tests are started:**
  - The node context menu has a 测试 submenu with the 11 kinds grouped by section and separated by dividers, then **全部测试**, which runs `allKinds` (`pages/NodesPage.qml:191-208`).
  - `App.testNode`, `testNodes` and `testAll` run `["url"]` (`App.qml:118,124,178`).
  - A quiet url test runs on the selected node right after the core starts (`App.qml:245`) and every 5 s while connected (`App.qml:209-213`).

### Queue and concurrency (`Tests.qml:81-128`)
- Up to `maxActive = 6` jobs run at once.
- **Speed tests (down/up) run one at a time** across all nodes, controlled by the `speedBusy` flag. `pump()` takes the first queued job that is not blocked by `speedBusy`, so other kinds can overtake a waiting speed test.
- `run(ids, ks, quiet)`:
  - Unless `quiet`, sets `shownKinds = ks`; these are the result columns the list shows.
  - Skips nodes the current core does not support (`App.supports`, `App.qml:47`).
  - Dedupes on `"id:kind"` while a job is queued or running (`running` map, which `isRunning(id,k)` reads).
  - Queues the jobs in FIFO order.
- `finish` decrements the counters, clears `running`, saves the result, and calls `pump()` again.
- **Saving a result** (`:129-138`): the result goes to `node.res[kind]`. For `url` it also writes `node.lat = ms || -1`; the tray and legacy code read this field. When the node is the selected one, `App.latency` is updated as well.
- `shownKinds` starts empty, so the list shows no result columns until something is tested. The × in the column header clears it (`NodesPage.qml:393`).

### Request mapping (`Tests.qml:140-199`, `src/core_client.cpp:180-227`)
`hostPort(n)` returns `[server]:port` for IPv6 addresses and `server:port` otherwise. `ms(k) = max(1, timeout) * 1000`.

| kind | Path | TestReq fields |
|---|---|---|
| tcp | Direct | `mode=TcpPing, address=hostPort, timeout=ms` |
| icmp | Direct | `mode=Probe, probe="icmp", address=n.server` (host only), `url="", count=0, timeout`, no config |
| loss | Direct | `mode=Probe, probe="loss", address=hostPort, count=opt(loss.count)\|\|10, timeout` |
| url | Through node | First `renderConfig(req, coreId, [nodeJson], renderSettings, 1)` builds a throw-away config. Then `mode=UrlTest, url=opt(url.url), timeout, config={core_id, core_config}` |
| udp | Through node | `mode=Probe, probe="udp", address=udp.server, url=udp.domain, count=0` + config |
| down | Through node | `probe="download", url=down.url, count=0` + config |
| up | Through node | `probe="upload", url=up.url, count=up.size (MB)\|\|10` + config |
| geo | Through node | `probe="geo", url=geo.url` + config |
| media / ai | Through node | `probe="media"` or `"ai"`, `url=services.join(",")` (for example `netflix,disney,youtube`) + config |
| dns | Through node | `probe="dnsleak", address="", url="", count=0` + config |

The proto is in `go/rpc/gen/libcore.proto:63-95`: `TestMode` is `TcpPing=0, UrlTest=1, FullTest=2, Probe=3`; the fields used are `probe=15`, `count=16`, `address=2`, `url=5`, `timeout=6` (ms) and `config=3` (`LoadConfigReq`). FullTest is unused by the UI.

**Turning responses into results** (`Tests.qml:167-198`):
- A render error gives `{err:"失败", detail}`.
- Any other error gives `{err: "超时" if /timeout|deadline|i\/o timeout/i else "失败", detail: error}`.
- `onTested` (url/tcp) gives `{ms: max(1, ms)}`.
- `onProbed` parses the `full_report` JSON. For icmp and udp the report is discarded and replaced by `{ms}`. For loss, `loss >= 1` adds `err: "超时"`.

Report shapes used by the UI, as read in `cell` and `detail`:
- loss: `{avg, min, max, jitter, loss (0..1)}`
- down/up: `{bps}`
- geo: `{ip, cc}`
- media/ai: `{<service>: {ok, region, note}}`
- dns: `{leak, local (cc), servers: [{cc, asn}]}`

### Display per node (`Tests.qml:203-288`, `components/NodeRow.qml:63-79`)
- **Legacy fallback:** when `res.url` is missing, `lat > 0` becomes `{ms}` and `lat < 0` becomes `{err:"超时"}`.
- **Column header:** a 26 px row with `测试结果` on the left and the `s` titles right-aligned at their widths, 12 px spacing, then `×`. Columns are dropped from the right when the name would get narrower than about 170 px (`NodesPage.qml:258-271,377-396`).
- **Cells:** mono 12 px, right-aligned. While a job is running the cell shows `测试中…` in `fg3`. When the result has `err`, the cell shows the error text in `bad`.

`cell()` text and colour per kind:

| kind | text | colour |
|---|---|---|
| url, tcp, icmp, udp | `N ms` | `latColor` |
| loss | `"{avg}ms · {pct}%"` | `bad` if loss>0.2, `warn` if loss>0, else `latColor(avg)` |
| down, up | `speed(bps)`: `X.X MB/s` when ≥1 MiB/s, else `N KB/s`, `—` if 0 | `fg` |
| geo | `"US 美国"`, or `—` | `fg` |
| media, ai | Short codes with marks, for example `N✓ D✗ Y?` (✓ ok, ✗ not ok, ? missing), covering only services present in the report | `good` if all ok, `warn` if some, `bad` if none |
| dns | `泄漏` or `无泄漏` | `bad` / `good` |

`detail()` gives the long text:
- Errors: `err（first line of detail, max 80 chars）`.
- loss: `平均 X ms · 最小 X · 最大 X · 抖动 X ms · 丢包 N%`.
- geo: `CC 国名 · ip`.
- media/ai: one line per service, `Name ✓ 地区（note）`.
- dns: if leaking, `可能泄漏：本地网络所在地（国）的 DNS 收到了查询`; otherwise `无泄漏（本地网络在国）`. Then `\nDNS 服务器：` followed by lines of the form `国 · ASN名（n 个）`, with servers grouped by country + ASN and the `AS123 ` prefix stripped.
- `countryNames` maps 51 ISO codes to Chinese names (`:213-220`). Unknown codes are shown as the raw code.

### Sorting (`NodesPage.qml:272-286`, `Tests.qml:282-288`)
- The mode is `ui.sort`: `default` | `latency` | `speed` | `geo` | (anything else sorts by name, lowercased).
- `latency` sorts by the **first latency kind among the shown columns**, falling back to `url`.
- `sortValue`:
  - Failed or untested nodes get `Infinity` and sort last.
  - down/up use `-bps`, so faster nodes come first.
  - loss uses `loss*100000 + avg`.
  - Everything else uses `ms`.
- `speed` sorts by `down`. `geo` sorts by `cc`, with nodes lacking a code given `"~"` so they come last.
- The sort is ascending and stable within the grouped list.

---

## 4. Logs

### Ingestion (`App.qml:50-52`)
- Every core log line has ANSI colour codes stripped (`/\x1b\[[0-9;]*m/g`), is passed through `LogZh.translate`, and is appended to `App.logs`.
- `App.logs` keeps at most **500 lines**, dropping the oldest. The translation happens on arrival, so the stored lines are already in Chinese.

### LogsPage (`qml/pages/LogsPage.qml`)
- **Header row** (margins 12, left/right 18): title **日志** (15 px DemiBold), a spacer, and a flat **清空** button that sets `App.logs = []`. A 1 px line sits under the header.
- **Log box:** colour `bg`, radius 8, 12 px margin, 10 px inner padding. It holds a read-only, selectable rich-text area: mono 11 px, wrap anywhere, selection in `accent` with `accentFg` text.
- **Incremental append** (`:20-29`): only new lines are appended, so an active selection survives new output. If `logs.length < shown` (after 清空, or once the 500-line window slides), the area is cleared and rebuilt.
- **Auto-follow:** new output scrolls to the end while the view is within 2% of the bottom (`position + size >= 0.98`). It does not scroll while the user has text selected.
- **Line colour** (`:15-17`): `bad` when the line matches `/(^|\s)(错误|致命|崩溃)[ ：]|失败|超时/`; otherwise `warn` when it matches `/(^|\s)警告 /`; otherwise `fg2`. Text is HTML-escaped, runs of spaces become `&nbsp;`, and newlines become `<br>`.
- **Context menu** (right-click on the text or the empty box): **复制** (enabled only with a selection; copies plain text with U+2029 turned into `\n`), **全选**, a separator, **清空**. Ctrl+C also copies plain text.
  - Menu styling: 140 px wide, `panel`, radius 12, 1 px `line` border. Items are 32 px high, 13 px text, radius 8, highlighted in `panel2`.

### LogZh.js translation (a pure function `translate(line)`, safe to port as-is)
- **Line grammar** (`:153`): `^(YYYY-MM-DD )?(HH:MM:SS )?LEVEL[uptime] ([connId duration] )?body`.
  - A line that does not match is returned unchanged.
  - The output is `date time 级别 `, then `[connId 时长] ` if present, then either `模块：消息` or `消息`. The `sing-box:` module prefix is not split off; its body goes straight to `message`.
- **levels** (`:10`): TRACE 跟踪, DEBUG 调试, INFO 信息, WARN/WARNING 警告, ERROR 错误, FATAL 致命, PANIC 崩溃.
- **tags** (`:12-16`): proxy 代理, bypass 直连, direct 直连, block 拦截, mixed-in 本地端口, tun-in TUN, dns-remote 远程 DNS, dns-direct 直连 DNS, dns-local 系统 DNS, dns-fake FakeIP.
- **module()** (`:21-29`):
  - `inbound/x[tag]` → `入站·tag`
  - `outbound/x[tag]` → `出站·tag`
  - `dns/x[tag]` → `DNS·tag`
  - Fixed names: router 路由, dns DNS, connection 连接, network 网络, reference 资源, sing-box, inbound 入站, outbound 出站, route 路由, ntp 时间同步, rule-set 规则集, clash_api 控制接口.
- **errors** (`:32-68`): 35 regex→Chinese substitutions, applied in order with the longest first. They cover Windows WSA messages, Go net errors (`connection refused` → 连接被拒绝, `i/o timeout` → 超时, `EOF` → 连接被关闭（EOF）, `dial tcp` → TCP 连接, …). The `connectex:` / `wsarecv:` etc. prefixes are stripped. After that, any `outbound/x[tag]` left in the text becomes `出站·tag`.
- **actions**: sniff 嗅探协议, reject 拦截, hijack-dns 接管 DNS, resolve 解析, route-options 设置路由选项, and `route(tag)` → `→ tag`.
- **conditions**: process_name 进程, process_path 进程路径, rule_set 规则集, domain_suffix 域名后缀, domain_keyword 域名关键词, domain_regex 域名正则, domain 域名, ip_cidr IP 段, ip_is_private 私有 IP, protocol 协议, inbound 入站, network 网络, port 端口, query_type 查询类型.
- **rcodes**: NOERROR 成功, NXDOMAIN 域名不存在, SERVFAIL 服务器失败, REFUSED 被拒绝, FORMERR 格式错误.
- **messages** (`:95-136`): 37 patterns over the message body; the first match wins, otherwise the body goes through `err()`. Examples:
  - `match[n] cond => action` → `命中规则 #n：cond action` (`pre-` gives 预匹配)
  - `sniffed protocol: p, domain: d` → `识别到协议 p，域名 d`
  - `inbound connection from X` → `新连接，来自 X`
  - `outbound connection to X` → `已连接 X`
  - `connection closed: e` → `连接关闭：e`
  - `open connection to T using O: e` → `经由<module>连接 T 失败：e`
  - DNS `exchanged/cached TYPE domain TTL IN T value` → `解析：/缓存：domain TYPE → value（TTL n）`
  - `sing-box started (d)` → `sing-box 已启动（耗时 …）`
  - `updated default interface X, index N` → `默认网卡：X（索引 N）`
  - `router: closing` → `正在关闭`
- **duration()** (`:147-149`): ms 毫秒, µs 微秒, ns 纳秒, s 秒, h 时, m 分.
- The translator only knows sing-box wording. Xray log lines pass through mostly untranslated apart from the `err()` phrase substitutions.

---

## 5. HelpPage full text (`qml/pages/HelpPage.qml:31-68`)

**Layout:**
- Back link `‹ 设置` (13 px). Title `操作说明` (15 px DemiBold).
- Section titles are 11 px, `fg3`, letter-spacing 1.
- Each entry is a name (13 px Medium, `fg`) over a body (12 px, `fg2`, wrapped). Column spacing is 10.

Verbatim text:

**顶栏**
- **开关**：打开：按当前节点启动代理，并按下面的「模式」接管流量。关闭：停止代理，恢复系统原有网络设置。
- **规则 / 全局 / 直连**：规则：按路由页的名单和应用列表分流，没命中的走「未匹配流量」。全局：不看域名和 IP 名单，所有没被拦截、也没有应用规则的流量都走代理。直连：域名 / IP 名单、拦截和应用列表照常生效，只是没命中任何规则的流量改为直连，不走节点。
- **系统代理 / TUN**：系统代理：只把浏览器等会读取系统代理设置的程序接到本地端口，其他程序不受影响。TUN：建立虚拟网卡接管整台电脑的全部流量，游戏、命令行等不支持代理的程序也能走节点；需要管理员权限。

**路由页**
- **规则分流 / 应用分流**：只是切换显示哪一部分，不影响实际生效。真正决定的是设置里的「分流方式」。
- **走代理 · 域名 / IP**：命中这些域名或 IP 段的流量走节点。一行一条，支持 geosite:、geoip:、full:、domain:、keyword:、regexp: 前缀和 CIDR。
- **直连 · 域名 / IP**：命中的流量不走节点，直接从本机出去。常用于国内网站、局域网。
- **拦截 · 域名 / IP**：命中的流量直接丢弃，对所有程序都有效，优先级最高。常用于广告和跟踪域名。
- **应用分流列表**：按程序决定去向：代理、直连或拦截，不看它访问的是什么网站。系统代理模式下只对走系统代理的程序有效，TUN 模式下对所有程序有效。
- **未匹配流量**：没有命中任何规则和应用列表的流量怎么走。代理：走节点。直连：不走节点。只想让个别程序走节点时，把它设为直连，再在应用列表里加上那几个程序。
- **远程 DNS / 直连 DNS**：远程 DNS 经代理解析，用于走代理的域名；直连 DNS 在本地解析，用于直连域名和节点服务器地址。
- **DNS 分流**：开启：按域名名单选择用哪个 DNS，减少 DNS 泄漏。关闭：不再按名单分流，除节点服务器地址外都用远程 DNS 解析。
- **导出 JSON**：把当前节点和设置生成的内核配置复制到剪贴板，用于排查问题或在别处使用。

**设置页**
- **布局**：横屏：左侧导航加右侧详情，适合桌面。竖屏：窄窗口，适合贴边放置。Ctrl+L 快速切换。
- **主题**：浅色、深色、跟随系统，或深浅组合（浅色内容配深色导航栏）。
- **开机自启**：开启：登录 Windows 后自动启动并最小化到托盘。关闭：需要手动打开。
- **模式**：与顶栏的「系统代理 / TUN」是同一个设置。
- **分流方式**：规则分流：只用域名和 IP 名单，忽略应用列表。应用分流：只用应用列表，忽略名单。同时生效：先拦截名单，再应用列表，再代理 / 直连名单，最后未匹配流量。
- **本地端口**：本机监听的代理端口，socks 与 http 共用。系统代理模式下浏览器连的就是它。
- **允许局域网**：开启：监听 0.0.0.0，同一局域网的其他设备可以把代理指向本机。关闭：只有本机能用。
- **测试设置**：各种测速用的目标网址、服务器、次数和超时。
- **协议栈**：TUN 的实现方式。gvisor 兼容性最好；system 性能更高；mixed 两者折中。遇到问题先换回 gvisor。
- **严格路由 / Kill Switch**：开启：TUN 运行期间所有流量必须经过 TUN，程序无法绕过代理，Windows 下还会加防火墙规则防 DNS 泄漏。关闭：允许流量绕过 TUN。仅 TUN 模式有效，核心停止后不再拦截。
- **FakeIP**：开启：TUN 下用假 IP 加速 DNS，上网更快但少数程序不兼容。关闭：正常解析。
- **IPv6**：开启：TUN 同时接管 IPv6 流量。关闭：只处理 IPv4，IPv6 按系统原样。
- **重启核心**：重新启动后台核心进程，连接会断开一次。核心异常时使用。

Entries that need rewording on mobile:
- **系统代理 / TUN**, **模式**: there is no system proxy mode on mobile; traffic always goes through the VPN.
- **TUN** body: "需要管理员权限" does not apply.
- **布局**: mentions Ctrl+L.
- **开机自启**: mentions Windows and the tray.
- **严格路由**: mentions the Windows firewall.
- **应用分流列表**: the system proxy sentence does not apply.

---

## 6. Theme and components (`qml/Theme.qml`)

The design rule is **grey-only UI**. Colour is reserved for connection state (accent), latency (good/warn/bad) and errors (`Theme.qml:4-5`).

- `dark` is true when mode is `dark`, or when mode is `system` and the OS is in dark mode (`:9`).
- `railDark = dark || mode==="mixed"`. In mixed mode the content stays light while the rail and tab bar are dark (`:11`).

### Content tokens (`:20-32`)

| token | light | dark |
|---|---|---|
| bg | `#f3f4f6` | `#111317` |
| panel | `#ffffff` | `#1a1d23` |
| panel2 | `#e9ebef` | `#22262e` |
| line | `#dcdfe5` | `#2c313b` |
| fg | `#15181d` | `#e8eaee` |
| fg2 | `#5b6270` | `#a0a7b4` |
| fg3 | `#8a909c` | `#6c7482` |
| accent (= good) | `#1f8f5f` | `#34b27a` |
| accentSoft | `#dcf2e7` | `#17352a` |
| accentFg | `#ffffff` | `#0b1a13` |
| warn | `#c48a08` | `#d9a420` |
| bad | `#c9433d` | `#e0605a` |

### Rail and tab-bar tokens (`:12-18`)
When `railDark` is true the rail uses fixed dark values. Otherwise each rail token equals the content token of the same name.

| token | railDark value | otherwise |
|---|---|---|
| railBg | `#111317` | bg |
| railPanel | `#1a1d23` | panel |
| railPanel2 | `#22262e` | panel2 |
| railLine | `#2c313b` | line |
| railFg | `#e8eaee` | fg |
| railFg2 | `#a0a7b4` | fg2 |
| railFg3 | `#6c7482` | fg3 |

**Mixed mode** therefore uses the light content column together with the dark rail column.

### Type and shape
- **Fonts** (`:34-35`):
  - UI font: Microsoft YaHei UI (Windows), PingFang SC (macOS), Noto Sans CJK SC (other platforms).
  - Mono font: Cascadia Mono (Windows), Menlo (other platforms).
- **Font sizes used:** 10, 11 (captions, logs, letter-spacing 1), 12, 13 (buttons, menus), 14 (base), 15 (page titles, DemiBold), 17 (portrait node name), 18 (`›` chevron), 20 and 26 (portrait status numbers), 22 Light (the RoundAdd `+`).
- **Weights:** Normal, Medium (row titles), DemiBold (titles, selected tab).
- **Radii:**
  - `Theme.radius = 8`: rows, tiles, log box.
  - 4: protocol chips.
  - 12: status card, context menu.
  - 16: 32 px pills and chips.
  - 21 / 26: rail items / rail container.
  - 22: popup card.
  - 28: confirm dialog.
  - Full pill (`height/2` or 999): buttons, inputs, segmented control.
- **Spacing:** page gutter 18 px; content max width 640; Field rows at least 44 px plus 12 px padding with a 1 px bottom line; nav rows 56 px; top bar 56; tab bar 64; detail column padding 16, gap 10.

### Helpers (`:38-56`)
- `lookColor`: `tun` → bad (red), `on` → accent, otherwise fg3.
- `latColor(ms)`: 0 or undefined → fg3; below 0 → bad; below 80 → good; below 150 → warn; otherwise bad.
- `latText(ms)`: 0 → `未测`; below 0 → `超时`; otherwise `N ms`.
- `bytes(n)`: `B`, `KB` and `MB` with 1 decimal; `GB` with 2 decimals; base 1024.

### Components
- **Toggle** (`components/Toggle.qml`): 46×26, radius 13. Track is `accent` when on, `panel2` when off. Knob is 20 px, colour `panel`, at x=3 (off) or x=23 (on), animated over 150 ms. Opacity is 0.5 when disabled.
- **Seg** (`components/Seg.qml`): segmented control. Pill container in `panel2`, 28 px high, 2 px inset. Items are 24 px high with 12 px padding; the selected item is filled with `panel` and uses `fg`, others use `fg2`; 12 px text.
- **Field** (`components/Field.qml`): title (Medium, `fg`) and optional detail (12 px, `fg2`) on the left, control on the right. When `width < control width + 160`, the control drops below the text. 1 px bottom line.
- **Input** (`components/Input.qml`): pill TextField in `panel2`, 14 px horizontal padding. Text `fg`, placeholder `fg2`, selection `accent` with `accentFg` text.
- **PillButton** (`components/PillButton.qml`): 32 px high, at least 72 px wide, 16 px padding, 13 px text.
  - Highlighted (primary): fill `fg` (`fg2` while pressed), text `panel`.
  - Normal: fill `panel2` with a 1 px `line` border.
  - Flat: transparent, no border.
  - Hover → `panel2`; pressed → `line`.
  - Opacity 0.45 when disabled; colour animates over 100 ms.
- **RoundAdd** (`components/RoundAdd.qml`): a 36×36 highlighted PillButton showing `+` at 22 px Light. Used for 导入 / 添加.
- **CheckDot** (`components/CheckDot.qml`): 16 px circle. Unchecked: 1.5 px `fg3` border. Checked: filled `fg` with a bold 11 px `✓` in `panel`.
- **Dropdown / PickPopup** (supporting components):
  - The Dropdown button is a PillButton showing `label + "  ▾"`.
  - The popup is a `panel` card, radius 22, 6 px padding, 1 px border. Items are 32 px high, radius 16, 14 px padding, 13 px text; the current item is `panel2` and DemiBold, others are `fg2`.

---

## 7. Logo (Mark) and assets

`components/Mark.qml` **does not draw anything**. It is an `Image` that loads `qrc:/qt/qml/DuoBaoProxy/res/logo-<look>.png`, with `look` one of `off|on|tun` (default size 28, source size 2×, aspect-fit, mipmapped) (`Mark.qml:4-12`). The `look` value comes from `App.qml:34`:
- `off` when disconnected.
- `tun` when connected, `coreHasTun`, and `ui.mode==="tun"`.
- `on` otherwise.

The same PNGs are used for the tray icon (`src/system.cpp:275-301`). `logo.png` is the window icon (`src/main.cpp:38`). The assets are bundled via `CMakeLists.txt:73-76`.

| File | Size | Content | Circle colour (sampled) |
|---|---|---|---|
| `res/logo.png` | 256×256 RGBA | App icon: a cartoon white rabbit (pink inner ears, brown eyes, open smiling mouth, paws resting on the bottom edge). The head and ears stick out of the top of a filled circle. Transparent background. | Blue ≈ `#0e76fd` → `#0666f7` |
| `res/logo-off.png` | 256×256 | Same rabbit | Grey ≈ `#88919c` → `#808196` |
| `res/logo-on.png` | 256×256 | Same rabbit | Green ≈ `#1daa71` → `#159a6b` |
| `res/logo-tun.png` | 256×256 | Same rabbit | Red ≈ `#de494a` → `#d63944` |
| `res/logo.ico` | multi-size | Windows exe icon | — |
| `res/app.rc` | — | Version info: Company `DuoBao`, Product `多宝代理 DuoBao Proxy`, Original filename `DuoBaoProxy.exe`, version 1.0, language 0x0804 | — |

The circles have a slight vertical gradient, which is why each colour above is given as a range. The rabbit's white is about `#fdfdfb`.

- **Android:** build the launcher icon from `logo.png` as an adaptive icon, with the rabbit as foreground and the blue as background. The off/on/tun variants can serve as status images or as the VPN notification's large icon. The notification small icon must be a monochrome silhouette, which does not exist yet.
- **iOS:** the AppIcon needs a 1024 px version. The current source is only 256 px, so it needs an upscale or the original artwork.