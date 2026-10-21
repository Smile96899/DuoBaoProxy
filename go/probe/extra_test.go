package probe

import (
	"context"
	"fmt"
	"net"
	"testing"
	"time"
)

func TestICMPPingLoopback(t *testing.T) {
	ms, err := ICMPPing(context.Background(), "127.0.0.1", 2*time.Second)
	if err != nil {
		t.Skipf("ICMP not allowed here: %v", err)
	}
	if ms < 1 {
		t.Fatalf("ms = %d", ms)
	}
}

func TestTCPPingStats(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	st := TCPPingStats(context.Background(), ln.Addr().String(), 4, time.Second)
	if st.Loss != 0 || st.Avg < 1 || st.Min > st.Max {
		t.Fatalf("stats %+v", st)
	}
	// Nothing listens here: everything is lost.
	ln2, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := ln2.Addr().String()
	ln2.Close()
	if st := TCPPingStats(context.Background(), addr, 2, 300*time.Millisecond); st.Loss != 1 {
		t.Fatalf("closed port stats %+v", st)
	}
}

func TestDNSQuery(t *testing.T) {
	// The query UDPLatency used to send as a fixed hex string.
	want := "0000010000010000000000000377777706676f6f676c6503636f6d0000010001"
	if got := fmt.Sprintf("%x", dnsQuery("www.google.com")); got != want {
		t.Fatalf("got %s", got)
	}
}
