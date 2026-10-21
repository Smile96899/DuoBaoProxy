pragma Singleton
import QtQuick

// Node tests: what each kind measures, the queue that runs them, and how the
// results read. A result lives on the node entry as res[kind]: {ms} for
// latencies, {bps} for speeds, {err} on failure, or the probe's own report.
QtObject {
    id: t

    // k: id, l: menu label, s: column title, w: column width, sec: menu section, d: what it does
    readonly property var kinds: [
        {k: "url",   l: "真连接延迟",     s: "真连接", w: 64, sec: 0, d: "通过节点访问一个网址，计算往返时间"},
        {k: "tcp",   l: "TCP 延迟",       s: "TCP",    w: 60, sec: 0, d: "直接连接节点服务器的端口，不经过代理"},
        {k: "icmp",  l: "ICMP Ping",      s: "ICMP",   w: 60, sec: 0, d: "直接 ping 节点服务器；很多服务器禁 ping"},
        {k: "udp",   l: "UDP 延迟",       s: "UDP",    w: 60, sec: 0, d: "通过节点发一次 DNS 查询，也能看出是否支持 UDP"},
        {k: "loss",  l: "丢包 / 抖动",    s: "丢包",   w: 92, sec: 0, d: "连续多次 TCP 连接，统计平均、抖动和丢包"},
        {k: "down",  l: "下载速度",       s: "下载",   w: 76, sec: 1, d: "通过节点下载测速文件"},
        {k: "up",    l: "上传速度",       s: "上传",   w: 76, sec: 1, d: "通过节点上传测速数据"},
        {k: "geo",   l: "出口 IP 与地区", s: "地区",   w: 76, sec: 1, d: "通过节点查询对方看到的 IP 和所在地区"},
        {k: "media", l: "流媒体解锁",     s: "流媒体", w: 96, sec: 2, d: "Netflix、Disney+、YouTube Premium 能否使用"},
        {k: "ai",    l: "AI 服务可用性",  s: "AI",     w: 96, sec: 2, d: "ChatGPT、Claude、Gemini 能否访问"},
        {k: "dns",   l: "DNS 泄漏检测",   s: "DNS",    w: 60, sec: 2, d: "检查 DNS 查询有没有绕过代理（使用 bash.ws）"}
    ]

    // ---- per-test settings (store.ui.tests[kind][field]) ------------------------
    readonly property var mediaNames: [["netflix", "Netflix", "N"], ["disney", "Disney+", "D"], ["youtube", "YouTube Premium", "Y"]]
    readonly property var aiNames: [["chatgpt", "ChatGPT", "GPT"], ["claude", "Claude", "Cl"], ["gemini", "Gemini", "Ge"]]
    readonly property var defaults: ({
        url:   {url: "http://cp.cloudflare.com/", timeout: 8},
        tcp:   {timeout: 5},
        icmp:  {timeout: 3},
        udp:   {server: "8.8.8.8:53", domain: "www.google.com", timeout: 5},
        loss:  {count: 10, timeout: 2},
        down:  {url: "https://speed.cloudflare.com/__down?bytes=25000000", timeout: 15},
        up:    {url: "https://speed.cloudflare.com/__up", size: 10, timeout: 15},
        geo:   {url: "https://www.cloudflare.com/cdn-cgi/trace", timeout: 8},
        media: {services: ["netflix", "disney", "youtube"], timeout: 15},
        ai:    {services: ["chatgpt", "claude", "gemini"], timeout: 15},
        dns:   {timeout: 25}
    })
    // What the settings page edits for each kind. t: text | int | services
    readonly property var fields: ({
        url:   [{f: "url", l: "测试网址", t: "text", h: "返回 2xx / 3xx 即视为成功"}, {f: "timeout", l: "超时（秒）", t: "int"}],
        tcp:   [{f: "timeout", l: "超时（秒）", t: "int"}],
        icmp:  [{f: "timeout", l: "超时（秒）", t: "int"}],
        udp:   [{f: "server", l: "DNS 服务器", t: "text", h: "IP:端口"}, {f: "domain", l: "查询域名", t: "text"}, {f: "timeout", l: "超时（秒）", t: "int"}],
        loss:  [{f: "count", l: "次数", t: "int"}, {f: "timeout", l: "每次超时（秒）", t: "int"}],
        down:  [{f: "url", l: "下载网址", t: "text", h: "文件越大测得越准"}, {f: "timeout", l: "最长时间（秒）", t: "int"}],
        up:    [{f: "url", l: "上传网址", t: "text", h: "需接受 POST 上传"}, {f: "size", l: "上传大小（MB）", t: "int"}, {f: "timeout", l: "最长时间（秒）", t: "int"}],
        geo:   [{f: "url", l: "查询网址", t: "text", h: "需返回 Cloudflare trace 格式（ip= / loc=）"}, {f: "timeout", l: "超时（秒）", t: "int"}],
        media: [{f: "services", l: "检测项目", t: "services", c: mediaNames}, {f: "timeout", l: "超时（秒）", t: "int"}],
        ai:    [{f: "services", l: "检测项目", t: "services", c: aiNames}, {f: "timeout", l: "超时（秒）", t: "int"}],
        dns:   [{f: "timeout", l: "超时（秒）", t: "int"}]
    })
    function opt(k, f) {
        var o = (store.ui.tests || {})[k] || {}
        var v = o[f]
        if (v !== undefined && v !== "" && !(Array.isArray(v) && !v.length)) return v
        if (k === "url" && f === "url" && store.ui.test_url) return store.ui.test_url   // from the old 测速地址 setting
        return defaults[k][f]
    }
    function setOpt(k, f, v) {
        var all = Object.assign({}, store.ui.tests || {})
        var o = Object.assign({}, all[k] || {})
        o[f] = v
        all[k] = o
        store.setUi("tests", all)
    }
    function resetOpts(k) {
        var all = Object.assign({}, store.ui.tests || {})
        delete all[k]
        store.setUi("tests", all)
        if (k === "url") store.setUi("test_url", "")
    }
    function ms(k) { return Math.max(1, Number(opt(k, "timeout")) || defaults[k].timeout) * 1000 }
    readonly property var allKinds: kinds.map(x => x.k)
    function kind(k) { return kinds.find(x => x.k === k) || {k: k, l: k, s: k, w: 64} }
    readonly property var latencyKinds: ["url", "tcp", "icmp", "udp", "loss"]

    // ---- queue -------------------------------------------------------------
    readonly property int maxActive: 6
    property var queue: []              // [{id, k}]
    property var running: ({})          // "id:kind" -> true while queued or running
    property int active: 0
    property bool speedBusy: false      // speed tests run one at a time
    property var pending: ({})          // request id -> job

    // Result columns the node list shows: the kinds of the last test the user
    // ran. Empty at launch, so the list shows no results until something is tested.
    property var shownKinds: []

    function isRunning(id, k) { return !!running[id + ":" + k] }
    function isSpeed(k) { return k === "down" || k === "up" }

    // quiet: run in the background without changing what the list shows.
    function run(ids, ks, quiet) {
        if (!quiet) shownKinds = ks.slice()
        var r = Object.assign({}, running)
        ids.forEach(id => {
            var e = store.node(id)
            if (!e.node || !App.supports(e.node)) return
            ks.forEach(k => {
                var key = id + ":" + k
                if (r[key]) return
                r[key] = true
                queue.push({id: id, k: k})
            })
        })
        running = r
        pump()
    }
    function pump() {
        while (active < maxActive && queue.length) {
            var i = queue.findIndex(j => !isSpeed(j.k) || !speedBusy)
            if (i < 0) return
            var job = queue.splice(i, 1)[0]
            active++
            if (isSpeed(job.k)) speedBusy = true
            start(job)
        }
    }
    function finish(job, result) {
        active--
        if (isSpeed(job.k)) speedBusy = false
        var r = Object.assign({}, running); delete r[job.id + ":" + job.k]; running = r
        save(job.id, job.k, result)
        pump()
    }
    function save(id, k, result) {
        var e = store.node(id)
        if (!e.node) return
        var res = Object.assign({}, e.res || {})
        res[k] = result
        var patch = {res: res}
        if (k === "url") patch.lat = result.ms ? result.ms : -1     // the tray and older code read lat
        store.updateNode(id, patch)
        if (k === "url" && id === App.selectedId) App.latency = patch.lat
    }

    function hostPort(n) { return (n.server.indexOf(":") >= 0 ? "[" + n.server + "]" : n.server) + ":" + n.port }
    readonly property var probeName: ({udp: "udp", down: "download", up: "upload", geo: "geo", media: "media", ai: "ai", dns: "dnsleak"})
    // Targets travel in the probe's address / url / count fields.
    function probeArgs(k) {
        switch (k) {
        case "udp":   return {address: opt("udp", "server"), url: opt("udp", "domain"), count: 0}
        case "down":  return {address: "", url: opt("down", "url"), count: 0}
        case "up":    return {address: "", url: opt("up", "url"), count: Number(opt("up", "size")) || 10}
        case "geo":   return {address: "", url: opt("geo", "url"), count: 0}
        case "media": case "ai": return {address: "", url: opt(k, "services").join(","), count: 0}
        }
        return {address: "", url: "", count: 0}
    }

    function start(job) {
        var n = store.node(job.id).node
        var req = App.nextReq++
        pending[req] = job
        switch (job.k) {
        case "tcp":  core.tcpPing(req, hostPort(n), ms("tcp")); return
        case "icmp": core.probe(req, "icmp", "", "", n.server, "", 0, ms("icmp")); return
        case "loss": core.probe(req, "loss", "", "", hostPort(n), "", Number(opt("loss", "count")) || 10, ms("loss")); return
        }
        // The rest go through the node: build a throw-away config first.
        job.render = true
        core.renderConfig(req, App.coreId, [store.nodeJson(job.id)], JSON.stringify(App.renderSettings()), 1)
    }
    function failure(error) { return {err: /timeout|deadline|i\/o timeout/i.test(error) ? "超时" : "失败", detail: error} }

    property Connections coreConn: Connections {
        target: core
        function onRendered(req, config, tags, error) {
            var job = t.pending[req]
            if (!job || !job.render) return
            delete t.pending[req]
            if (error !== "") { t.finish(job, {err: "失败", detail: error}); return }
            var req2 = App.nextReq++
            t.pending[req2] = job
            if (job.k === "url") { core.urlTest(req2, App.coreId, config, t.opt("url", "url"), t.ms("url")); return }
            var a = t.probeArgs(job.k)
            core.probe(req2, t.probeName[job.k], App.coreId, config, a.address, a.url, a.count, t.ms(job.k))
        }
        function onTested(req, ms, error) {
            var job = t.pending[req]
            if (!job) return
            delete t.pending[req]
            t.finish(job, error !== "" ? t.failure(error) : {ms: Math.max(1, ms)})
        }
        function onProbed(req, ms, report, error) {
            var job = t.pending[req]
            if (!job) return
            delete t.pending[req]
            if (error !== "") { t.finish(job, t.failure(error)); return }
            var r = {}
            try { r = report ? JSON.parse(report) : {} } catch (e) { r = {} }
            if (job.k === "icmp" || job.k === "udp") r = {ms: Math.max(1, ms)}
            if (job.k === "loss" && r.loss >= 1) r.err = "超时"
            t.finish(job, r)
        }
    }

    // ---- reading results ------------------------------------------------------
    // Older nodes only have lat from before results were kept per kind.
    function result(entry, k) {
        var r = entry && entry.res ? entry.res[k] : undefined
        if (!r && k === "url" && entry && entry.lat) r = entry.lat > 0 ? {ms: entry.lat} : {err: "超时"}
        return r
    }
    function speed(bps) {
        if (!bps) return "—"
        if (bps >= 1048576) return (bps / 1048576).toFixed(1) + " MB/s"
        return Math.round(bps / 1024) + " KB/s"
    }
    readonly property var countryNames: ({
        CN: "中国", HK: "香港", TW: "台湾", MO: "澳门", JP: "日本", KR: "韩国", SG: "新加坡", MY: "马来西亚", TH: "泰国",
        VN: "越南", PH: "菲律宾", ID: "印尼", IN: "印度", AU: "澳大利亚", NZ: "新西兰", US: "美国", CA: "加拿大", MX: "墨西哥",
        BR: "巴西", AR: "阿根廷", GB: "英国", DE: "德国", FR: "法国", NL: "荷兰", IT: "意大利", ES: "西班牙", CH: "瑞士",
        SE: "瑞典", NO: "挪威", FI: "芬兰", DK: "丹麦", PL: "波兰", RU: "俄罗斯", UA: "乌克兰", TR: "土耳其", AE: "阿联酋",
        IL: "以色列", SA: "沙特", ZA: "南非", EG: "埃及", IE: "爱尔兰", AT: "奥地利", BE: "比利时", PT: "葡萄牙", CZ: "捷克",
        RO: "罗马尼亚", HU: "匈牙利", GR: "希腊", KZ: "哈萨克斯坦", LU: "卢森堡", IS: "冰岛", CL: "智利", CO: "哥伦比亚"
    })
    function country(cc) { return cc ? (countryNames[cc] || cc) : "" }
    function mark(s) { return s ? (s.ok ? "✓" : "✗") : "?" }
    // Short text for a list column: {text, color}
    function cell(entry, k) {
        var r = result(entry, k)
        if (!r) return null
        if (r.err) return {text: r.err, color: Theme.bad}
        switch (k) {
        case "url": case "tcp": case "icmp": case "udp":
            return {text: r.ms + " ms", color: Theme.latColor(r.ms)}
        case "loss":
            return {text: r.avg + "ms · " + Math.round(r.loss * 100) + "%", color: r.loss > 0.2 ? Theme.bad : (r.loss > 0 ? Theme.warn : Theme.latColor(r.avg))}
        case "down": case "up":
            return {text: speed(r.bps), color: Theme.fg}
        case "geo":
            return {text: r.cc ? r.cc + " " + country(r.cc) : "—", color: Theme.fg}
        case "media": case "ai":
            var names = (k === "media" ? mediaNames : aiNames).filter(n => r[n[0]])
            var okCount = names.filter(n => r[n[0]] && r[n[0]].ok).length
            return {text: names.map(n => n[2] + mark(r[n[0]])).join(" "), color: okCount === names.length ? Theme.good : (okCount ? Theme.warn : Theme.bad)}
        case "dns":
            return {text: r.leak ? "泄漏" : "无泄漏", color: r.leak ? Theme.bad : Theme.good}
        }
        return null
    }
    // Full text for the detail column.
    function detail(entry, k) {
        var r = result(entry, k)
        if (!r) return ""
        if (r.err) return r.err + (r.detail ? "（" + r.detail.split("\n")[0].slice(0, 80) + "）" : "")
        switch (k) {
        case "url": case "tcp": case "icmp": case "udp":
            return r.ms + " ms"
        case "loss":
            return "平均 " + r.avg + " ms · 最小 " + r.min + " · 最大 " + r.max + " · 抖动 " + r.jitter + " ms · 丢包 " + Math.round(r.loss * 100) + "%"
        case "down": case "up":
            return speed(r.bps)
        case "geo":
            return (r.cc ? r.cc + " " + country(r.cc) + " · " : "") + (r.ip || "")
        case "media": case "ai":
            var names = (k === "media" ? mediaNames : aiNames).filter(n => r[n[0]])
            return names.map(n => {
                var s = r[n[0]]
                if (!s) return n[1] + " ?"
                var where = s.region ? " " + country(s.region) : ""
                return n[1] + " " + mark(s) + where + (s.note ? "（" + s.note + "）" : "")
            }).join("\n")
        case "dns":
            // Group servers by country and operator so 20 Google addresses read as one line.
            var seen = {}
            ;(r.servers || []).forEach(s => {
                var key = country(s.cc) + " · " + (s.asn || "").replace(/^AS\d+\s*/, "")
                seen[key] = (seen[key] || 0) + 1
            })
            var lines = Object.keys(seen).map(k => k + "（" + seen[k] + " 个）")
            return (r.leak ? "可能泄漏：本地网络所在地（" + country(r.local) + "）的 DNS 收到了查询" : "无泄漏" + (r.local ? "（本地网络在" + country(r.local) + "）" : ""))
                + (lines.length ? "\nDNS 服务器：\n" + lines.join("\n") : "")
        }
        return ""
    }
    // Number used to sort by this kind; failures and untested sort last.
    function sortValue(entry, k) {
        var r = result(entry, k)
        if (!r || r.err) return Infinity
        if (k === "down" || k === "up") return -(r.bps || 0)
        if (k === "loss") return r.loss * 100000 + r.avg
        return r.ms || Infinity
    }
}
