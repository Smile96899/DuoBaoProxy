import QtQuick
import QtQuick.Controls
import QtQuick.Window

ApplicationWindow {
    id: win
    readonly property bool landscape: (store.ui.layout || "landscape") === "landscape"
    title: sys.platformName
    visible: !startInTray
    color: Theme.panel
    // Fit the screen at any DPI scale: never wider than the work area.
    readonly property int landW: Math.min(1180, Screen.desktopAvailableWidth - 60)
    readonly property int landH: Math.min(720, Screen.desktopAvailableHeight - 80)
    minimumWidth: landscape ? 900 : 360
    minimumHeight: landscape ? 560 : 640
    width: landscape ? landW : 390
    height: landscape ? landH : Math.min(760, Screen.desktopAvailableHeight - 80)
    font.family: Theme.font
    font.pixelSize: 14

    Component.onCompleted: Theme.mode = store.ui.theme || "system"

    // Keep the chosen layout across restarts; resize when the user switches.
    onLandscapeChanged: {
        width = landscape ? landW : 390
        height = landscape ? landH : Math.min(760, Screen.desktopAvailableHeight - 80)
    }

    Loader {
        anchors.fill: parent
        sourceComponent: win.landscape ? landscapeComp : portraitComp
    }
    Component { id: landscapeComp; Landscape {} }
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
