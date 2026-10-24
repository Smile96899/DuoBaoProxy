//go:build darwin

package mobile

import (
	"syscall"

	"golang.org/x/sys/unix"
)

const utunControlName = "com.apple.net.utun_control"

// tunName reads the utun name with getsockopt(SYSPROTO_CONTROL,
// UTUN_OPT_IFNAME) (copied from libbox tun_name_darwin.go).
func tunName(fd int32) (string, error) {
	return unix.GetsockoptString(int(fd), 2 /* SYSPROTO_CONTROL */, 2 /* UTUN_OPT_IFNAME */)
}

// findTunFd scans the process fds for the utun control socket that
// NetworkExtension opened for us (copied from libbox tun_darwin.go, itself
// from wireguard-apple). Returns -1 when none is found.
func findTunFd() int32 {
	ctlInfo := &unix.CtlInfo{}
	copy(ctlInfo.Name[:], utunControlName)
	for fd := range 1024 {
		addr, err := unix.Getpeername(fd)
		if err != nil {
			continue
		}
		addrCTL, ok := addr.(*unix.SockaddrCtl)
		if !ok {
			continue
		}
		if ctlInfo.Id == 0 {
			if err = unix.IoctlCtlInfo(fd, ctlInfo); err != nil {
				continue
			}
		}
		if addrCTL.ID == ctlInfo.Id {
			return int32(fd)
		}
	}
	return -1
}

func dupFd(fd int) (int, error) { return syscall.Dup(fd) }
func closeFd(fd int) error      { return syscall.Close(fd) }
