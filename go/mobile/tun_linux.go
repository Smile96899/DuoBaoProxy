//go:build linux

package mobile

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/unix"
)

// tunName reads the TUN interface name with the TUNGETIFF ioctl
// (copied from libbox tun_name_linux.go).
func tunName(fd int32) (string, error) {
	var ifr [unix.IFNAMSIZ + 64]byte
	_, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(unix.TUNGETIFF), uintptr(unsafe.Pointer(&ifr[0])))
	if errno != 0 {
		return "", fmt.Errorf("TUNGETIFF: %w", errno)
	}
	return unix.ByteSliceToString(ifr[:]), nil
}

// findTunFd is only needed under NetworkExtension; the Android host always
// returns the established fd.
func findTunFd() int32 { return -1 }

func dupFd(fd int) (int, error) { return syscall.Dup(fd) }
func closeFd(fd int) error      { return syscall.Close(fd) }
