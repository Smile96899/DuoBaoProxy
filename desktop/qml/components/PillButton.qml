import QtQuick
import QtQuick.Templates as T

// The one button shape: a 32 px pill. highlighted = primary, flat = no fill.
T.Button {
    id: control
    implicitWidth: Math.max(72, contentItem.implicitWidth + leftPadding + rightPadding)
    implicitHeight: 32
    leftPadding: 16; rightPadding: 16
    font.pixelSize: 13
    hoverEnabled: true
    opacity: enabled ? 1 : .45

    contentItem: Text {
        text: control.text
        font: control.font
        color: control.highlighted ? Theme.panel : Theme.fg
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
    background: Rectangle {
        radius: height / 2
        color: control.highlighted ? (control.down ? Theme.fg2 : Theme.fg)
             : control.down ? Theme.line
             : control.hovered ? Theme.panel2
             : control.flat ? "transparent" : Theme.panel2
        border.width: control.flat || control.highlighted ? 0 : 1
        border.color: Theme.line
        Behavior on color { ColorAnimation { duration: 100 } }
    }
}
