package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"

	"duobaoproxy/probe"
)

// TestMode selects what Test measures. Values match the gRPC TestMode.
type TestMode int

const (
	// TestTCPPing is a direct TCP connect to Address (no proxy).
	TestTCPPing TestMode = iota
	// TestURLTest is one GET of URL through the node.
	TestURLTest
	// TestFullTest is the combined latency / speed / in-out report.
	TestFullTest
	// TestProbe runs the named Probe.
	TestProbe
)

// DefaultTestTimeout replaces a Timeout <= 0.
const DefaultTestTimeout = 5 * time.Second

// TestRequest is one Test call. Fields not used by Mode are ignored.
type TestRequest struct {
	Mode    TestMode
	Timeout time.Duration

	// CoreID and Config select the node for tests that go through one:
	// a non-empty Config builds a throw-away instance of CoreID ("" means
	// sing-box), otherwise the running core is used.
	CoreID string
	Config string

	// TcpPing, icmp, loss: host:port. udp: DNS server.
	Address string
	// UrlTest, FullTest: test URL. Probes: see Probe.
	URL string

	// FullTest
	InAddress        string
	FullLatency      bool
	FullUDPLatency   bool
	FullSpeed        bool
	FullSpeedURL     string
	FullSpeedTimeout time.Duration
	FullInOut        bool

	// Probe: icmp, loss (direct); udp, download, upload, geo, media, ai,
	// dnsleak (through the node).
	Probe string
	// loss: number of pings. upload: MiB to send.
	Count int
}

// TestResult is the outcome of a Test. Ms is the latency in milliseconds,
// Report the FullTest text or the probe JSON.
type TestResult struct {
	Ms     int32
	Report string
}

// Test runs one test. TcpPing and UrlTest keep Ms even when they fail;
// a failed probe returns a zero result.
func (s *Service) Test(ctx context.Context, in TestRequest) (TestResult, error) {
	var out TestResult
	timeout := in.Timeout
	if timeout <= 0 {
		timeout = DefaultTestTimeout
	}
	switch in.Mode {
	case TestTCPPing:
		ms, err := probe.TCPPing(ctx, in.Address, timeout)
		out.Ms = ms
		return out, err
	case TestURLTest, TestFullTest:
		d, release, err := s.dialerFor(ctx, in.CoreID, in.Config)
		if err != nil {
			return out, err
		}
		defer release()
		if in.Mode == TestURLTest {
			out.Ms, err = probe.URLTest(ctx, d, in.URL, timeout)
			return out, err
		}
		r := probe.FullTest(ctx, d, probe.FullTestOptions{
			URL:          in.URL,
			Timeout:      timeout,
			Latency:      in.FullLatency,
			UDPLatency:   in.FullUDPLatency,
			Speed:        in.FullSpeed,
			SpeedURL:     in.FullSpeedURL,
			SpeedTimeout: in.FullSpeedTimeout,
			InOut:        in.FullInOut,
			InAddress:    in.InAddress,
		})
		out.Ms = r.LatencyMs
		out.Report = r.Report
		return out, nil
	case TestProbe:
		return s.runProbe(ctx, in, timeout)
	default:
		return out, errors.New("unknown test mode")
	}
}

// runProbe answers TestProbe: Ms for latency-like checks, JSON in Report.
func (s *Service) runProbe(ctx context.Context, in TestRequest, timeout time.Duration) (TestResult, error) {
	var out TestResult
	report := func(v any) {
		b, _ := json.Marshal(v)
		out.Report = string(b)
	}

	// Checks that talk to the server directly.
	switch in.Probe {
	case "icmp":
		host, _, err := net.SplitHostPort(in.Address)
		if err != nil {
			host = in.Address
		}
		ms, err := probe.ICMPPing(ctx, host, timeout)
		if err != nil {
			return TestResult{}, err
		}
		out.Ms = ms
		return out, nil
	case "loss":
		st := probe.TCPPingStats(ctx, in.Address, in.Count, timeout)
		out.Ms = st.Avg
		report(st)
		return out, nil
	}

	// The rest go through the node.
	d, release, err := s.dialerFor(ctx, in.CoreID, in.Config)
	if err != nil {
		return TestResult{}, err
	}
	defer release()
	switch in.Probe {
	case "udp":
		server, name := in.Address, in.URL
		if server == "" {
			server = "8.8.8.8:53"
		}
		if name == "" {
			name = "www.google.com"
		}
		ms, err := probe.UDPLatencyTo(ctx, d, server, name, timeout)
		if err != nil {
			return TestResult{}, err
		}
		out.Ms = max(ms, 1)
	case "download":
		bps, err := probe.DownloadSpeed(ctx, d, in.URL, timeout)
		if err != nil {
			return TestResult{}, err
		}
		report(map[string]float64{"bps": bps})
	case "upload":
		size := int64(in.Count)
		if size <= 0 {
			size = 10
		}
		bps, err := probe.UploadSpeed(ctx, d, in.URL, size*1024*1024, timeout)
		if err != nil {
			return TestResult{}, err
		}
		report(map[string]float64{"bps": bps})
	case "geo":
		g, err := probe.ExitGeo(ctx, d, in.URL, timeout)
		if err != nil {
			return TestResult{}, err
		}
		report(g)
	case "media":
		report(probe.MediaUnlock(ctx, d, timeout, in.URL))
	case "ai":
		report(probe.AIUnlock(ctx, d, timeout, in.URL))
	case "dnsleak":
		r, err := probe.DNSLeakTest(ctx, d, timeout)
		if err != nil {
			return TestResult{}, err
		}
		report(r)
	default:
		return TestResult{}, fmt.Errorf("unknown probe %q", in.Probe)
	}
	return out, nil
}
