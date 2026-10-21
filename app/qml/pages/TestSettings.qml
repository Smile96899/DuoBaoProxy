import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"

// 设置 › 测试设置: the list of tests, then one test's targets and limits.
Item {
    id: root
    signal back()
    property string kind: ""            // "" = the list
    readonly property var info: Tests.kind(kind)

    component Input: TextField { color: Theme.fg; leftPadding: 14; rightPadding: 14; background: Rectangle { radius: height / 2; color: Theme.panel2 } }

    Flickable {
        anchors.fill: parent
        contentHeight: col.implicitHeight + 36
        clip: true
        ColumnLayout {
            id: col
            width: Math.min(parent.width - 36, 640)
            x: 18; y: 12
            spacing: 0

            // ‹ back, then the page title
            Label {
                text: "‹ " + (root.kind === "" ? "设置" : "测试设置")
                color: backMa.containsMouse ? Theme.fg : Theme.fg2; font.pixelSize: 13
                MouseArea { id: backMa; anchors.fill: parent; anchors.margins: -6; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                    onClicked: root.kind === "" ? root.back() : root.kind = "" }
            }
            Label { text: root.kind === "" ? "测试设置" : root.info.l; color: Theme.fg; font.pixelSize: 15; font.weight: Font.DemiBold; Layout.topMargin: 8 }
            Label { visible: root.kind !== ""; text: root.info.d || ""; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true; Layout.topMargin: 4; Layout.bottomMargin: 6 }

            // ---- list of tests ----
            Repeater {
                model: root.kind === "" ? Tests.kinds : []
                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: rowCol.implicitHeight + 20
                    color: rowMa.containsMouse ? Theme.panel2 : "transparent"
                    radius: Theme.radius
                    ColumnLayout {
                        id: rowCol
                        anchors.left: parent.left; anchors.right: arrow.left; anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: 10; anchors.rightMargin: 10
                        spacing: 2
                        Label { text: modelData.l; color: Theme.fg; font.weight: Font.Medium }
                        Label { text: modelData.d; color: Theme.fg2; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
                    }
                    Label { id: arrow; anchors.right: parent.right; anchors.rightMargin: 12; anchors.verticalCenter: parent.verticalCenter; text: "›"; color: Theme.fg3; font.pixelSize: 18 }
                    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                    MouseArea { id: rowMa; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: root.kind = modelData.k }
                }
            }

            // ---- one test's settings ----
            Repeater {
                model: root.kind !== "" ? (Tests.fields[root.kind] || []) : []
                Field {
                    id: fieldRow
                    Layout.fillWidth: true
                    title: modelData.l
                    detail: modelData.h || ""
                    readonly property var value: { store.ui.tests; store.ui.test_url; return Tests.opt(root.kind, modelData.f) }

                    Input {
                        visible: modelData.t === "text"
                        width: modelData.t === "text" ? 300 : 0
                        text: modelData.t === "text" ? String(fieldRow.value) : ""
                        onEditingFinished: if (text.trim() !== String(fieldRow.value)) Tests.setOpt(root.kind, modelData.f, text.trim())
                    }
                    Input {
                        visible: modelData.t === "int"
                        width: modelData.t === "int" ? 90 : 0
                        font.family: Theme.mono; horizontalAlignment: Text.AlignHCenter
                        validator: IntValidator { bottom: 1; top: 3600 }
                        text: modelData.t === "int" ? String(fieldRow.value) : ""
                        onEditingFinished: if (Number(text) !== Number(fieldRow.value)) Tests.setOpt(root.kind, modelData.f, Number(text))
                    }
                    // Which services to check: pills you switch on and off (at least one stays on).
                    Row {
                        visible: modelData.t === "services"
                        spacing: 8
                        Repeater {
                            model: modelData.t === "services" ? modelData.c : []
                            Rectangle {
                                readonly property bool on: (fieldRow.value || []).indexOf(modelData[0]) >= 0
                                height: 32; radius: 16
                                width: chipText.implicitWidth + 28
                                color: on ? Theme.fg : (chipMa.containsMouse ? Theme.panel2 : "transparent")
                                border.width: on ? 0 : 1; border.color: Theme.line
                                Text { id: chipText; anchors.centerIn: parent; text: (parent.on ? "✓ " : "") + modelData[1]; font.pixelSize: 13; color: parent.on ? Theme.panel : Theme.fg }
                                MouseArea {
                                    id: chipMa; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        var list = (fieldRow.value || []).slice(), i = list.indexOf(modelData[0])
                                        if (i >= 0) { if (list.length > 1) list.splice(i, 1) } else list.push(modelData[0])
                                        Tests.setOpt(root.kind, "services", list)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            RowLayout {
                visible: root.kind !== ""
                Layout.fillWidth: true; Layout.topMargin: 14
                Item { Layout.fillWidth: true }
                PillButton { text: "恢复默认"; flat: true; onClicked: Tests.resetOpts(root.kind) }
            }
        }
    }
}
