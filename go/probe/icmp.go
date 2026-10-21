package probe

import (
	"context"
	"net"
	"time"
)

// ICMPPing resolves host and sends one echo request.
func ICMPPing(ctx context.Context, host string, timeout time.Duration) (int32, error) {
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", host)
	if err != nil {
		return 0, err
	}
	if len(ips) == 0 {
		return 0, &net.DNSError{Err: "no IPv4 address", Name: host}
	}
	ms, err := icmpEcho(ips[0], timeout)
	return max(ms, 1), err
}
