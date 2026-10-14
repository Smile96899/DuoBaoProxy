import QtQuick
import QtQuick.Effects

// The rabbit mark, tinted by state: grey idle, accent connected, red error.
Item {
    id: root
    property bool on: false
    property bool error: false
    property int size: 28
    width: size; height: size

    Image {
        id: img
        anchors.fill: parent
        source: "qrc:/qt/qml/HProxy/res/mark.svg"
        sourceSize: Qt.size(size * 2, size * 2)
        visible: false
    }
    MultiEffect {
        anchors.fill: img
        source: img
        colorization: 1
        colorizationColor: root.error ? Theme.bad : (root.on ? Theme.accent : Theme.fg3)
    }
}
