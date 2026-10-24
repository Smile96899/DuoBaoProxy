package mobile

import (
	"context"
	"time"

	"duobaoproxy/api"
	"duobaoproxy/core"
)

// TestResult is the outcome of a Probe: Ms for latency-like probes, Report
// the probe's JSON report.
type TestResult struct {
	Ms     int32
	Report string
}

// testContext is the context a test hands the engine: throw-away sing-box
// instances get the platform (protector + local DNS, no TUN).
func testContext(coreID, config string) context.Context {
	if config != "" && api.CoreID(coreID) == core.IDSingBox {
		p := currentPaths()
		return newTestContext(p.base, p.temp)
	}
	return context.Background()
}

func timeoutOf(ms int32) time.Duration { return time.Duration(ms) * time.Millisecond }

// TCPPing is the direct TCP connect time to address (host:port) in ms, over
// a protected socket. timeoutMs <= 0 means 5 s.
func TCPPing(address string, timeoutMs int32) (int32, error) {
	r, err := svc.Test(context.Background(), api.TestRequest{Mode: api.TestTCPPing, Address: address, Timeout: timeoutOf(timeoutMs)})
	return r.Ms, err
}

// URLTest is the time of one GET of url through a node, in ms. config is a
// Test-mode RenderConfig for coreID (a throw-away instance), or "" to use
// the running engine (live latency must use this). A status >= 400 fails.
func URLTest(coreID, config, url string, timeoutMs int32) (int32, error) {
	r, err := svc.Test(testContext(coreID, config), api.TestRequest{
		Mode: api.TestURLTest, CoreID: coreID, Config: config, URL: url, Timeout: timeoutOf(timeoutMs),
	})
	return r.Ms, err
}

// Probe runs one named probe with the desktop's argument conventions
// (app/qml/Tests.qml):
//
//	icmp     direct        address = host or host:port         -> Ms
//	loss     direct        address = host:port, count (<=0: 10)  -> Ms = avg, Report {"avg","min","max","jitter","loss"}
//	udp      through node  address = DNS server (8.8.8.8:53), url = name (www.google.com) -> Ms
//	download through node  url                                  -> {"bps"}
//	upload   through node  url, count = MiB (<=0: 10)            -> {"bps"}
//	geo      through node  url = trace URL                      -> {"ip","cc"}
//	media    through node  url = comma list of netflix,disney,youtube ("" = all)
//	ai       through node  url = comma list of chatgpt,claude,gemini
//	dnsleak  through node + local resolver                     -> {"leak","local","servers":[...]}
//
// coreID/config select the node like URLTest. timeoutMs <= 0 means 5 s.
func Probe(name, coreID, config, address, url string, count, timeoutMs int32) (*TestResult, error) {
	r, err := svc.Test(testContext(coreID, config), api.TestRequest{
		Mode: api.TestProbe, Probe: name, CoreID: coreID, Config: config,
		Address: address, URL: url, Count: int(count), Timeout: timeoutOf(timeoutMs),
	})
	if err != nil {
		return nil, err
	}
	return &TestResult{Ms: r.Ms, Report: r.Report}, nil
}
