import QtQuick
import QtQuick.Controls
import QtQuick.Window
import "components"

ApplicationWindow {
    id: win
    readonly property bool landscape: (store.ui.layout || "landscape") === "landscape"
    title: sys.platformName
    visible: !startInTray
    color: Theme.panel
    // Every launch, and every layout switch, starts at the layout's minimum size.
    readonly property int minW: landscape ? 900 : 360
    readonly property int minH: landscape ? 560 : 640
    minimumWidth: minW
    minimumHeight: minH
    width: minW
    height: minH
    font.family: Theme.font
    font.pixelSize: 14

    Component.onCompleted: { Theme.mode = store.ui.theme || "system"; sys.setDarkTitleBar(win, Theme.dark) }
    // The title bar follows the theme too, including "follow system" changes.
    Connections { target: Theme; function onDarkChanged() { sys.setDarkTitleBar(win, Theme.dark) } }

    // Keep the chosen layout across restarts; resize when the user switches.
    // Set the minimum first so a smaller layout is not held at the old one.
    onLandscapeChanged: {
        minimumWidth = minW; minimumHeight = minH
        width = minW; height = minH
    }

    Loader {
        anchors.fill: parent
        sourceComponent: win.landscape ? landscapeComp : portraitComp
    }
    Component { id: landscapeComp; Landscape {} }
    QrDialog { id: qrDialog; items: App.qrItems }
    Connections { target: App; function onQrItemsChanged() { if (App.qrItems.length) qrDialog.open() } }
    Component { id: portraitComp; Portrait {} }

    // Closing hides to the tray; "退出" in the tray menu really quits.
    onClosing: function(close) { close.accepted = false; win.hide() }
    Connections {
        target: sys
        function onTrayShowRequested() { win.show(); win.raise(); win.requestActivate() }
        function onTrayQuitRequested() { if (App.connected) App.disconnect(); Qt.quit() }
    }
    // "-connect" on the command line connects to the remembered node once the core is up.
    Connections {
        target: core
        function onCoresListed() {
            // Deferred so App's own handler stores the list first.
            Qt.callLater(function() {
                if (importText !== "") App.importText(importText, "默认")
                else if (autoConnect && !App.connected && !App.busy) App.connect()
            })
        }
        function onImported() { Qt.callLater(function() { if (autoConnect && !App.connected && !App.busy) App.connect() }) }
    }
    Shortcut { sequence: "Ctrl+L"; onActivated: App.setLayout(win.landscape ? "portrait" : "landscape") }
}
