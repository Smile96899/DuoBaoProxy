package com.duobao.proxy.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 语言: the app is written in Chinese; [tr] maps a displayed Chinese string to English when English
 * is on. Exact strings come from [EN]; strings with values in them (`导入 3 个节点到「默认」`) from
 * [TEMPLATES], whose `{n}` parts are translated in turn. Anything unknown (node names, core errors,
 * the log) stays as it is. The UI's `Text` calls [tr], so a language switch recomposes everything.
 */
object I18n {
    /** `zh` or `en`; Compose state so a switch recomposes every `Text`. */
    var lang by mutableStateOf("zh")
        private set

    /** Applies 设置 › 语言: `zh`, `en`, or `system` (Chinese for a Chinese system locale). */
    fun apply(pref: String) {
        val l = when (pref) {
            "zh", "en" -> pref
            else -> if (Locale.getDefault().language == "zh") "zh" else "en"
        }
        if (l != lang) {
            lang = l
            synchronized(cache) { cache.clear() }
        }
    }

    private val cjk = Regex("[\\u4e00-\\u9fff]")
    private val cache = HashMap<String, String>()

    fun tr(s: String): String {
        if (lang == "zh" || !cjk.containsMatchIn(s)) return s
        EN[s]?.let { return it }
        synchronized(cache) { cache[s] }?.let { return it }
        val out = template(s) ?: s
        synchronized(cache) {
            if (cache.size > 4000) cache.clear()
            cache[s] = out
        }
        return out
    }

    private fun template(s: String): String? {
        for ((re, en) in compiled) {
            val m = re.matchEntire(s) ?: continue
            var out = en
            m.groupValues.drop(1).forEachIndexed { i, v -> out = out.replace("{$i}", tr(v)) }
            return out
        }
        return null
    }

    private val compiled: List<Pair<Regex, String>> by lazy {
        TEMPLATES.entries.sortedByDescending { it.key.length }.map { (zh, en) ->
            val pattern = zh.split(Regex("\\{\\d}")).joinToString("(.+?)") { Regex.escape(it) }
            Regex(pattern, RegexOption.DOT_MATCHES_ALL) to en
        }
    }

