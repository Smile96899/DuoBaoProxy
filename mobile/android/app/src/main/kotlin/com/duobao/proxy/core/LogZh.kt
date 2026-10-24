package com.duobao.proxy.core

/**
 * 1:1 port of `app/qml/LogZh.js`: rewrites sing-box log lines in Chinese for display. A line is
 *
 *     2026-10-22 20:09:57 DEBUG[0006] [1268711528 49ms] dns: exchanged ...
 *
 * The prefix (level, connection id, module) is always translated; the message is matched against
 * the common phrasings below, and the Go error text at its end is translated phrase by phrase.
 * Unknown messages keep their original wording. Pure: safe to call from any thread.
 */
object LogZh {
    private val levels = mapOf(
        "TRACE" to "跟踪", "DEBUG" to "调试", "INFO" to "信息", "WARN" to "警告", "WARNING" to "警告",
        "ERROR" to "错误", "FATAL" to "致命", "PANIC" to "崩溃",
    )

    private val tags = mapOf(
        "proxy" to "代理", "bypass" to "直连", "direct" to "直连", "block" to "拦截",
        "mixed-in" to "本地端口", "tun-in" to "TUN",
        "dns-remote" to "远程 DNS", "dns-direct" to "直连 DNS", "dns-local" to "系统 DNS", "dns-fake" to "FakeIP",
    )

    fun tag(t: String): String = tags[t] ?: t

    private val inOutRe = Regex("""^(inbound|outbound)/([\w-]+)\[([^\]]+)\]$""")
    private val dnsModRe = Regex("""^dns/([\w-]+)\[([^\]]+)\]$""")
    private val moduleNames = mapOf(
        "router" to "路由", "dns" to "DNS", "connection" to "连接", "network" to "网络", "reference" to "资源",
        "sing-box" to "sing-box", "inbound" to "入站", "outbound" to "出站", "route" to "路由", "ntp" to "时间同步",
        "rule-set" to "规则集", "clash_api" to "控制接口",
    )

    /** `inbound/mixed[mixed-in]` → `入站·本地端口`, `outbound/socks[proxy]` → `出站·代理`. */
    fun module(m: String): String {
        inOutRe.find(m)?.let { r -> return (if (r.groupValues[1] == "inbound") "入站·" else "出站·") + tag(r.groupValues[3]) }
        dnsModRe.find(m)?.let { r -> return "DNS·" + tag(r.groupValues[2]) }
        return moduleNames[m] ?: m
    }

    /** Go and Windows error phrases, longest first. */
    private val errors: List<Pair<Regex, String>> = listOf(
        """No connection could be made because the target machine actively refused it\.?""" to "目标主动拒绝连接",
        """A connection attempt failed because the connected party did not properly respond after a period of time, or established connection failed because connected host has failed to respond\.?""" to "连接超时，对方无响应",
        """An established connection was aborted by the software in your host machine\.?""" to "连接被本机中止",
        """An existing connection was forcibly closed by the remote host\.?""" to "连接被远程主机强制关闭",
        """The requested address is not valid in its context\.?""" to "请求的地址无效",
        """A socket operation was attempted to an unreachable (network|host)\.?""" to "网络不可达",
        """use of closed network connection""" to "连接已关闭",
        """read/write on closed pipe""" to "管道已关闭",
        """connection reset by peer""" to "连接被对方重置",
        """connection refused""" to "连接被拒绝",
        """context deadline exceeded""" to "超时",
        """context canceled""" to "已取消",
        """i/o timeout""" to "超时",
        """endpoint not connected""" to "端点未连接",
        """network is unreachable""" to "网络不可达",
        """no such host""" to "找不到主机",
        """certificate signed by unknown authority""" to "证书不受信任",
        """tls: handshake failure""" to "TLS 握手失败",
        """authentication failed""" to "认证失败",
        """process not found for""" to "找不到进程：",
        """\bEOF\b""" to "连接被关闭（EOF）",
        """\bread http request\b""" to "读取 HTTP 请求",
        """\bpeek first byte\b""" to "读取首字节",
        """The operation was cancell?ed\.?""" to "操作已取消",
        """\boperation timed out\b""" to "操作超时",
        """\bdial tcp\b""" to "TCP 连接",
        """\bdial udp\b""" to "UDP 连接",
        """\bread tcp\b""" to "TCP 读取",
        """\bwrite tcp\b""" to "TCP 写入",
        """\bclose tcp\b""" to "TCP 关闭",
        """\bread udp\b""" to "UDP 读取",
        """\bwrite udp\b""" to "UDP 写入",
        """\b(connectex|wsarecv|wsasend|wsarecvfrom|wsasendto): """ to "",
        """\bopen connection to\b""" to "连接",
        """\busing\b""" to "经由",
    ).map { (p, r) -> Regex(p) to r }

