//go:build !windows

package probe

import (
	"fmt"
	"net"
	"os"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
)

// Unprivileged ICMP ("udp4" sockets): macOS allows it, Linux when
// net.ipv4.ping_group_range covers the user.
func icmpEcho(ip net.IP, timeout time.Duration) (int32, error) {
	ip4 := ip.To4()
	if ip4 == nil {
		return 0, fmt.Errorf("ICMP 只支持 IPv4")
	}
	c, err := icmp.ListenPacket("udp4", "0.0.0.0")
	if err != nil {
		return 0, err
	}
	defer c.Close()
	msg := icmp.Message{Type: ipv4.ICMPTypeEcho, Body: &icmp.Echo{ID: os.Getpid() & 0xffff, Seq: 1, Data: []byte("h.proxy ping")}}
	b, _ := msg.Marshal(nil)
	start := time.Now()
	if _, err := c.WriteTo(b, &net.UDPAddr{IP: ip4}); err != nil {
		return 0, err
	}
	_ = c.SetReadDeadline(time.Now().Add(timeout))
	buf := make([]byte, 1500)
	for {
		n, _, err := c.ReadFrom(buf)
		if err != nil {
			return 0, fmt.Errorf("timeout")
		}
		if m, err := icmp.ParseMessage(1, buf[:n]); err == nil && m.Type == ipv4.ICMPTypeEchoReply {
			return int32(time.Since(start).Milliseconds()), nil
		}
	}
}