    val TEMPLATES = mapOf(
        "核心初始化失败：{0}" to "Core failed to start: {0}",
        "无法写入 {0}" to "Cannot write {0}",
        "DNS 查询失败：{0}" to "DNS query failed: {0}",
        "{0} 不支持 {1}" to "{0} does not support {1}",
        "{0} 不支持" to "Not supported by {0}",
        "无法启动 VPN 服务：{0}" to "Cannot start the VPN service: {0}",
        "错误：{0}" to "Error: {0}",
        "已断开：{0}" to "Disconnected: {0}",
        "刚测试的 {0} 个节点都无法连接" to "None of the {0} nodes just tested can connect",
        "导入 {0} 个节点到「{1}」" to "Imported {0} nodes into \"{1}\"",
        "导入 {0} 个节点到「{1}」，{2} 行失败" to "Imported {0} nodes into \"{1}\", {2} lines failed",
        "订阅 {0} 更新失败：{1}" to "Updating subscription {0} failed: {1}",
        "订阅 {0} 没有解析出节点，保留原有节点" to "Subscription {0} contained no nodes; the old nodes were kept",
        "订阅 {0} 没有解析出节点，保留原有节点（{1} 行失败）" to "Subscription {0} contained no nodes ({1} lines failed); the old nodes were kept",
        "「{0}」不是订阅分组" to "\"{0}\" is not a subscription group",
        "「{0}」已过期" to "\"{0}\" has expired",
        "「{0}」将在 {1} 天内到期" to "\"{0}\" expires within {1} days",
        "「{0}」剩余 {1}" to "\"{0}\" has {1} left",
        "删除节点「{0}」？" to "Delete node \"{0}\"?",
        "删除分组「{0}」？" to "Delete group \"{0}\"?",
        "删除订阅「{0}」？" to "Delete subscription \"{0}\"?",
        "分组「{0}」" to "Group \"{0}\"",
        "已安装应用 · 已选 {0} 个" to "Installed apps · {0} selected",
        "已保存 {0} 个应用" to "Saved {0} apps",
        "已选 {0} 个" to "{0} selected",
        "{0} 个超时" to "{0} timed out",
        "{0} 个" to "{0}",
        "{0} 秒" to "{0} s",
        "每 {0} 小时" to "Every {0} h",
        "{0} 小时" to "{0} h",
        "{0} 天" to "{0} days",
        "{0} 分钟前" to "{0} min ago",
        "{0} 小时前" to "{0} h ago",
        "{0} 天前" to "{0} days ago",
        "{0}更新" to "Updated {0}",
        "{0}到期" to "Expires {0}",
        "共 {0} 个订阅 · {1}" to "{0} subscriptions · {1}",
        "上次更新 {0}" to "Last updated {0}",
        "剩余 {0} / {1}" to "{0} left of {1}",
        "版本 {0}" to "Version {0}",
        "  规则集上次更新：{0}" to "  Rule sets updated: {0}",
        "未命中 → {0}" to "Unmatched → {0}",
        "导入失败：{0}" to "Import failed: {0}",
        "导出失败：{0}" to "Export failed: {0}",
        "读取失败：{0}" to "Read failed: {0}",
        "JSON 格式错误：{0}" to "Invalid JSON: {0}",
        "无网络 · {0}" to "Offline · {0}",
        "{0} · 支持 {1} 种协议" to "{0} · {1} protocols",
        "平均 {0} ms · 最小 {1} · 最大 {2} · 抖动 {3} ms · 丢包 {4}%" to "avg {0} ms · min {1} · max {2} · jitter {3} ms · loss {4}%",
        "可能泄漏：本地网络所在地（{0}）的 DNS 收到了查询" to "Possible leak: a DNS server in your local network's country ({0}) saw the queries",
        "无泄漏（本地网络在{0}）" to "No leak (local network in {0})",
        "规则数据更新失败：{0}" to "Updating rule data failed: {0}",
        "分享失败：{0}" to "Share failed: {0}",
        "已连接：{0}" to "Connected: {0}",
    )

