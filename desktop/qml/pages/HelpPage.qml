import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// 设置 › 操作说明: what every control on the routes and settings pages does.
Item {
    id: root
    signal back()

    // One help entry: name, then what each choice (or on / off) does.
    component Help: ColumnLayout {
        property string name
        property string text
        spacing: 2
        Label { text: parent.name; color: Theme.fg; font.weight: Font.Medium; font.pixelSize: 13 }
        Label { text: parent.text; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
    }
    component HelpTitle: Label { color: Theme.fg3; font.pixelSize: 11; font.letterSpacing: 1; Layout.topMargin: 6 }

    Flickable {
        anchors.fill: parent
        contentHeight: col.implicitHeight + 36
        clip: true
        ColumnLayout {
            id: col
            width: Math.min(parent.width - 36, 640)
            x: 18; y: 12
            spacing: 10

            Label {
                text: "‹ 设置"
                color: backMa.containsMouse ? Theme.fg : Theme.fg2; font.pixelSize: 13
                MouseArea { id: backMa; anchors.fill: parent; anchors.margins: -6; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: root.back() }
            }
            Label { text: "操作说明"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; Layout.topMargin: 8; Layout.bottomMargin: 6 }


        HelpTitle { text: "顶栏" }
        Help { name: "开关"; text: "打开：按当前节点启动代理，并按下面的「模式」接管流量。关闭：停止代理，恢复系统原有网络设置。" }
        Help { name: "规则 / 全局 / 直连"; text: "规则：按路由页的名单和应用列表分流，没命中的走「未匹配流量」。全局：不看域名和 IP 名单，所有没被拦截、也没有应用规则的流量都走代理。直连：域名 / IP 名单、拦截和应用列表照常生效，只是没命中任何规则的流量改为直连，不走节点。" }
        Help { name: "系统代理 / TUN"; text: "系统代理：只把浏览器等会读取系统代理设置的程序接到本地端口，其他程序不受影响。TUN：建立虚拟网卡接管整台电脑的全部流量，游戏、命令行等不支持代理的程序也能走节点；需要管理员权限。" }

        HelpTitle { text: "路由页" }
        Help { name: "规则分流 / 应用分流"; text: "只是切换显示哪一部分，不影响实际生效。真正决定的是设置里的「分流方式」。" }
        Help { name: "走代理 · 域名 / IP"; text: "命中这些域名或 IP 段的流量走节点。一行一条，支持 geosite:、geoip:、full:、domain:、keyword:、regexp: 前缀和 CIDR。" }
        Help { name: "直连 · 域名 / IP"; text: "命中的流量不走节点，直接从本机出去。常用于国内网站、局域网。" }
        Help { name: "拦截 · 域名 / IP"; text: "命中的流量直接丢弃，对所有程序都有效，优先级最高。常用于广告和跟踪域名。" }
        Help { name: "应用分流列表"; text: "按程序决定去向：代理、直连或拦截，不看它访问的是什么网站。系统代理模式下只对走系统代理的程序有效，TUN 模式下对所有程序有效。" }
        Help { name: "未匹配流量"; text: "没有命中任何规则和应用列表的流量怎么走。代理：走节点。直连：不走节点。只想让个别程序走节点时，把它设为直连，再在应用列表里加上那几个程序。" }
        Help { name: "远程 DNS / 直连 DNS"; text: "远程 DNS 经代理解析，用于走代理的域名；直连 DNS 在本地解析，用于直连域名和节点服务器地址。" }
        Help { name: "DNS 分流"; text: "开启：按域名名单选择用哪个 DNS，减少 DNS 泄漏。关闭：不再按名单分流，除节点服务器地址外都用远程 DNS 解析。" }
        Help { name: "导出 JSON"; text: "把当前节点和设置生成的内核配置复制到剪贴板，用于排查问题或在别处使用。" }

        HelpTitle { text: "设置页" }
        Help { name: "布局"; text: "横屏：左侧导航加右侧详情，适合桌面。竖屏：窄窗口，适合贴边放置。Ctrl+L 快速切换。" }
        Help { name: "主题"; text: "浅色、深色、跟随系统，或深浅组合（浅色内容配深色导航栏）。" }
        Help { name: "开机自启"; text: "开启：登录 Windows 后自动启动并最小化到托盘。关闭：需要手动打开。" }
        Help { name: "模式"; text: "与顶栏的「系统代理 / TUN」是同一个设置。" }
        Help { name: "分流方式"; text: "规则分流：只用域名和 IP 名单，忽略应用列表。应用分流：只用应用列表，忽略名单。同时生效：先拦截名单，再应用列表，再代理 / 直连名单，最后未匹配流量。" }
        Help { name: "本地端口"; text: "本机监听的代理端口，socks 与 http 共用。系统代理模式下浏览器连的就是它。" }
        Help { name: "允许局域网"; text: "开启：监听 0.0.0.0，同一局域网的其他设备可以把代理指向本机。关闭：只有本机能用。" }
        Help { name: "测试设置"; text: "各种测速用的目标网址、服务器、次数和超时。" }
        Help { name: "协议栈"; text: "TUN 的实现方式。gvisor 兼容性最好；system 性能更高；mixed 两者折中。遇到问题先换回 gvisor。" }
        Help { name: "严格路由 / Kill Switch"; text: "开启：TUN 运行期间所有流量必须经过 TUN，程序无法绕过代理，Windows 下还会加防火墙规则防 DNS 泄漏。关闭：允许流量绕过 TUN。仅 TUN 模式有效，核心停止后不再拦截。" }
        Help { name: "FakeIP"; text: "开启：TUN 下用假 IP 加速 DNS，上网更快但少数程序不兼容。关闭：正常解析。" }
        Help { name: "IPv6"; text: "开启：TUN 同时接管 IPv6 流量。关闭：只处理 IPv4，IPv6 按系统原样。" }
        Help { name: "重启核心"; text: "重新启动后台核心进程，连接会断开一次。核心异常时使用。" }
        }
    }
}
