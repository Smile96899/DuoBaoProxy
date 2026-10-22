import QtQuick
import QtQuick.Controls

// The one text field: pill background, theme colours for text, placeholder
// and selection so it reads in both light and dark mode.
TextField {
    color: Theme.fg
    placeholderTextColor: Theme.fg2
    selectionColor: Theme.accent
    selectedTextColor: Theme.accentFg
    selectByMouse: true
    leftPadding: 14; rightPadding: 14
    background: Rectangle { radius: height / 2; color: Theme.panel2 }
}
