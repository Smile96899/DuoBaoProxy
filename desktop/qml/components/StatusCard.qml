import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Portrait status card: core picker, switch, node name, three big numbers.
Rectangle {
    id: root
    radius: 12
    color: App.connected ? Theme.accentSoft : Theme.panel2
    implicitHeight: col.implicitHeight + 32
    Behavior on color { ColorAnimation { duration: 150 } }

    ColumnLayout {
        id: col
        anchors.fill: parent
        anchors.margins: 16
        spacing: 12

        RowLayout {
            spacing: 10
            Mark { size: 28; look: App.look }
            Dropdown {
                model: App.cores.map(c => ({v: c.id, l: c.id}))
                current: App.coreId
                onPicked: v => App.setCore(v)
            }
            Item { Layout.fillWidth: true }
            Toggle { on: App.connected; enabled: !App.busy; onClicked: App.toggle() }
        }

        Label { text: App.nodeName(App.selectedNode); color: Theme.fg; font.pixelSize: 17; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }

        // The three numbers share the width and shrink to fit a narrow window.
        RowLayout {
            spacing: 14
            ColumnLayout { spacing: 4; Layout.fillWidth: true; Layout.preferredWidth: 1
                Label { text: App.statusText; color: App.connected ? Theme.accent : Theme.fg2; font.pixelSize: 11; font.letterSpacing: 1; elide: Text.ElideRight; Layout.fillWidth: true }
                Label { text: App.connected ? Theme.latText(App.latency) : "—"; color: App.connected ? Theme.latColor(App.latency) : Theme.fg3; font.family: Theme.mono; font.pixelSize: 26; fontSizeMode: Text.HorizontalFit; minimumPixelSize: 12; font.weight: Font.Medium; Layout.fillWidth: true }
            }
            ColumnLayout { spacing: 4; Layout.fillWidth: true; Layout.preferredWidth: 1
                Label { text: "↓ 下行"; color: Theme.fg2; font.pixelSize: 11; font.letterSpacing: 1 }
                Label { text: Theme.bytes(App.downRate) + "/s"; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 20; fontSizeMode: Text.HorizontalFit; minimumPixelSize: 11; font.weight: Font.Medium; Layout.fillWidth: true }
            }
            ColumnLayout { spacing: 4; Layout.fillWidth: true; Layout.preferredWidth: 1
                Label { text: "↑ 上行"; color: Theme.fg2; font.pixelSize: 11; font.letterSpacing: 1 }
                Label { text: Theme.bytes(App.upRate) + "/s"; color: Theme.fg; font.family: Theme.mono; font.pixelSize: 20; fontSizeMode: Text.HorizontalFit; minimumPixelSize: 11; font.weight: Font.Medium; Layout.fillWidth: true }
            }
        }
    }
}
