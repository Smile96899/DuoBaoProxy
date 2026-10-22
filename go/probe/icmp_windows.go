//go:build windows

package probe

import (
	"encoding/binary"
	"fmt"
	"net"
	"syscall"
	"time"
	"unsafe"
)

// Windows answers ICMP echo through iphlpapi without admin rights.
var (
	iphlpapi        = syscall.NewLazyDLL("iphlpapi.dll")
	icmpCreateFile  = iphlpapi.NewProc("IcmpCreateFile")
	icmpCloseHandle = iphlpapi.NewProc("IcmpCloseHandle")
	icmpSendEcho    = iphlpapi.NewProc("IcmpSendEcho")
)

func icmpEcho(ip net.IP, timeout time.Duration) (int32, error) {
	ip4 := ip.To4()
	if ip4 == nil {
		return 0, fmt.Errorf("ICMP 只支持 IPv4")
	}
	h, _, err := icmpCreateFile.Call()
	if h == uintptr(syscall.InvalidHandle) {
		return 0, err
	}
	defer icmpCloseHandle.Call(h)

	data := []byte("DuoBao Proxy ping")
	reply := make([]byte, 256+len(data))
	addr := *(*uint32)(unsafe.Pointer(&ip4[0]))
	n, _, _ := icmpSendEcho.Call(h, uintptr(addr),
		uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)), 0,
		uintptr(unsafe.Pointer(&reply[0])), uintptr(len(reply)), uintptr(timeout.Milliseconds()))
	if n == 0 {
		return 0, fmt.Errorf("timeout")
	}
	// ICMP_ECHO_REPLY: Address, Status, RoundTripTime, ...
	if status := binary.LittleEndian.Uint32(reply[4:8]); status != 0 {
		return 0, fmt.Errorf("icmp status %d", status)
	}
	return int32(binary.LittleEndian.Uint32(reply[8:12])), nil
}
