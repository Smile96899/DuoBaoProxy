.pragma library

// Rewrites sing-box log lines in Chinese for display. A line is
//   2026-10-22 20:09:57 DEBUG[0006] [1268711528 49ms] dns: exchanged ...
// The prefix (level, connection id, module) is always translated; the
// message is matched against the common phrasings below, and the Go error
// text at its end is translated phrase by phrase. Unknown messages keep their
// original wording.

var levels = {TRACE: "跟踪", DEBUG: "调试", INFO: "信息", WARN: "警告", WARNING: "警告", ERROR: "错误", FATAL: "致命", PANIC: "崩溃"}

var tags = {
    "proxy": "代理", "bypass": "直连", "direct": "直连", "block": "拦截",
    "mixed-in": "本地端口", "tun-in": "TUN",
    "dns-remote": "远程 DNS", "dns-direct": "直连 DNS", "dns-local": "系统 DNS", "dns-fake": "FakeIP"
}

function tag(t) { return tags[t] || t }

// inbound/mixed[mixed-in] → 入站·本地端口, outbound/socks[proxy] → 出站·代理
function module(m) {
    var r = /^(inbound|outbound)\/([\w-]+)\[([^\]]+)\]$/.exec(m)
    if (r) return (r[1] === "inbound" ? "入站·" : "出站·") + tag(r[3])
    r = /^dns\/([\w-]+)\[([^\]]+)\]$/.exec(m)
    if (r) return "DNS·" + tag(r[2])
    var names = {router: "路由", dns: "DNS", connection: "连接", network: "网络", reference: "资源", "sing-box": "sing-box",
                 inbound: "入站", outbound: "出站", route: "路由", ntp: "时间同步", "rule-set": "规则集", clash_api: "控制接口"}
    return names[m] || m
}

// Go and Windows error phrases, longest first.
var errors = [
    [/No connection could be made because the target machine actively refused it\.?/g, "目标主动拒绝连接"],
    [/A connection attempt failed because the connected party did not properly respond after a period of time, or established connection failed because connected host has failed to respond\.?/g, "连接超时，对方无响应"],
    [/An established connection was aborted by the software in your host machine\.?/g, "连接被本机中止"],
    [/An existing connection was forcibly closed by the remote host\.?/g, "连接被远程主机强制关闭"],
    [/The requested address is not valid in its context\.?/g, "请求的地址无效"],
    [/A socket operation was attempted to an unreachable (network|host)\.?/g, "网络不可达"],
    [/use of closed network connection/g, "连接已关闭"],
    [/read\/write on closed pipe/g, "管道已关闭"],
    [/connection reset by peer/g, "连接被对方重置"],
    [/connection refused/g, "连接被拒绝"],
    [/context deadline exceeded/g, "超时"],
    [/context canceled/g, "已取消"],
    [/i\/o timeout/g, "超时"],
    [/endpoint not connected/g, "端点未连接"],
    [/network is unreachable/g, "网络不可达"],
    [/no such host/g, "找不到主机"],
    [/certificate signed by unknown authority/g, "证书不受信任"],
    [/tls: handshake failure/g, "TLS 握手失败"],
    [/authentication failed/g, "认证失败"],
    [/process not found for/g, "找不到进程："],
    [/\bEOF\b/g, "连接被关闭（EOF）"],
    [/\bread http request\b/g, "读取 HTTP 请求"],
    [/\bpeek first byte\b/g, "读取首字节"],
    [/The operation was cancell?ed\.?/g, "操作已取消"],
    [/\boperation timed out\b/g, "操作超时"],
    [/\bdial tcp\b/g, "TCP 连接"],
    [/\bdial udp\b/g, "UDP 连接"],
    [/\bread tcp\b/g, "TCP 读取"],
    [/\bwrite tcp\b/g, "TCP 写入"],
    [/\bclose tcp\b/g, "TCP 关闭"],
    [/\bread udp\b/g, "UDP 读取"],
    [/\bwrite udp\b/g, "UDP 写入"],
    [/\b(connectex|wsarecv|wsasend|wsarecvfrom|wsasendto): /g, ""],
    [/\bopen connection to\b/g, "连接"],
    [/\busing\b/g, "经由"],
]

