pragma Singleton
import QtQuick

// Colour and type tokens. Grey-only UI; colour is reserved for connection
// state (accent), latency (good/warn/bad) and errors.
QtObject {
    id: theme
    property string mode: "system"          // system | light | dark
    readonly property bool dark: mode === "dark" || (mode === "system" && Qt.styleHints.colorScheme === Qt.Dark)

    readonly property color bg:        dark ? "#111317" : "#f3f4f6"
    readonly property color panel:     dark ? "#1a1d23" : "#ffffff"
    readonly property color panel2:    dark ? "#22262e" : "#e9ebef"
    readonly property color line:      dark ? "#2c313b" : "#dcdfe5"
    readonly property color fg:        dark ? "#e8eaee" : "#15181d"
    readonly property color fg2:       dark ? "#a0a7b4" : "#5b6270"
    readonly property color fg3:       dark ? "#6c7482" : "#8a909c"
    readonly property color accent:    dark ? "#34b27a" : "#1f8f5f"
    readonly property color accentSoft:dark ? "#17352a" : "#dcf2e7"
    readonly property color accentFg:  dark ? "#0b1a13" : "#ffffff"
    readonly property color good:      accent
    readonly property color warn:      dark ? "#d9a420" : "#c48a08"
    readonly property color bad:       dark ? "#e0605a" : "#c9433d"

    readonly property string font: Qt.platform.os === "windows" ? "Microsoft YaHei UI" : (Qt.platform.os === "osx" ? "PingFang SC" : "Noto Sans CJK SC")
    readonly property string mono: Qt.platform.os === "windows" ? "Cascadia Mono" : "Menlo"
    readonly property int radius: 8

    function latColor(ms) {
        if (ms === undefined || ms === null || ms === 0) return fg3
        if (ms < 0) return bad
        if (ms < 80) return good
        if (ms < 150) return warn
        return bad
    }
    function latText(ms) {
        if (ms === undefined || ms === null || ms === 0) return "未测"
        if (ms < 0) return "超时"
        return ms + " ms"
    }
    function bytes(n) {
        if (n < 1024) return n + " B"
        if (n < 1048576) return (n / 1024).toFixed(1) + " KB"
        if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB"
        return (n / 1073741824).toFixed(2) + " GB"
    }
}
