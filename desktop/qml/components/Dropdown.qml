import QtQuick

// Pill button showing the current choice; the options open beneath it.
PillButton {
    id: root
    property var model: []        // [{v, l}]
    property var current
    signal picked(var v)
    readonly property var currentItem: model.find(m => m.v === current)
    text: (currentItem ? currentItem.l : "") + "  ▾"
    onClicked: pop.open()
    PickPopup {
        id: pop
        y: root.height + 6
        itemWidth: Math.max(root.width - 12, 120)
        model: root.model; current: root.current
        onPicked: v => root.picked(v)
    }
}
