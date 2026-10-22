import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// A titled text box holding one rule per line, bound to a settings list.
ColumnLayout {
    id: root
    property string title
    property string key          // e.g. "routing.proxy_domain"
    property var lines: []
    property int boxHeight: 140         // every box the same size; long lists scroll inside
    spacing: 6
    Label { text: root.title; color: Theme.fg3; font.pixelSize: 11; font.capitalization: Font.AllUppercase; font.letterSpacing: 1 }
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: root.boxHeight
        radius: Theme.radius; color: Theme.bg; border.width: 1; border.color: Theme.line
        // Long rules scroll sideways, long lists scroll down, never out of the box.
        ScrollView {
            anchors.fill: parent
            anchors.margins: 10
            TextArea {
                id: ta
                text: (root.lines || []).join("\n")
                color: Theme.fg; font.family: Theme.mono; font.pixelSize: 12
                placeholderTextColor: Theme.fg3; selectionColor: Theme.accent; selectedTextColor: Theme.accentFg; selectByMouse: true;
                background: null
                padding: 0
                wrapMode: TextEdit.NoWrap
                // Save shortly after typing stops, not only on focus loss, so
                // rules survive clicking straight onto another button or page.
                function save() {
                    var out = text.split("\n").map(s => s.replace(/。/g, ".").trim()).filter(s => s.length > 0)
                    if (JSON.stringify(out) !== JSON.stringify(root.lines || [])) store.setSetting(root.key, out)
                }
                onEditingFinished: save()
                onTextChanged: if (activeFocus) saveTimer.restart()
                Timer { id: saveTimer; interval: 600; onTriggered: ta.save() }
            }
        }
    }
}
