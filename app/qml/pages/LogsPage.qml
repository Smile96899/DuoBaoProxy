import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: root
    property string filter: "all"   // all | error
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        RowLayout {
            Layout.fillWidth: true; Layout.margins: 12; Layout.leftMargin: 18; Layout.rightMargin: 18; spacing: 10
            Label { text: "日志"; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold }
            Item { Layout.fillWidth: true }
            Button { text: "清空"; flat: true; onClicked: App.logs = [] }
            Button { text: "复制"; onClicked: sys.copyText(App.logs.join("\n")) }
        }
        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }
        Rectangle {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.margins: 12
            radius: Theme.radius; color: Theme.bg
            ListView {
                id: list
                anchors.fill: parent; anchors.margins: 10
                clip: true
                model: App.logs
                onCountChanged: positionViewAtEnd()
                delegate: Label {
                    width: list.width
                    text: modelData
                    wrapMode: Text.WrapAnywhere
                    font.family: Theme.mono; font.pixelSize: 11
                    color: /error|fatal|failed|timeout/i.test(modelData) ? Theme.bad : (/warn/i.test(modelData) ? Theme.warn : Theme.fg2)
                }
            }
        }
    }
}
