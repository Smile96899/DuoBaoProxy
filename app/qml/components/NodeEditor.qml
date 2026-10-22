import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Edit one node: a form with the fields its protocol uses, or the full JSON.
Popup {
    id: root
    property int nodeId: -1
    property var draft: ({})            // working copy of entry.node
    property string group: ""
    property string mode: "form"        // form | json
    property string error: ""

    modal: true
    anchors.centerIn: Overlay.overlay
    width: Math.min(560, Overlay.overlay.width - 32)
    height: Math.min(640, Overlay.overlay.height - 32)
    padding: 24
    background: Rectangle { color: Theme.panel; radius: 28; border.width: 1; border.color: Theme.line }

    // Protocols a node can be created with by hand, and their usual ports.
    readonly property var protocols: [
        {v: "socks", l: "SOCKS5", port: 1080}, {v: "http", l: "HTTP", port: 8080}, {v: "shadowsocks", l: "Shadowsocks", port: 8388},
        {v: "vmess", l: "VMess", port: 443}, {v: "vless", l: "VLESS", port: 443}, {v: "trojan", l: "Trojan", port: 443},
        {v: "hysteria2", l: "Hysteria2", port: 443}, {v: "tuic", l: "TUIC", port: 443}, {v: "ssh", l: "SSH", port: 22}, {v: "anytls", l: "AnyTLS", port: 443},
        {v: "wireguard", l: "WireGuard", port: 51820}, {v: "hysteria", l: "Hysteria (v1)", port: 443}, {v: "shadowtls", l: "ShadowTLS", port: 443}
    ]
    function openNew(protocol) {
        var p = protocols.find(x => x.v === protocol) || {port: 443}
        nodeId = -1
        group = App.defaultGroup
        mode = "form"; error = ""
        draft = {protocol: protocol, server: "", port: p.port}
        if (protocol === "trojan" || protocol === "anytls") draft.stream = {sec: "tls"}
        if (protocol === "shadowsocks" || protocol === "shadowtls") draft.method = "aes-256-gcm"
        if (protocol === "shadowtls") draft.shadowtls = {version: 3}
        if (protocol === "wireguard") draft.wireguard = {local_address: "10.0.0.2/32"}
        open()
    }

    function openFor(id) {
        var e = store.node(id)
        if (!e.node) return
        nodeId = id
        group = e.group || App.defaultGroup
        mode = "form"; error = ""
        draft = JSON.parse(JSON.stringify(e.node))
        open()
    }

    // ---- nested access by path, e.g. ["stream", "sni"] ----------------------
    function getAt(obj, path) {
        for (var i = 0; i < path.length; i++) { if (obj === undefined || obj === null) return undefined; obj = obj[path[i]] }
        return obj
    }
    function setAt(obj, path, v) {
        for (var i = 0; i < path.length - 1; i++) {
            if (typeof obj[path[i]] !== "object" || obj[path[i]] === null) obj[path[i]] = {}
            obj = obj[path[i]]
        }
        var k = path[path.length - 1]
        if (v === "" || v === false || v === undefined) delete obj[k]; else obj[k] = v
    }

    // ---- which fields each protocol shows ------------------------------------
    readonly property var tlsFields: [
        {l: "传输方式", p: ["stream", "net"], t: "choice", c: ["tcp", "ws", "grpc", "http", "httpupgrade", "xhttp"], d: "tcp"},
        {l: "TLS", p: ["stream", "sec"], t: "choice", c: ["", "tls"], cl: ["关闭", "开启"]},
        {l: "SNI", p: ["stream", "sni"], t: "text"},
        {l: "Host", p: ["stream", "host"], t: "text"},
        {l: "路径", p: ["stream", "path"], t: "text"},
        {l: "ALPN", p: ["stream", "alpn"], t: "text", h: "逗号分隔，如 h2,http/1.1"},
        {l: "指纹", p: ["stream", "utls"], t: "text", h: "chrome、firefox、safari…"},
        {l: "Reality 公钥", p: ["stream", "pbk"], t: "text"},
        {l: "Reality ShortID", p: ["stream", "sid"], t: "text"},
        {l: "跳过证书验证", p: ["stream", "insecure"], t: "bool"}
    ]
    readonly property var quicFields: [
        {l: "SNI", p: ["quic_tls", "sni"], t: "text"},
        {l: "ALPN", p: ["quic_tls", "alpn"], t: "text"},
        {l: "跳过证书验证", p: ["quic_tls", "insecure"], t: "bool"}
    ]
    readonly property var specs: {
        var p = draft.protocol || ""
        var base = [
            {l: "名称", p: ["name"], t: "text", h: "留空则显示地址"},
            {l: "地址", p: ["server"], t: "text"},
            {l: "端口", p: ["port"], t: "int"}
        ]
        var auth = {
            "socks": [{l: "用户名", p: ["username"], t: "text"}, {l: "密码", p: ["password"], t: "text"}],
            "http": [{l: "用户名", p: ["username"], t: "text"}, {l: "密码", p: ["password"], t: "text"}],
            "shadowsocks": [{l: "加密方式", p: ["method"], t: "text", h: "如 aes-256-gcm、2022-blake3-aes-128-gcm"}, {l: "密码", p: ["password"], t: "text"}, {l: "插件", p: ["plugin"], t: "text"}],
            "vmess": [{l: "UUID", p: ["uuid"], t: "text"}, {l: "AlterID", p: ["alter_id"], t: "int"}, {l: "加密", p: ["vmess_security"], t: "text", h: "auto"}],
            "vless": [{l: "UUID", p: ["uuid"], t: "text"}, {l: "Flow", p: ["flow"], t: "text", h: "xtls-rprx-vision"}],
            "trojan": [{l: "密码", p: ["password"], t: "text"}],
            "hysteria2": [{l: "密码", p: ["password"], t: "text"}, {l: "混淆密码", p: ["hysteria2", "obfs_password"], t: "text"},
                          {l: "上行 Mbps", p: ["hysteria2", "up_mbps"], t: "int"}, {l: "下行 Mbps", p: ["hysteria2", "down_mbps"], t: "int"},
                          {l: "端口跳跃", p: ["hysteria2", "hop_ports"], t: "text", h: "如 20000-30000"}],
            "tuic": [{l: "UUID", p: ["uuid"], t: "text"}, {l: "密码", p: ["password"], t: "text"}, {l: "拥塞控制", p: ["tuic", "congestion_control"], t: "text", h: "bbr、cubic"}],
            "naive": [{l: "用户名", p: ["username"], t: "text"}, {l: "密码", p: ["password"], t: "text"}, {l: "SNI", p: ["naive", "sni"], t: "text"}],
            "ssh": [{l: "用户名", p: ["username"], t: "text"}, {l: "密码", p: ["password"], t: "text"}],
            "anytls": [{l: "密码", p: ["password"], t: "text"}],
            "wireguard": [{l: "本机私钥", p: ["wireguard", "private_key"], t: "text"}, {l: "对端公钥", p: ["wireguard", "peer_public_key"], t: "text"},
                          {l: "预共享密钥", p: ["wireguard", "pre_shared_key"], t: "text"}, {l: "本机地址", p: ["wireguard", "local_address"], t: "text", h: "10.0.0.2/32,fd00::2/128"},
                          {l: "Reserved", p: ["wireguard", "reserved"], t: "text", h: "WARP 用，如 1,2,3"}, {l: "MTU", p: ["wireguard", "mtu"], t: "int"}],
            "hysteria": [{l: "认证字符串", p: ["hysteria", "auth"], t: "text"}, {l: "上行 Mbps", p: ["hysteria", "up_mbps"], t: "int"}, {l: "下行 Mbps", p: ["hysteria", "down_mbps"], t: "int"},
                         {l: "混淆密码", p: ["hysteria", "obfs"], t: "text"}],
            "shadowtls": [{l: "ShadowTLS 密码", p: ["password"], t: "text"}, {l: "版本", p: ["shadowtls", "version"], t: "int", h: "3"},
                          {l: "握手 SNI", p: ["stream", "sni"], t: "text", h: "如 www.microsoft.com"}, {l: "指纹", p: ["stream", "utls"], t: "text", h: "chrome"},
                          {l: "SS 加密方式", p: ["method"], t: "text"}, {l: "SS 密码", p: ["shadowtls", "ss_password"], t: "text"}]
        }[p] || []
        var extra = (p === "hysteria2" || p === "tuic" || p === "hysteria") ? quicFields
                  : (p === "shadowtls" || p === "wireguard" || p === "ssh") ? []
                  : (["vmess", "vless", "trojan", "anytls"].indexOf(p) >= 0 || draft.stream) ? tlsFields : []
        return base.concat(auth, extra)
    }

    function toJson() { jsonArea.text = JSON.stringify(draft, null, 2) }
    function fromJson() {
        try { draft = JSON.parse(jsonArea.text); error = ""; return true }
        catch (e) { error = "JSON 格式错误：" + e.message; return false }
    }
    function switchMode(m) {
        if (m === mode) return
        if (m === "json") { toJson(); mode = m }
        else if (fromJson()) mode = m
    }
    function save() {
        if (mode === "json" && !fromJson()) return
        if (!String(draft.server || "").trim().length) { error = "地址不能为空"; return }
        var port = Number(draft.port)
        if (!(port >= 1 && port <= 65535)) { error = "端口要在 1–65535 之间"; return }
        draft.port = port
        if (nodeId < 0) {
            var id = store.addNode(JSON.stringify(draft), group)
            if (id < 0) { error = "节点数据无效"; return }
            App.log("已添加节点 " + App.nodeName(draft))
        } else {
            store.updateNode(nodeId, {node: draft, group: group, lat: 0, res: {}})
            App.log("已保存节点 " + App.nodeName(draft))
            if (App.connected && App.selectedId === nodeId) App.reconnect()
        }
        close()
    }

    contentItem: ColumnLayout {
        spacing: 14
        RowLayout {
            Layout.fillWidth: true
            Label { text: root.nodeId < 0 ? "添加节点" : "编辑节点"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Rectangle { radius: 4; color: Theme.panel2; height: 18; Layout.preferredWidth: protoLbl.implicitWidth + 12
                Label { id: protoLbl; anchors.centerIn: parent; text: root.draft.protocol || ""; color: Theme.fg2; font.pixelSize: 11; font.family: Theme.mono } }
            Item { Layout.fillWidth: true }
            Seg { model: [{v: "form", l: "常用"}, {v: "json", l: "JSON"}]; current: root.mode; onPicked: v => root.switchMode(v) }
        }

        // ---- form ----
        Flickable {
            visible: root.mode === "form"
            Layout.fillWidth: true; Layout.fillHeight: true
            contentHeight: form.implicitHeight
            clip: true
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
            ColumnLayout {
                id: form
                width: parent.width
                spacing: 8
                RowLayout {
                    Layout.fillWidth: true; spacing: 12
                    Label { text: "分组"; color: Theme.fg2; font.pixelSize: 13; Layout.preferredWidth: 110 }
                    Dropdown { model: store.groups.map(g => ({v: g.name, l: g.name})); current: root.group; onPicked: v => root.group = v; Layout.fillWidth: true }
                }
                Repeater {
                    model: root.specs
                    RowLayout {
                        id: fieldRow
                        Layout.fillWidth: true; spacing: 12
                        readonly property var value: root.getAt(root.draft, modelData.p)
                        Label { text: modelData.l; color: Theme.fg2; font.pixelSize: 13; Layout.preferredWidth: 110; elide: Text.ElideRight }
                        Input {
                            visible: modelData.t === "text" || modelData.t === "int"
                            Layout.fillWidth: true
                            text: fieldRow.value === undefined || fieldRow.value === null ? "" : String(fieldRow.value)
                            placeholderText: modelData.h || ""
                            validator: modelData.t === "int" ? intOnly : null
                            font.family: modelData.t === "int" ? Theme.mono : Theme.font
                            onTextEdited: root.setAt(root.draft, modelData.p, modelData.t === "int" ? (text === "" ? "" : Number(text)) : text)
                        }
                        Item {
                            visible: modelData.t === "bool"
                            Layout.fillWidth: true; implicitHeight: 32
                            Toggle {
                                property bool val: !!fieldRow.value
                                anchors.verticalCenter: parent.verticalCenter
                                on: val
                                onClicked: { val = !val; root.setAt(root.draft, modelData.p, val) }
                            }
                        }
                        Dropdown {
                            visible: modelData.t === "choice"
                            Layout.fillWidth: true
                            model: (modelData.c || []).map((c, i) => ({v: c, l: modelData.cl ? modelData.cl[i] : c}))
                            current: fieldRow.value === undefined ? (modelData.d || "") : fieldRow.value
                            onPicked: v => { current = v; root.setAt(root.draft, modelData.p, v) }
                        }
                    }
                }
            }
        }
        IntValidator { id: intOnly; bottom: 0; top: 65535 }

        // ---- raw JSON ----
        Rectangle {
            visible: root.mode === "json"
            Layout.fillWidth: true; Layout.fillHeight: true
            radius: 16; color: Theme.bg; border.width: 1; border.color: Theme.line
            ScrollView {
                anchors.fill: parent; anchors.margins: 10
                TextArea { id: jsonArea; color: Theme.fg; placeholderTextColor: Theme.fg3; selectionColor: Theme.accent; selectedTextColor: Theme.accentFg; selectByMouse: true; font.family: Theme.mono; font.pixelSize: 12; background: null; wrapMode: TextEdit.WrapAnywhere }
            }
        }

        Label { visible: root.error !== ""; text: root.error; color: Theme.bad; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
        RowLayout {
            Layout.fillWidth: true
            Item { Layout.fillWidth: true }
            PillButton { text: "取消"; flat: true; onClicked: root.close() }
            PillButton { text: "保存"; highlighted: true; onClicked: root.save() }
        }
    }
}