    private val outboundTagRe = Regex("""outbound/[\w-]+\[([^\]]+)\]""")

    fun err(text: String): String {
        var s = text
        // The replacements carry no `$` references, so match them literally.
        for ((re, rep) in errors) s = re.replace(s, Regex.escapeReplacement(rep))
        return outboundTagRe.replace(s) { "出站·" + tag(it.groupValues[1]) }
    }

    private val actions = mapOf(
        "sniff" to "嗅探协议", "reject" to "拦截", "hijack-dns" to "接管 DNS", "resolve" to "解析", "route-options" to "设置路由选项",
    )
    private val routeActionRe = Regex("""^route\(([^)]+)\)$""")

    fun action(a: String): String {
        routeActionRe.find(a)?.let { return "→ " + tag(it.groupValues[1]) }
        return actions[a] ?: a
    }

    fun condition(c: String): String = c.replace("process_name=", "进程=").replace("process_path=", "进程路径=")
        .replace("rule_set=", "规则集=").replace("domain_suffix=", "域名后缀=").replace("domain_keyword=", "域名关键词=")
        .replace("domain_regex=", "域名正则=").replace("domain=", "域名=").replace("ip_cidr=", "IP 段=")
        .replace("ip_is_private", "私有 IP").replace("protocol=", "协议=").replace("inbound=", "入站=")
        .replace("network=", "网络=").replace("port=", "端口=").replace("query_type=", "查询类型=")

    private val rcodes = mapOf("NOERROR" to "成功", "NXDOMAIN" to "域名不存在", "SERVFAIL" to "服务器失败", "REFUSED" to "被拒绝", "FORMERR" to "格式错误")

    /**
     * `[pattern, replacement]` over the message part; the first match wins. Group values are "" when a
     * group did not take part, which is falsy exactly like JS `undefined` in the original functions.
     */
    private val messages: List<Pair<Regex, (List<String>) -> String>> = listOf(
        Regex("""^(pre-)?match\[(\d+)\](?: (.+?))? => (.+)$""") to { g ->
            (if (g[1].isNotEmpty()) "预匹配" else "命中") + "规则 #" + g[2] + "：" + (if (g[3].isNotEmpty()) condition(g[3]) + " " else "") + action(g[4])
        },
        Regex("""^sniffed (packet )?protocol: ([\w-]+)(?:, domain: ([^,]+))?(?:, client: (.+))?$""") to { g ->
            "识别到" + (if (g[1].isNotEmpty()) "数据包" else "") + "协议 " + g[2] + (if (g[3].isNotEmpty()) "，域名 " + g[3] else "") +
                (if (g[4].isNotEmpty()) "，客户端 " + g[4] else "")
        },
        Regex("""^attempt to sniff fragmented QUIC client hello$""") to { _ -> "尝试识别分片的 QUIC 握手" },
        Regex("""^found process path: (.+)$""") to { g -> "进程：" + g[1] },
        Regex("""^failed to search process: (.+)$""") to { g -> "查找进程失败：" + err(g[1]) },
        Regex("""^inbound connection from (.+)$""") to { g -> "新连接，来自 " + g[1] },
        Regex("""^inbound connection to (.+)$""") to { g -> "目标 " + g[1] },
        Regex("""^inbound packet connection from (.+)$""") to { g -> "新 UDP 连接，来自 " + g[1] },
        Regex("""^inbound packet connection to (.+)$""") to { g -> "UDP 目标 " + g[1] },
        Regex("""^inbound DNS packet from (.+)$""") to { g -> "DNS 请求，来自 " + g[1] },
        Regex("""^outbound connection to (.+)$""") to { g -> "已连接 " + g[1] },
        Regex("""^outbound packet connection to (.+)$""") to { g -> "已建立 UDP 连接 " + g[1] },
        Regex("""^connection (upload|download) finished$""") to { g -> (if (g[1] == "upload") "上传" else "下载") + "完成" },
        Regex("""^(connection|packet) (upload|download) closed(?:: (.+))?$""") to { g ->
            (if (g[1] == "packet") "UDP " else "") + (if (g[2] == "upload") "上传" else "下载") + "已关闭" + (if (g[3].isNotEmpty()) "：" + err(g[3]) else "")
        },
        Regex("""^connection closed: rejected$""") to { _ -> "连接已拦截" },
        Regex("""^connection closed: (.+)$""") to { g -> "连接关闭：" + err(g[1]) },
        Regex("""^open connection to (\S+) using (\S+): (.+)$""") to { g -> "经由" + module(g[2]) + "连接 " + g[1] + " 失败：" + err(g[3]) },
        Regex("""^lookup domain (.+)$""") to { g -> "查询域名 " + g[1] },
        Regex("""^lookup succeed for ([^:]+): (.+)$""") to { g -> g[1] + " 解析成功：" + g[2] },
        Regex("""^lookup failed for ([^:]+): (.+)$""") to { g -> g[1] + " 解析失败：" + err(g[2]) },
        Regex("""^exchange (\S+?)\.? IN (\w+)$""") to { g -> "查询 " + g[1] + "（" + g[2] + "）" },
        Regex("""^(exchanged|cached) (\w+) (\S+?)\.? (\d+) IN \w+ (.+)$""") to { g ->
            (if (g[1] == "cached") "缓存：" else "解析：") + g[3] + " " + g[2] + " → " + g[5].replace(Regex("""\.$"""), "") + "（TTL " + g[4] + "）"
        },
        Regex("""^(exchanged|cached) (\S+?)\.? (NOERROR|NXDOMAIN|SERVFAIL|REFUSED|FORMERR) (\d+)$""") to { g ->
            (if (g[1] == "cached") "缓存：" else "解析：") + g[2] + " " + rcodes[g[3]] + "（TTL " + g[4] + "）"
        },
        Regex("""^cached OPT .*$""") to { _ -> "缓存：EDNS 选项" },
        Regex("""^(exchanged|cached) (SOA|OPT) .*$""") to { g -> (if (g[1] == "cached") "缓存：" else "解析：") + g[2] + " 记录" },
        Regex("""^sing-box started \((.+)\)$""") to { g -> "sing-box 已启动（耗时 " + duration(g[1]) + "）" },
        Regex("""^sing-box pre-started \((.+)\)$""") to { g -> "sing-box 预启动完成（耗时 " + duration(g[1]) + "）" },
        Regex("""^start (\S+?)(?:\.\.\.| completed \((.+)\))$""") to { g ->
            "启动" + module(g[1]) + (if (g[2].isNotEmpty()) " 完成（耗时 " + duration(g[2]) + "）" else "…")
        },
        Regex("""^creating stack$""") to { _ -> "正在创建网络栈" },
        Regex("""^started at (.+)$""") to { g -> "已启动：" + g[1] },
        Regex("""^outbound packet connection$""") to { _ -> "已建立 UDP 连接" },
        Regex("""^(tcp|udp) server started at (.+)$""") to { g -> g[1].uppercase() + " 服务已启动：" + g[2] },
        Regex("""^updated default interface (\S+), index (\d+)$""") to { g -> "默认网卡：" + g[1] + "（索引 " + g[2] + "）" },
        Regex("""^updated network environment: gateway (\S+), gateway_mac (\S+)$""") to { g -> "网络环境已更新：网关 " + g[1] + "，MAC " + g[2] },
        Regex("""^(.+) is unreferenced, closing idle connections$""") to { g ->
            dnsModRe.replace(err(g[1])) { tag(it.groupValues[2]) } + " 暂未使用，关闭空闲连接"
        },
        Regex("""^router: closing$""") to { _ -> "正在关闭" },
    )

