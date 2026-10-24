//go:build tools

package mobile

// Keeps golang.org/x/mobile in go.mod: gomobile bind needs the bind package
// in the module being bound.
import _ "golang.org/x/mobile/bind"