function err(text) {
    var s = text
    for (var i = 0; i < errors.length; i++) s = s.replace(errors[i][0], errors[i][1])
    return s.replace(/outbound\/[\w-]+\[([^\]]+)\]/g, function (m, t) { return "出站·" + tag(t) })
}

var actions = {"sniff": "嗅探协议", "reject": "拦截", "hijack-dns": "接管 DNS", "resolve": "解析", "route-options": "设置路由选项"}

function action(a) {
    var r = /^route\(([^)]+)\)$/.exec(a)
    if (r) return "→ " + tag(r[1])
    return actions[a] || a
}

function condition(c) {
    return c.replace(/process_name=/g, "进程=").replace(/process_path=/g, "进程路径=")
            .replace(/rule_set=/g, "规则集=").replace(/domain_suffix=/g, "域名后缀=").replace(/domain_keyword=/g, "域名关键词=")
            .replace(/domain_regex=/g, "域名正则=").replace(/domain=/g, "域名=").replace(/ip_cidr=/g, "IP 段=")
            .replace(/ip_is_private/g, "私有 IP").replace(/protocol=/g, "协议=").replace(/inbound=/g, "入站=")
            .replace(/network=/g, "网络=").replace(/port=/g, "端口=").replace(/query_type=/g, "查询类型=")
}

var rcodes = {NOERROR: "成功", NXDOMAIN: "域名不存在", SERVFAIL: "服务器失败", REFUSED: "被拒绝", FORMERR: "格式错误"}

