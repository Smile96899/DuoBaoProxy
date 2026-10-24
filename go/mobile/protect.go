package mobile

import (
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/control"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	errProtect = errors.New("mobile: protect socket failed")

	xrayProtectOnce sync.Once
	protectFailures atomic.Int64
)

// protectFD runs the host protector on fd. Without a protector it is a
// no-op (desktop, iOS, or Android before SetProtector).
func protectFD(fd uintptr) error {
	p := currentProtector()
	if p == nil {
		return nil
	}
	if p.Protect(int32(fd)) {
		return nil
	}
	// A failed protect does not abort the dial in xray (the controller error
	// is only logged there), so keep our own count; the first failure and
	// every 100th after it are logged to avoid flooding after the VPN stops.
	if n := protectFailures.Add(1); n == 1 || n%100 == 0 {
		logf(log.LevelWarn, "protect fd %d failed (%d failures so far)", fd, n)
	}
	return errProtect
}

// protectControl is a control.Func (net.Dialer.Control shape) that protects
// the socket before it connects.
func protectControl(_, _ string, c syscall.RawConn) error {
	return control.Raw(c, protectFD)
}

// installXrayProtect hooks the protector into xray's process-wide system
// dialer. Controllers can only be appended, so this runs once per process.
// UseAlternativeSystemDialer must never be called: it silently disables
// registered controllers (spec/xray-mobile.md §1).
func installXrayProtect() {
	xrayProtectOnce.Do(func() {
		if err := internet.RegisterDialerController(protectControl); err != nil {
			logf(log.LevelError, "register xray dialer controller: %v", err)
		}
	})
}

// protectedDialer returns a dialer whose sockets bypass the VPN, for the
// probe/direct paths and subscription fetching. Callers set Timeout etc.
func protectedDialer() *net.Dialer {
	return &net.Dialer{Control: protectControl}
}
