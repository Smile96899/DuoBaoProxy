import QtQuick

// The rabbit logo; its circle shows the state: grey idle, green running, red running in TUN.
Image {
    property string look: "off"     // off | on | tun
    property int size: 28
    width: size; height: size
    source: "qrc:/qt/qml/DuoBaoProxy/res/logo-" + look + ".png"
    sourceSize: Qt.size(size * 2, size * 2)
    fillMode: Image.PreserveAspectFit
    smooth: true; mipmap: true
}