// [pattern, replacement function or string] over the message part.
var messages = [
    [/^(pre-)?match\[(\d+)\](?: (.+?))? => (.+)$/, function (m, pre, n, c, a) { return (pre ? "预匹配" : "命中") + "规则 #" + n + "：" + (c ? condition(c) + " " : "") + action(a) }],
    [/^sniffed (packet )?protocol: ([\w-]+)(?:, domain: ([^,]+))?(?:, client: (.+))?$/, function (m, pk, p, d, cl) {
        return "识别到" + (pk ? "数据包" : "") + "协议 " + p + (d ? "，域名 " + d : "") + (cl ? "，客户端 " + cl : "") }],
    [/^attempt to sniff fragmented QUIC client hello$/, "尝试识别分片的 QUIC 握手"],
    [/^found process path: (.+)$/, "进程：$1"],
    [/^failed to search process: (.+)$/, function (m, e) { return "查找进程失败：" + err(e) }],
    [/^inbound connection from (.+)$/, "新连接，来自 $1"],
    [/^inbound connection to (.+)$/, "目标 $1"],
    [/^inbound packet connection from (.+)$/, "新 UDP 连接，来自 $1"],
    [/^inbound packet connection to (.+)$/, "UDP 目标 $1"],
    [/^inbound DNS packet from (.+)$/, "DNS 请求，来自 $1"],
    [/^outbound connection to (.+)$/, "已连接 $1"],
    [/^outbound packet connection to (.+)$/, "已建立 UDP 连接 $1"],
    [/^connection (upload|download) finished$/, function (m, d) { return (d === "upload" ? "上传" : "下载") + "完成" }],
    [/^(connection|packet) (upload|download) closed(?:: (.+))?$/, function (m, k, d, e) {
        return (k === "packet" ? "UDP " : "") + (d === "upload" ? "上传" : "下载") + "已关闭" + (e ? "：" + err(e) : "") }],
    [/^connection closed: rejected$/, "连接已拦截"],
    [/^connection closed: (.+)$/, function (m, e) { return "连接关闭：" + err(e) }],
    [/^open connection to (\S+) using (\S+): (.+)$/, function (m, t, o, e) { return "经由" + module(o) + "连接 " + t + " 失败：" + err(e) }],
    [/^lookup domain (.+)$/, "查询域名 $1"],
    [/^lookup succeed for ([^:]+): (.+)$/, "$1 解析成功：$2"],
    [/^lookup failed for ([^:]+): (.+)$/, function (m, d, e) { return d + " 解析失败：" + err(e) }],
    [/^exchange (\S+?)\.? IN (\w+)$/, "查询 $1（$2）"],
    [/^(exchanged|cached) (\w+) (\S+?)\.? (\d+) IN \w+ (.+)$/, function (m, k, t, d, ttl, v) {
        return (k === "cached" ? "缓存：" : "解析：") + d + " " + t + " → " + v.replace(/\.$/, "") + "（TTL " + ttl + "）" }],
    [/^(exchanged|cached) (\S+?)\.? (NOERROR|NXDOMAIN|SERVFAIL|REFUSED|FORMERR) (\d+)$/, function (m, k, d, rc, ttl) {
        return (k === "cached" ? "缓存：" : "解析：") + d + " " + rcodes[rc] + "（TTL " + ttl + "）" }],
    [/^cached OPT .*$/, "缓存：EDNS 选项"],
    [/^(exchanged|cached) (SOA|OPT) .*$/, function (m, k, t) { return (k === "cached" ? "缓存：" : "解析：") + t + " 记录" }],
    [/^sing-box started \((.+)\)$/, function (m, d) { return "sing-box 已启动（耗时 " + duration(d) + "）" }],
    [/^sing-box pre-started \((.+)\)$/, function (m, d) { return "sing-box 预启动完成（耗时 " + duration(d) + "）" }],
    [/^start (\S+?)(?:\.\.\.| completed \((.+)\))$/, function (m, w, d) { return "启动" + module(w) + (d ? " 完成（耗时 " + duration(d) + "）" : "…") }],
    [/^creating stack$/, "正在创建网络栈"],
    [/^started at (.+)$/, "已启动：$1"],
    [/^outbound packet connection$/, "已建立 UDP 连接"],
    [/^(tcp|udp) server started at (.+)$/, function (m, p, a) { return p.toUpperCase() + " 服务已启动：" + a }],
    [/^updated default interface (\S+), index (\d+)$/, "默认网卡：$1（索引 $2）"],
    [/^updated network environment: gateway (\S+), gateway_mac (\S+)$/, "网络环境已更新：网关 $1，MAC $2"],
    [/^(.+) is unreferenced, closing idle connections$/, function (m, r) { return err(r).replace(/^dns\/[\w-]+\[([^\]]+)\]$/, function (x, t) { return tag(t) }) + " 暂未使用，关闭空闲连接" }],
    [/^router: closing$/, "正在关闭"],
]

function message(text) {
    for (var i = 0; i < messages.length; i++) {
        var m = messages[i]
        if (m[0].test(text)) return text.replace(m[0], m[1])
    }
    return err(text)
}

// 49ms → 49毫秒, 1m5.2s → 1分5.2秒
function duration(d) {
    return d.replace(/ms$/, "毫秒").replace(/µs$/, "微秒").replace(/ns$/, "纳秒").replace(/(\d)s$/, "$1秒").replace(/(\d)h/, "$1时").replace(/(\d)m(?!s)/, "$1分")
}

function translate(line) {
    // date time LEVEL[uptime] [conn duration] module: message
    var r = /^(\d{4}-\d\d-\d\d )?(\d\d:\d\d:\d\d )?([A-Z]+)\[\d+\] (?:\[(\d+) ([^\]]+)\] )?(.*)$/.exec(line)
    if (!r) return line
    var out = (r[1] || "") + (r[2] || "") + (levels[r[3]] || r[3]) + " "
    if (r[4]) out += "[" + r[4] + " " + duration(r[5]) + "] "
    var body = r[6]
    var mm = /^([\w\/-]+(?:\[[^\]]+\])?): (.*)$/.exec(body)
    if (mm && mm[1] !== "sing-box") return out + module(mm[1]) + "：" + message(mm[2])
    return out + message(body)
}
