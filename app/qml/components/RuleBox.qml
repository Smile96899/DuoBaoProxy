import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// A titled text box holding one rule per line, bound to a settings list.
ColumnLayout {
    id: root
    property string title
    property string key          // e.g. "routing.proxy_domain"
    property var lines: []
    property int minHeight: 120
    spacing: 6
    Label { text: root.title; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: Math.max(root.minHeight, ta.implicitHeight + 20)
        radius: Theme.radius; color: Theme.bg; border.width: 1; border.color: Theme.line
        TextArea {
            id: ta
            anchors.fill: parent
            anchors.margins: 10
            text: (root.lines || []).join("\n")
            color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12
            background: null
            wrapMode: TextEdit.NoWrap
            onEditingFinished: {
                var out = text.split("\n").map(s => s.trim()).filter(s => s.length > 0)
                store.setSetting(root.key, out)
            }
        }
    }
}
