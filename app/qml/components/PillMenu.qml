import QtQuick
import QtQuick.Controls

// Right-click menu in the app's style: a rounded card with pill items top to
// bottom. Declare items as Action (add `property bool danger: true` for red
// text), nested PillMenu for a submenu, and MenuSeparator.
Menu {
    id: root
    padding: 6
    background: Rectangle { implicitWidth: 210; color: Theme.panel; radius: 22; border.width: 1; border.color: Theme.line }
    delegate: MenuItem {
        id: item
        readonly property bool danger: !!(action && action.danger)
        implicitWidth: 198; implicitHeight: 32
        leftPadding: 14; rightPadding: 14
        indicator: null
        contentItem: Text {
            text: item.text
            font.pixelSize: 13
            color: !item.enabled ? Theme.fg3 : (item.danger ? Theme.bad : Theme.fg)
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
            rightPadding: item.subMenu ? 16 : 0
        }
        arrow: Text {
            x: item.width - width - 14; anchors.verticalCenter: parent.verticalCenter
            visible: !!item.subMenu
            text: "›"; color: Theme.fg2; font.pixelSize: 16
        }
        background: Rectangle { radius: 16; color: item.highlighted && item.enabled ? Theme.panel2 : "transparent" }
    }
}
