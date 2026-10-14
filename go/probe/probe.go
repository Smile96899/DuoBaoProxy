// Package probe implements latency and throughput tests over a core.Dialer.
// It replaces the libneko speedtest helpers.
package probe

import (
	"context"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"strings"
	"time"

	"hproxy/core"
)

const MiB = 1024 * 1024

// HTTPClient builds an http.Client whose connections go through d.
func HTTPClient(d core.Dialer, timeout time.Duration) *http.Client {
	tr := &http.Transport{
		DialContext:         d.DialContext,
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
		DisableKeepAlives:   true,
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: timeout,
	}
	return &http.Client{Transport: tr, Timeout: timeout}
}

// URLTest returns the round-trip time of one HTTP request made through d.
// A 204/200/3xx response counts as success; the status is not otherwise
// inspected so that generate_204 style endpoints and plain pages both work.
func URLTest(ctx context.Context, d core.Dialer, url string, timeout time.Duration) (int32, error) {
	client := HTTPClient(d, timeout)
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	_ = resp.Body.Close()
	if resp.StatusCode >= 400 {
		return 0, fmt.Errorf("http status %d", resp.StatusCode)
	}
	return int32(time.Since(start).Milliseconds()), nil
}

// TCPPing measures the time to open a TCP connection to addr directly.
func TCPPing(ctx context.Context, addr string, timeout time.Duration) (int32, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var d net.Dialer
	start := time.Now()
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return 0, err
	}
	_ = conn.Close()
	return int32(time.Since(start).Milliseconds()), nil
}

// UDPLatency sends one DNS query for www.google.com to 8.8.8.8 through d
// and measures the time until the first reply byte.
func UDPLatency(ctx context.Context, d core.Dialer, timeout time.Duration) (int32, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	pc, err := d.ListenPacket(ctx)
	if err != nil {
		return 0, err
	}
	defer pc.Close()
	_ = pc.SetDeadline(time.Now().Add(timeout))
	query, _ := hex.DecodeString("0000010000010000000000000377777706676f6f676c6503636f6d0000010001")
	dst := &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}
	start := time.Now()
	if _, err = pc.WriteTo(query, dst); err != nil {
		return 0, err
	}
	var buf [1400]byte
	if _, _, err = pc.ReadFrom(buf[:]); err != nil {
		return 0, err
	}
	return int32(time.Since(start).Milliseconds()), nil
}

// FullTestOptions mirrors the FullTest fields of the gRPC TestReq.
type FullTestOptions struct {
	URL          string
	Timeout      time.Duration
	Latency      bool
	UDPLatency   bool
	Speed        bool
	SpeedURL     string
	SpeedTimeout time.Duration
	InOut        bool
	InAddress    string
}

// FullTestResult is the textual report plus the latency used for sorting.
type FullTestResult struct {
	LatencyMs int32
	Report    string
}

func FullTest(ctx context.Context, d core.Dialer, o FullTestOptions) FullTestResult {
	var res FullTestResult
	var parts []string

	if o.Latency {
		ms, err := URLTest(ctx, d, o.URL, o.Timeout)
		if err == nil {
			res.LatencyMs = ms
			parts = append(parts, fmt.Sprintf("Latency: %dms", ms))
		} else {
			parts = append(parts, "Latency: Error")
		}
	}
	if o.UDPLatency {
		ms, err := UDPLatency(ctx, d, 3*time.Second)
		if err == nil {
			parts = append(parts, fmt.Sprintf("UDPLatency: %dms", ms))
		} else if errors.Is(err, context.DeadlineExceeded) {
			parts = append(parts, "UDPLatency: Timeout")
		} else {
			parts = append(parts, "UDPLatency: Error")
		}
	}
	if o.Speed {
		t := o.SpeedTimeout
		if t <= 0 {
			t = 30 * time.Second
		}
		parts = append(parts, "Speed: "+download(ctx, d, o.SpeedURL, t))
	}
	if o.InOut {
		if ip, err := net.ResolveIPAddr("ip", o.InAddress); err == nil {
			parts = append(parts, "In: "+ip.String())
		} else {
			parts = append(parts, "In: "+err.Error())
		}
		parts = append(parts, "Out: "+egressIP(ctx, d, o.Timeout))
	}
	res.Report = strings.Join(parts, " / ")
	return res
}

func download(ctx context.Context, d core.Dialer, url string, timeout time.Duration) string {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	client := HTTPClient(d, 0)
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "Error"
	}
	resp, err := client.Do(req)
	if err != nil {
		return "Error"
	}
	defer resp.Body.Close()
	start := time.Now()
	n, _ := io.Copy(io.Discard, resp.Body)
	secs := math.Max(time.Since(start).Seconds(), 0.000001)
	if n == 0 {
		if ctx.Err() != nil {
			return "Timeout"
		}
		return "Error"
	}
	return fmt.Sprintf("%.2fMiB/s", float64(n)/secs/MiB)
}

func egressIP(ctx context.Context, d core.Dialer, timeout time.Duration) string {
	client := HTTPClient(d, timeout)
	defer client.CloseIdleConnections()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://www.cloudflare.com/cdn-cgi/trace", nil)
	resp, err := client.Do(req)
	if err != nil {
		return "Error"
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	for _, line := range strings.Split(string(b), "\n") {
		if v, ok := strings.CutPrefix(line, "ip="); ok {
			return strings.TrimSpace(v)
		}
	}
	return "Error"
}