    val EN = mapOf(
        // navigation and shared
        "首页" to "Home", "订阅" to "Subscriptions", "规则" to "Rules", "设置" to "Settings",
        "取消" to "Cancel", "关闭" to "Close", "保存" to "Save", "删除" to "Delete", "编辑" to "Edit", "添加" to "Add",
        "创建" to "Create", "重命名" to "Rename", "恢复" to "Restore", "合并" to "Merge", "导入" to "Import", "粘贴" to "Paste",
        "分享" to "Share", "链接" to "Link", "测试" to "Test", "更新" to "Update", "全部" to "All", "全选" to "Select all",
        "全不选" to "Select none", "清空" to "Clear", "开启" to "On", "已关闭" to "Off", "未启用" to "Off", "正常" to "OK",
        "自动" to "Auto", "系统" to "System", "自定义" to "Custom", "高级" to "Advanced", "选项" to "Options", "类型" to "Type",
        "名称" to "Name", "地址" to "Address", "端口" to "Port", "用户名" to "Username", "密码" to "Password", "版本" to "Version",
        "路径" to "Path", "指纹" to "Fingerprint", "插件" to "Plugin", "分组" to "Group", "节点" to "Nodes", "日志" to "Log",
        "默认" to "Default", "上一个" to "Previous", "下一个" to "Next", "上移" to "Move up", "下移" to "Move down",
        "刚刚" to "just now", "未更新" to "Never updated", "尚未更新" to "Not updated yet", "未测" to "untested",
        "失败" to "failed", "超时" to "timeout", "M月d日" to "MMM d",
        // modes and outbounds
        "全局" to "Global", "直连" to "Direct", "代理" to "Proxy", "拦截" to "Block", "规则 / 全局 / 直连" to "Rule / Global / Direct",
        "跟随系统" to "System", "浅色" to "Light", "深色" to "Dark",
        "跟踪" to "Trace", "调试" to "Debug", "信息" to "Info", "警告" to "Warning", "错误" to "Error",
        // status and controller messages
        "未连接" to "Disconnected", "正在连接…" to "Connecting…", "已连接" to "Connected", "未选择节点" to "No node selected",
        "先选一个节点" to "Select a node first", "未获得 VPN 权限" to "VPN permission not granted",
        "需要先在应用内授予 VPN 权限" to "Grant the VPN permission in the app first",
        "关闭 TUN 模式时需要先设置代理端口" to "Set a proxy port before turning TUN mode off",
        "正在下载规则数据…" to "Downloading rule data…", "正在测速选择节点…" to "Testing nodes to pick the fastest…",
        "连接已断开" to "Connection lost", "VPN 已被系统或其他 VPN 应用断开" to "The VPN was stopped by the system or another VPN app",
        "VPN 服务已停止" to "VPN service stopped", "核心已退出" to "Core exited", "节点全部超时" to "All nodes timed out",
        "订阅即将到期" to "Subscription expiring", "订阅流量不足" to "Subscription data low",
        "没有可导入的内容" to "Nothing to import", "名称不能为空" to "Name cannot be empty", "已有同名分组" to "A group with this name exists",
        "节点数据无效" to "Invalid node data", "正在更新规则数据…" to "Updating rule data…", "规则数据已更新" to "Rule data updated",
        "核心已重启" to "Core restarted", "节点不存在" to "Node not found", "没有可用的网络" to "No network available",
        "DNS 查询超时 (i/o timeout)" to "DNS query timed out (i/o timeout)", "DNS 查询无应答" to "DNS query got no answer",
        "已关闭 URL Scheme 导入" to "URL scheme import is off", "没有可导入的链接" to "No importable link", "剪贴板" to "Clipboard",
        "VPN 未授权或已被其他 VPN 应用占用" to "VPN not authorized or taken by another VPN app",
        // notifications
        "多宝代理" to "DuoBao Proxy", "VPN 连接" to "VPN connection", "连接状态与实时速度" to "Connection state and live speed",
        "断开" to "Disconnect", "提醒" to "Reminders", "订阅到期、流量不足、连接断开等提醒" to "Expiry, low data and disconnect reminders",
        "正在处理…" to "Working…", "分享日志" to "Share log", "开机自动连接" to "Connect at boot",
        // home
        "无网络" to "Offline", "正在获取出口 IP…" to "Getting exit IP…", "正在获取 IP…" to "Getting IP…", "代理服务" to "Proxy service",
        "无网络，等待网络恢复" to "Offline, waiting for the network", "延迟测试" to "Latency test", "当前节点超时" to "Current node timed out",
        "没有可测试的节点" to "No nodes to test", "测试中…" to "Testing…", "全部测试" to "Test all",
        "扫一扫" to "Scan QR code", "从相册识别二维码" to "QR code from photos", "从剪贴板导入" to "Import from clipboard",
        "添加订阅" to "Add subscription", "手动添加" to "Add manually", "删除后无法恢复。" to "This cannot be undone.",
        "测试本组延迟" to "Test this group", "从剪贴板导入到本组" to "Import from clipboard into this group",
        "取消首页置顶" to "Unpin from home", "在首页置顶" to "Pin to home", "选择并连接" to "Select and connect",
        "显示二维码" to "Show QR code", "分享到其他应用" to "Share to another app", "移动到分组" to "Move to group",
        "这个协议没有通用的分享链接" to "This protocol has no standard share link", "未知地区" to "Unknown region",
        "默认排序" to "Default order", "按速度" to "By speed", "按地区" to "By region",
        // overlays and scanning
        "导入节点" to "Import nodes", "单个 http(s) 链接会作为订阅添加。" to "A single http(s) link is added as a subscription.",
        "分享二维码" to "Share QR code", "二维码" to "QR code", "复制链接" to "Copy link", "已复制分享链接" to "Share link copied",
        "无法读取图片" to "Cannot read the image", "图片中没有可识别的二维码" to "No QR code found in the image",
        "二维码识别失败" to "QR code recognition failed", "没有可用的相机应用" to "No camera app available",
        // node editor
        "不是 JSON 对象" to "Not a JSON object", "添加节点" to "Add node", "编辑节点" to "Edit node", "常用" to "Common", "连接" to "Connect",
        "未列出的字段（如 xhttp 额外参数、TUIC 设置、自定义配置）请在 JSON 里编辑。" to "Edit fields not listed here (xhttp extras, TUIC options, custom config) in JSON.",
        "留空则显示地址" to "Leave empty to show the address", "逗号分隔，如 h2,http/1.1" to "Comma separated, e.g. h2,http/1.1",
        "Reality 公钥" to "Reality public key", "跳过证书验证" to "Skip certificate verification",
        "如 aes-256-gcm、2022-blake3-aes-128-gcm" to "e.g. aes-256-gcm, 2022-blake3-aes-128-gcm", "如 20000-30000" to "e.g. 20000-30000",
        "拥塞控制" to "Congestion control", "WARP 用，如 1,2,3" to "For WARP, e.g. 1,2,3", "握手 SNI" to "Handshake SNI",
        "如 www.microsoft.com" to "e.g. www.microsoft.com", "SS 加密方式" to "SS method", "SS 密码" to "SS password",
        "地址不能为空" to "Address cannot be empty", "端口要在 1–65535 之间" to "Port must be 1–65535",
        "传输方式" to "Transport", "加密方式" to "Encryption", "混淆密码" to "Obfs password", "上行 Mbps" to "Upload Mbps",
        "下行 Mbps" to "Download Mbps", "端口跳跃" to "Port hopping", "本机私钥" to "Private key", "对端公钥" to "Peer public key",
        "预共享密钥" to "Pre-shared key", "本机地址" to "Local address", "认证字符串" to "Auth string", "ShadowTLS 密码" to "ShadowTLS password",
        // groups
        "管理分组" to "Manage groups", "新建" to "New",
        "删除分组会同时删除其中的节点；「默认」分组不能删除。重命名时节点会跟着移动。" to "Deleting a group deletes its nodes; the \"默认\" group cannot be deleted. Renaming keeps the nodes.",
        "从剪贴板导入节点" to "Import nodes from clipboard", "剪贴板里没有可导入的内容" to "Nothing importable on the clipboard",
        "移到其他分组并删除" to "Move to another group and delete", "删除分组" to "Delete group", "新建分组" to "New group",
        "分组名称" to "Group name", "重命名分组" to "Rename group",
        // rules
        "广告拦截" to "Ad blocking", "GeoSite 广告" to "GeoSite ads", "国内网站直连" to "China sites direct", "GeoSite CN" to "GeoSite CN",
        "局域网直连" to "LAN direct", "私有网段" to "Private ranges", "流媒体解锁" to "Streaming unlock", "苹果服务直连" to "Apple services direct",
        "在“规则”模式下生效" to "Applies in Rule mode", "直连模式：命中规则照常生效，其余直连" to "Direct mode: matched rules apply, the rest goes direct",
        "全局模式：只有拦截和分应用代理生效" to "Global mode: only blocking and per-app proxy apply",
        "更新规则集" to "Update rule sets", "分流策略" to "Routing presets", "自定义规则" to "Custom rules", "添加规则" to "Add rule",
        "编辑规则" to "Edit rule", "如 192.168.0.0/16" to "e.g. 192.168.0.0/16", "去向" to "Action", "删除这条规则" to "Delete this rule",
        "如 google" to "e.g. google", "正则表达式" to "Regular expression", "如 google.com" to "e.g. google.com",
        "分应用代理" to "Per-app proxy", "仅所选走代理" to "Only selected use proxy", "所选不走代理" to "Selected bypass proxy",
        "搜索应用" to "Search apps", "保存并应用" to "Save and apply", "已保存：未选择应用，全部走代理" to "Saved: no apps selected, everything uses the proxy",
        // subscriptions
        "6 小时" to "6 h", "12 小时" to "12 h", "24 小时" to "24 h", "请粘贴节点链接" to "Paste node links", "自建节点" to "My nodes",
        "已保存" to "Saved", "编辑订阅" to "Edit subscription", "订阅链接" to "Subscription URL", "扫码" to "Scan", "手动配置" to "Manual",
        "订阅名称" to "Name", "留空则使用链接的域名" to "Leave empty to use the link's domain", "扫描订阅或节点二维码" to "Scan a subscription or node QR code",
        "打开相机" to "Open camera", "从相册选择" to "Choose from photos", "节点链接" to "Node links", "按设定间隔自动拉取节点" to "Fetch nodes on a schedule",
        "设为默认分组" to "Set as default group", "首页优先显示此分组的节点" to "Show this group first on Home", "到期提醒" to "Expiry reminder",
        "手动配置会建立一个普通分组，节点不会自动更新。" to "Manual setup creates a plain group whose nodes never update.",
        "支持 Clash、V2Ray、Shadowsocks 等常见订阅格式，添加后会自动识别。" to "Clash, V2Ray, Shadowsocks and other common formats are detected automatically.",
        "正在保存…" to "Saving…", "请填写 http:// 或 https:// 开头的订阅链接" to "Enter a link starting with http:// or https://",
        "粘贴 https:// 开头的订阅地址" to "Paste a subscription URL starting with https://",
        "更新全部" to "Update all", "还没有订阅" to "No subscriptions yet",
        "添加机场提供的订阅链接，节点会自动导入并定期更新。" to "Add your provider's subscription link; nodes are imported and kept up to date.",
        "立即更新" to "Update now", "取消默认分组" to "Unset default group", "复制订阅链接" to "Copy subscription URL",
        "已复制订阅链接" to "Subscription URL copied", "删除订阅" to "Delete subscription", "到期" to "Expires", "无流量信息" to "No data info",
        "已过期" to "Expired", "正在更新…" to "Updating…", "已停止更新" to "Updates stopped", "自动更新" to "Auto update",
        // settings
        "3 秒" to "3 s", "5 秒" to "5 s", "10 秒" to "10 s", "30 秒" to "30 s",
        "连接" to "Connection", "开机自启" to "Start at boot", "开机后自动连接（需已授予 VPN 权限）" to "Connect after boot (needs the VPN permission)",
        "启动后自动连接" to "Connect on launch", "断线自动重连" to "Auto reconnect", "连接前测速选最快节点" to "Pick the fastest node before connecting",
        "在当前分组里按 TCP 延迟选择" to "By TCP latency within the current group", "网络" to "Network", "DNS" to "DNS", "内核" to "Core",
        "代理端口" to "Proxy port", "本机 socks / http 混合端口，供其他应用使用" to "Local socks / http port for other apps",
        "允许局域网连接" to "Allow LAN connections", "同一网络的设备可以使用代理端口" to "Devices on the same network can use the proxy port",
        "TUN 模式" to "TUN mode", "关闭后不建立 VPN，只开代理端口供应用手动设置" to "When off, no VPN is created; only the proxy port runs for apps set up manually",
        "关闭时 IPv6 流量会被拦截，防止绕过 VPN" to "When off, IPv6 is blocked so it cannot bypass the VPN",
        "在系统设置里打开「始终开启的 VPN」和「屏蔽未使用 VPN 的连接」" to "Turn on \"Always-on VPN\" and \"Block connections without VPN\" in system settings",
        "系统设置" to "System settings", "分流" to "Routing", "代理模式" to "Proxy mode", "规则集管理" to "Rule sets",
        "绕过中国大陆" to "Bypass mainland China", "订阅与节点" to "Subscriptions & nodes", "订阅更新" to "Subscription updates",
        "自动切换最快节点" to "Auto switch to fastest node", "连接后定时测速，当前节点变慢或失效时切换" to "Tests periodically while connected and switches when the node slows down or fails",
        "备份与恢复" to "Backup & restore", "外观与关于" to "Appearance & about", "语言" to "Language", "深色模式" to "Dark mode",
        "简体中文" to "简体中文", "通知" to "Notifications", "连接日志" to "Connection log", "诊断" to "Diagnostics",
        "操作说明与关于" to "Help & about", "检查更新" to "Check for updates", "留空则关闭" to "Leave empty to turn off",
        // DNS
        "分流 DNS" to "Split DNS", "系统 DNS" to "System DNS", "DNS 设置" to "DNS settings", "国内 DNS" to "Domestic DNS", "国外 DNS" to "Overseas DNS",
        "添加服务器" to "Add server", "添加国内 DNS" to "Add domestic DNS", "添加国外 DNS" to "Add overseas DNS", "如 223.5.5.5 或 https://…" to "e.g. 223.5.5.5 or https://…",
        "DNS 分流" to "DNS routing", "按名单选择国内或国外 DNS" to "Choose domestic or overseas DNS by list",
        "DoH 优先" to "Prefer DoH", "列表里的加密 DNS 排在前面优先使用" to "Encrypted servers in the list are tried first",
        "加快首包，部分应用可能不兼容" to "Faster first packet; some apps may not work", "DNS 劫持" to "DNS hijacking",
        "应用自带的 DNS 查询也交给内核解析" to "DNS queries apps send to their own servers are resolved by the core too",
        "国内域名用国内 DNS 解析，其余走国外 DNS，避免 DNS 污染和泄露。支持 223.5.5.5、tls://、https://、quic:// 等写法，填 local 使用系统 DNS。Xray 按列表顺序依次尝试；sing-box 使用每组的第一个。" to
            "Domestic domains use the domestic DNS, the rest the overseas DNS, avoiding poisoning and leaks. 223.5.5.5, tls://, https://, quic:// and local (system DNS) are accepted. Xray tries the list in order; sing-box uses the first of each list.",
        // core
        "切换内核" to "Switch core", "多路复用" to "Multiplexing", "减少握手，部分节点不支持" to "Fewer handshakes; some nodes do not support it",
        "减少建立连接的延迟，需要节点和系统支持" to "Lower connection setup latency; needs node and system support",
        "日志级别" to "Log level", "运行中" to "Running", "未运行" to "Not running",
        "切换内核后会自动重新连接，节点配置无需修改。当前内核不支持的协议会在首页变灰。" to "Switching cores reconnects automatically; nodes need no changes. Protocols the current core cannot carry are greyed out on Home.",
        // subscription updates
        "更新间隔" to "Interval", "每 6 小时" to "Every 6 h", "每 12 小时" to "Every 12 h", "每 24 小时" to "Every 24 h", "每次启动时" to "On every launch",
        "更新方式" to "Update method", "仅 Wi-Fi 下更新" to "Wi-Fi only", "使用移动数据时跳过自动更新" to "Skip auto updates on mobile data",
        "通过代理更新" to "Update via proxy", "已连接时经代理下载订阅，失败再直连" to "Download through the proxy while connected, falling back to direct",
        "已连接时订阅经代理下载；未连接时直接从本机网络拉取。" to "While connected, subscriptions download through the proxy; otherwise directly.",
        "订阅直接从本机网络拉取，不经过代理。" to "Subscriptions download directly, not through the proxy.",
        "自动检测剪贴板" to "Detect clipboard", "打开应用时发现分享链接就提示导入" to "Offer to import share links when the app opens",
        "允许 URL Scheme 导入" to "Allow URL scheme import", "其他应用打开 vmess:// 等链接时导入" to "Import vmess:// and similar links opened by other apps",
        // latency
        "测试地址" to "Test URL", "自定义测试地址" to "Custom test URL",
        "首页「测试」和连接后的实时延迟都使用这里的设置。" to "Home's Test button and the live latency use these settings.",
        // notifications page
        "常驻通知显示速率" to "Show speed in notification", "通知栏显示实时上传 / 下载速度" to "Live upload / download speed in the notification",
        "连接状态变化" to "Connection changes", "意外断开时提醒" to "Notify on unexpected disconnects", "订阅到期提醒" to "Expiry reminder",
        "提前提醒" to "Remind before", "1 天" to "1 day", "3 天" to "3 days", "7 天" to "7 days", "流量不足提醒" to "Low data reminder",
        "剩余低于" to "When below", "到期和流量提醒在订阅更新后检查，每个订阅每天最多提醒一次。" to "Expiry and data reminders are checked after updates, at most once a day per subscription.",
        // about
        "打开：按当前节点建立 VPN，接管手机的全部流量。关闭：停止代理。" to "On: starts the VPN with the current node and takes over all traffic. Off: stops the proxy.",
        "规则：按「规则」页的分流策略和自定义规则决定去向，没命中的走「未命中」设置。全局：除拦截和分应用代理外，所有流量都走代理。直连：规则照常生效，没命中的直连。" to
            "Rule: the presets and custom rules on the Rules page decide; the rest follows \"Unmatched\". Global: everything uses the proxy except blocking and per-app choices. Direct: rules apply, the rest goes direct.",
        "点一下选中并切换节点；长按可编辑、测试、分享、移动或删除。灰色节点是当前内核不支持的协议。" to "Tap to select and switch; long-press to edit, test, share, move or delete. Grey nodes use protocols the current core cannot carry.",
        "通过节点访问测试地址，计算往返时间。地址和超时在「设置 › 延迟测试」修改。" to "Fetches the test URL through the node and measures the round trip. Change it in Settings › Latency test.",
        "订阅与规则" to "Subscriptions & rules",
        "添加机场的订阅链接，节点会导入到同名分组并按间隔自动更新；更新失败或没有解析出节点时保留原有节点。" to "Add a subscription link; its nodes go into a group of the same name and update on schedule. Failed or empty updates keep the old nodes.",
        "常用的直连、代理和拦截名单，打开即生效。" to "Common direct, proxy and block lists; they apply as soon as they are on.",
        "按域名、关键词、正则、GeoSite、IP 段或 GeoIP 决定代理、直连或拦截。" to "Proxy, direct or block by domain, keyword, regex, GeoSite, IP range or GeoIP.",
        "仅所选走代理：只有选中的应用经过 VPN。所选不走代理：选中的应用直接联网。" to "Only selected: only the chosen apps use the VPN. Selected bypass: the chosen apps connect directly.",
        "关于" to "About", "多宝代理 DuoBao Proxy" to "DuoBao Proxy", "关于开发者" to "Developer", "发布页" to "Releases",
        "关闭时 IPv6 流量会被拦截，避免绕过 VPN。网络支持 IPv6 时可以打开。" to "When off, IPv6 is blocked so it cannot bypass the VPN. Turn on if your network supports IPv6.",
        "本机 socks / http 混合端口，供不支持 VPN 的应用手动设置代理；允许局域网后可给同一 Wi-Fi 下的设备使用。" to "A local socks / http port for apps set up manually; with LAN allowed, devices on the same Wi-Fi can use it.",
        // backup
        "无法写入文件" to "Cannot write the file", "已导出配置" to "Configuration exported", "无法读取文件" to "Cannot read the file",
        "上次备份" to "Last backup", "从未备份" to "Never", "导出配置" to "Export configuration", "导入配置" to "Import configuration",
        "备份内容" to "Contents", "分流规则和分应用代理" to "Routing rules and per-app proxy", "恢复默认设置" to "Reset settings",
        "合并：把文件里的节点和分组追加进来，本机的设置不变。" to "Merge: adds the file's nodes and groups; settings stay unchanged.",
        "覆盖：用文件替换本机的全部数据。" to "Replace: the file replaces all data.", "覆盖导入" to "Replace", "已覆盖导入" to "Replaced with the file",
        "恢复默认设置？" to "Reset settings?", "规则、DNS、端口等设置会恢复为初始值，节点和订阅保留。" to "Rules, DNS, ports and other settings go back to defaults; nodes and subscriptions stay.",
        "已恢复默认设置" to "Settings reset",
        // diagnostics
        "网络连接" to "Network", "移动网络" to "Mobile data", "DNS 解析" to "DNS resolution", "无法连接" to "Unreachable",
        "规则集加载" to "Rule sets", "缺少规则数据" to "Rule data missing", "节点连通" to "Node reachability", "全部正常" to "All OK",
        "全部超时" to "All timed out", "内核状态" to "Core", "DuoBao Proxy 诊断报告" to "DuoBao Proxy diagnostics",
        "导出诊断报告" to "Export report", "检测中…" to "Checking…", "正在诊断…" to "Diagnosing…", "重新诊断" to "Run again",
        "清除缓存" to "Clear cache", "已清除缓存" to "Cache cleared", "重启核心" to "Restart core",
        "核心异常时使用，连接会断开一次" to "Use when the core misbehaves; the connection drops once",
        // logs
        "日志条数" to "Lines", "本次上传" to "Uploaded", "本次下载" to "Downloaded", "暂无日志" to "No log yet",
        // tests
        "无泄漏" to "No leak", "真连接延迟" to "Real latency", "真连接" to "Real", "通过节点访问一个网址，计算往返时间" to "Fetches a URL through the node and measures the round trip",
        "TCP 延迟" to "TCP latency", "直接连接节点服务器的端口，不经过代理" to "Connects to the node's port directly, not through the proxy",
        "UDP 延迟" to "UDP latency", "通过节点发一次 DNS 查询，也能看出是否支持 UDP" to "One DNS query through the node; also shows UDP support",
        "丢包 / 抖动" to "Loss / jitter", "丢包" to "Loss", "连续多次 TCP 连接，统计平均、抖动和丢包" to "Repeated TCP connects: average, jitter and loss",
        "上传速度" to "Upload speed", "上传" to "Upload", "通过节点上传测速数据" to "Uploads test data through the node",
        "出口 IP 与地区" to "Exit IP & region", "地区" to "Region", "通过节点查询对方看到的 IP 和所在地区" to "The IP and region the internet sees through the node",
        "流媒体" to "Streaming", "AI 服务可用性" to "AI services", "测试网址" to "Test URL", "返回 2xx / 3xx 即视为成功" to "2xx / 3xx counts as success",
        "超时（秒）" to "Timeout (s)", "DNS 服务器" to "DNS server", "IP:端口" to "IP:port", "上传网址" to "Upload URL", "需接受 POST 上传" to "Must accept POST",
        "上传大小（MB）" to "Upload size (MB)", "查询网址" to "Query URL", "需返回 Cloudflare trace 格式（ip= / loc=）" to "Must return the Cloudflare trace format (ip= / loc=)",
        "检测项目" to "Checks",
        // countries
        "中国" to "China", "香港" to "Hong Kong", "台湾" to "Taiwan", "澳门" to "Macao", "日本" to "Japan", "韩国" to "South Korea",
        "新加坡" to "Singapore", "马来西亚" to "Malaysia", "泰国" to "Thailand", "越南" to "Vietnam", "菲律宾" to "Philippines",
        "印尼" to "Indonesia", "印度" to "India", "澳大利亚" to "Australia", "新西兰" to "New Zealand", "美国" to "United States",
        "加拿大" to "Canada", "墨西哥" to "Mexico", "巴西" to "Brazil", "阿根廷" to "Argentina", "英国" to "United Kingdom",
        "德国" to "Germany", "法国" to "France", "荷兰" to "Netherlands", "意大利" to "Italy", "西班牙" to "Spain", "瑞士" to "Switzerland",
        "瑞典" to "Sweden", "挪威" to "Norway", "芬兰" to "Finland", "丹麦" to "Denmark", "波兰" to "Poland", "俄罗斯" to "Russia",
        "乌克兰" to "Ukraine", "土耳其" to "Turkey", "阿联酋" to "UAE", "以色列" to "Israel", "沙特" to "Saudi Arabia", "南非" to "South Africa",
        "埃及" to "Egypt", "爱尔兰" to "Ireland", "奥地利" to "Austria", "比利时" to "Belgium", "葡萄牙" to "Portugal", "捷克" to "Czechia",
        "罗马尼亚" to "Romania", "匈牙利" to "Hungary", "希腊" to "Greece", "哈萨克斯坦" to "Kazakhstan", "卢森堡" to "Luxembourg",
        "冰岛" to "Iceland", "智利" to "Chile", "哥伦比亚" to "Colombia", "柬埔寨" to "Cambodia", "老挝" to "Laos", "缅甸" to "Myanmar",
        "孟加拉" to "Bangladesh", "尼泊尔" to "Nepal", "斯里兰卡" to "Sri Lanka", "蒙古" to "Mongolia", "卡塔尔" to "Qatar",
        "保加利亚" to "Bulgaria", "秘鲁" to "Peru", "巴基斯坦" to "Pakistan", "伊朗" to "Iran", "尼日利亚" to "Nigeria",
    )
}

/** [I18n.tr]: the current language's form of a displayed string. */
fun tr(s: String): String = I18n.tr(s)