    fun message(text: String): String {
        for ((re, fn) in messages) {
            val m = re.find(text) ?: continue
            return text.substring(0, m.range.first) + fn(m.groupValues) + text.substring(m.range.last + 1)
        }
        return err(text)
    }

    private val durationSteps = listOf(
        Regex("ms$") to "毫秒", Regex("µs$") to "微秒", Regex("ns$") to "纳秒",
        Regex("""(\d)s$""") to "$1秒", Regex("""(\d)h""") to "$1时", Regex("""(\d)m(?!s)""") to "$1分",
    )

    /** `49ms` → `49毫秒`, `1m5.2s` → `1分5.2秒`. Each step replaces the first match only, as in JS. */
    fun duration(d: String): String = durationSteps.fold(d) { s, (re, rep) -> s.replaceFirst(re, rep) }

    private val lineRe = Regex("""^(\d{4}-\d\d-\d\d )?(\d\d:\d\d:\d\d )?([A-Z]+)\[\d+\] (?:\[(\d+) ([^\]]+)\] )?(.*)$""")
    private val bodyRe = Regex("""^([\w/-]+(?:\[[^\]]+\])?): (.*)$""")

    /** Translates one (ANSI-free) engine line; a line not in sing-box format is returned unchanged. */
    fun translate(line: String): String {
        val r = lineRe.matchEntire(line) ?: return line
        val g = r.groupValues
        var out = g[1] + g[2] + (levels[g[3]] ?: g[3]) + " "
        if (g[4].isNotEmpty()) out += "[" + g[4] + " " + duration(g[5]) + "] "
        val body = g[6]
        val mm = bodyRe.matchEntire(body)
        if (mm != null && mm.groupValues[1] != "sing-box") return out + module(mm.groupValues[1]) + "：" + message(mm.groupValues[2])
        return out + message(body)
    }

    private val ansiRe = Regex("\u001b\\[[0-9;]*m")

    /** `line.replace(/\x1b\[[0-9;]*m/g, "")`. */
    fun stripAnsi(line: String): String = ansiRe.replace(line, "")
}
