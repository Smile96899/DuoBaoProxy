package mobile

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	"image/png"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/log"

	"duobaoproxy/render"
)

const ownPkg = "com.duobao.proxy"

// TestMain runs Setup once in a directory that outlives the tests: the
// crash log stays open for the life of the process, which would make
// t.TempDir's cleanup fail on Windows.
func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "dbcore-mobile-test-")
	if err != nil {
		panic(err)
	}
	if err = Setup(&SetupOptions{BasePath: dir, Platform: render.PlatformAndroid, OwnPackage: ownPkg}); err != nil {
		panic(err)
	}
	code := m.Run()
	_ = os.RemoveAll(dir) // best effort; crash.log is still open
	os.Exit(code)
}

func TestSetup(t *testing.T) {
	if err := Setup(nil); err == nil {
		t.Error("nil options accepted")
	}
	if err := Setup(&SetupOptions{BasePath: currentPaths().base, Platform: "windows"}); err == nil {
		t.Error("unknown platform accepted")
	}
	p := currentPaths()
	if os.Getenv("TMPDIR") != p.temp || os.Getenv("XRAY_LOCATION_ASSET") != p.asset {
		t.Errorf("env TMPDIR=%q XRAY_LOCATION_ASSET=%q, paths %+v", os.Getenv("TMPDIR"), os.Getenv("XRAY_LOCATION_ASSET"), p)
	}
	if Version() == "" {
		t.Error("empty version")
	}
	var cores []coreInfo
	if err := json.Unmarshal([]byte(ListCoresJSON()), &cores); err != nil || len(cores) != 2 ||
		cores[0].ID != "sing-box" || cores[1].ID != "xray" || len(cores[0].Protocols) == 0 {
		t.Fatalf("ListCoresJSON = %s (%v)", ListCoresJSON(), err)
	}
	for _, tc := range []struct {
		core, proto string
		want        bool
	}{
		{"", "hysteria2", true}, {"sing-box", "vless", true}, {"xray", "vless", true},
		{"xray", "hysteria2", false}, {"xray", "naive", false}, {"nope", "vless", false},
	} {
		if got := Supports(tc.core, tc.proto); got != tc.want {
			t.Errorf("Supports(%q, %q) = %v", tc.core, tc.proto, got)
		}
	}
}

// Every routing list and rule set non-empty so each case shows what it
// clears; unknown keys and a number that float64 cannot hold must survive.
const fullSettings = `{
  "log_level": "warn",
  "apps": [{"name":"Chrome","process":"com.android.chrome","outbound":"proxy","platform":"android"}],
  "tun": {"enabled": %v, "mtu": 9000, "stack": "mixed"},
  "routing": {
    "proxy_domain": ["geosite:google"], "direct_domain": ["geosite:cn"], "block_domain": ["geosite:ads"],
    "proxy_ip": ["8.8.8.8/32"], "direct_ip": ["geoip:cn"], "block_ip": ["1.2.3.4/32"],
    "custom_rules": [{"domain":["x.example"],"outbound":"proxy"}],
    "final": "block", "remote_dns": "https://1.1.1.1/dns-query"
  },
  "x_future": {"big": 12345678901234567890, "s": "<&>"}
}`

var listKeys = []string{"proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip", "custom_rules"}

func TestPrepareSettings(t *testing.T) {
	// Expectations written out from App.qml renderSettings():
	//   global: proxy_domain direct_domain proxy_ip direct_ip custom_rules = [], final = proxy
	//   direct: final = bypass;  rule: untouched
	//   parts rules: apps = [];  apps: all six lists + custom_rules = [];  both: untouched
	//   tun.enabled = tun
	cases := []struct {
		mode, parts string
		final       string
		cleared     []string
		appsCleared bool
	}{
		{"global", "both", "proxy", []string{"proxy_domain", "direct_domain", "proxy_ip", "direct_ip", "custom_rules"}, false},
		{"global", "rules", "proxy", []string{"proxy_domain", "direct_domain", "proxy_ip", "direct_ip", "custom_rules"}, true},
		{"global", "apps", "proxy", []string{"proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip", "custom_rules"}, false},
		{"rule", "both", "block", nil, false},
		{"rule", "rules", "block", nil, true},
		{"rule", "apps", "block", []string{"proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip", "custom_rules"}, false},
		{"direct", "both", "bypass", nil, false},
		{"direct", "rules", "bypass", nil, true},
		{"direct", "apps", "bypass", []string{"proxy_domain", "direct_domain", "block_domain", "proxy_ip", "direct_ip", "block_ip", "custom_rules"}, false},
		// Empty values take the QML defaults: global and both.
		{"", "", "proxy", []string{"proxy_domain", "direct_domain", "proxy_ip", "direct_ip", "custom_rules"}, false},
	}
	for _, tc := range cases {
		for _, tun := range []bool{false, true} {
			name := fmt.Sprintf("%s/%s/tun=%v", tc.mode, tc.parts, tun)
			in := fmt.Sprintf(fullSettings, !tun)
			out, err := PrepareSettings(in, tc.mode, tc.parts, tun)
			if err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			var got, orig map[string]any
			if err = json.Unmarshal([]byte(out), &got); err != nil {
				t.Fatalf("%s: output %s: %v", name, out, err)
			}
			_ = json.Unmarshal([]byte(in), &orig)
			routing := got["routing"].(map[string]any)
			origRouting := orig["routing"].(map[string]any)
			if routing["final"] != tc.final {
				t.Errorf("%s: final = %v, want %s", name, routing["final"], tc.final)
			}
			for _, k := range listKeys {
				cleared := false
				for _, c := range tc.cleared {
					cleared = cleared || c == k
				}
				want := origRouting[k]
				if cleared {
					want = []any{}
				}
				if !reflect.DeepEqual(routing[k], want) {
					t.Errorf("%s: routing.%s = %v, want %v", name, k, routing[k], want)
				}
			}
			if apps := got["apps"].([]any); (len(apps) == 0) != tc.appsCleared {
				t.Errorf("%s: apps = %v", name, apps)
			}
			tunObj := got["tun"].(map[string]any)
			if tunObj["enabled"] != tun || tunObj["mtu"] != float64(9000) || tunObj["stack"] != "mixed" {
				t.Errorf("%s: tun = %v", name, tunObj)
			}
			if routing["remote_dns"] != "https://1.1.1.1/dns-query" || got["log_level"] != "warn" {
				t.Errorf("%s: other settings lost: %s", name, out)
			}
			if got["platform"] != "android" || got["own_package"] != ownPkg {
				t.Errorf("%s: platform not injected: %v %v", name, got["platform"], got["own_package"])
			}
			if !strings.Contains(out, `"big":12345678901234567890`) || !strings.Contains(out, `"s":"<&>"`) {
				t.Errorf("%s: unknown field changed: %s", name, out)
			}
		}
	}
}

func TestPrepareSettingsEdges(t *testing.T) {
	// Empty settings (first run before seeding): objects are created.
	out, err := PrepareSettings("", "direct", "both", true)
	if err != nil {
		t.Fatal(err)
	}
	var s render.Settings
	if err = json.Unmarshal([]byte(out), &s); err != nil || s.Routing.Final != "bypass" || !s.TUN.Enabled || s.Platform != "android" {
		t.Fatalf("empty: %s %v", out, err)
	}
	// An explicit platform is kept.
	out, _ = PrepareSettings(`{"platform":"ios"}`, "rule", "both", false)
	if !strings.Contains(out, `"platform":"ios"`) {
		t.Errorf("platform overridden: %s", out)
	}
	if _, err = PrepareSettings("{", "", "", false); err == nil || !strings.HasPrefix(err.Error(), "settings: ") {
		t.Errorf("bad json: %v", err)
	}
}

const socksNode = `{"protocol":"socks","server":"127.0.0.1","port":1080}`

func TestRenderConfig(t *testing.T) {
	settings, err := PrepareSettings(`{"inbound":{"port":2080}}`, "global", "both", true)
	if err != nil {
		t.Fatal(err)
	}
	// xray + TUN on mobile: bridge + front.
	r, err := RenderConfig("xray", "["+socksNode+"]", settings, int32(render.ModeFull))
	if err != nil {
		t.Fatal(err)
	}
	if r.FrontConfig == "" {
		t.Fatal("no front config")
	}
	var front struct {
		Inbounds  []map[string]any `json:"inbounds"`
		Outbounds []map[string]any `json:"outbounds"`
	}
	if err = json.Unmarshal([]byte(r.FrontConfig), &front); err != nil {
		t.Fatal(err)
	}
	var bridge map[string]any
	for _, o := range front.Outbounds {
		if o["type"] == "socks" {
			bridge = o
		}
	}
	if bridge == nil || bridge["server_port"] == float64(0) || len(bridge["username"].(string)) != 24 || bridge["username"] == bridge["password"] {
		t.Fatalf("front bridge outbound: %v", front.Outbounds)
	}
	port := fmt.Sprint(bridge["server_port"])
	if !strings.Contains(r.Config, port) || !strings.Contains(r.Config, bridge["password"].(string)) {
		t.Errorf("xray config does not listen on the bridge %s", port)
	}
	if !strings.Contains(r.FrontConfig, ownPkg) {
		t.Errorf("own package not excluded in front: %s", r.FrontConfig)
	}
	var tags []string
	if err = json.Unmarshal([]byte(r.StatsTagsJSON), &tags); err != nil || len(tags) == 0 || tags[0] != "proxy" {
		t.Errorf("stats tags %s", r.StatsTagsJSON)
	}
	// Two renders pick different credentials.
	r2, _ := RenderConfig("xray", "["+socksNode+"]", settings, int32(render.ModeFull))
	if r2 == nil || r2.FrontConfig == r.FrontConfig {
		t.Error("bridge credentials reused")
	}
	// Test mode and sing-box have no front.
	for _, tc := range []struct {
		core string
		mode render.Mode
	}{{"xray", render.ModeTest}, {"xray", render.ModeExport}, {"sing-box", render.ModeFull}, {"", render.ModeTest}} {
		r, err := RenderConfig(tc.core, "["+socksNode+"]", settings, int32(tc.mode))
		if err != nil || r.FrontConfig != "" || r.Config == "" {
			t.Errorf("%+v: front %q err %v", tc, r.FrontConfig, err)
		}
	}
	if _, err = RenderConfig("", "[]", settings, 0); err == nil || err.Error() != "empty chain" {
		t.Errorf("empty chain: %v", err)
	}
	if _, err = RenderConfig("", "{", settings, 0); err == nil || !strings.HasPrefix(err.Error(), "chain: ") {
		t.Errorf("bad chain: %v", err)
	}
}

func TestNodes(t *testing.T) {
	r := ImportNodes("socks5://127.0.0.1:1080#a\nvmess://garbage")
	var nodes []map[string]any
	var errs []string
	if err := json.Unmarshal([]byte(r.NodesJSON), &nodes); err != nil || len(nodes) != 1 || r.Count != 1 {
		t.Fatalf("nodes %s: %v", r.NodesJSON, err)
	}
	if err := json.Unmarshal([]byte(r.ErrorsJSON), &errs); err != nil || len(errs) != 1 {
		t.Fatalf("errors %s: %v", r.ErrorsJSON, err)
	}
	if r := ImportNodes(""); r.NodesJSON != "[]" || r.ErrorsJSON != "[]" || r.Count != 0 {
		t.Errorf("empty import: %+v", r)
	}
	one, _ := json.Marshal(nodes[0])
	if link, err := ShareLink(string(one), false); err != nil || !strings.HasPrefix(link, "socks") {
		t.Errorf("share %q %v", link, err)
	}
	wg := `{"protocol":"wireguard","server":"1.2.3.4","port":51820,"wireguard":{"private_key":"a","peer_public_key":"b","local_address":"10.0.0.2, fd00::2"}}`
	out, err := NormalizeNode(wg)
	if err != nil || !strings.Contains(out, "10.0.0.2/32") || !strings.Contains(out, "fd00::2/128") {
		t.Errorf("normalize %s: %v", out, err)
	}
	if _, err = NormalizeNode(`{"protocol":"vless","server":"a","port":1}`); err == nil || !strings.Contains(err.Error(), "uuid") {
		t.Errorf("validate: %v", err)
	}
	if _, err = NormalizeNode("{"); err == nil {
		t.Error("bad json accepted")
	}
}

func TestQR(t *testing.T) {
	const text = "vless://id@example.com:443#节点"
	img, err := EncodeQR(text, 0)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := DecodeQR(img); err != nil || got != text {
		t.Fatalf("decode %q %v", got, err)
	}
	// Luminance path: the PNG is grayscale, so its pixels are the Y plane.
	m, _ := png.Decode(bytes.NewReader(img))
	g, ok := m.(*image.Gray)
	if !ok {
		t.Fatalf("encoded image is %T", m)
	}
	if got, err := DecodeQRLuminance(g.Pix, int32(g.Rect.Dx()), int32(g.Rect.Dy()), int32(g.Stride)); err != nil || got != text {
		t.Errorf("luminance %q %v", got, err)
	}
	var blank bytes.Buffer
	_ = png.Encode(&blank, image.NewGray(image.Rect(0, 0, 64, 64)))
	if _, err := DecodeQR(blank.Bytes()); err != errQRNotFound {
		t.Errorf("blank image: %v", err)
	}
}

func TestLineLevel(t *testing.T) {
	for line, want := range map[string]log.Level{
		"INFO[0000] router: updated default interface":          log.LevelInfo,
		"WARN[0001] dns: ERROR text later in the message":       log.LevelWarn,
		"+0800 2026-01-01 12:00:00 ERROR inbound/tun: closed":   log.LevelError,
		"2026/01/01 12:00:00.000 [Warning] core: something odd": log.LevelWarn,
		"2026/01/01 12:00:00.000 [Debug] app/dns: cache hit":    log.LevelDebug,
		"plain line": log.LevelInfo,
	} {
		if got := lineLevel(line); got != want {
			t.Errorf("lineLevel(%q) = %v, want %v", line, got, want)
		}
	}
}

func TestFetchSubscription(t *testing.T) {
	var ua string
	mux := http.NewServeMux()
	mux.HandleFunc("/old", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/sub", http.StatusFound) })
	mux.HandleFunc("/sub", func(w http.ResponseWriter, r *http.Request) {
		ua = r.UserAgent()
		w.Header().Set("subscription-userinfo", "upload=1; download=2; total=3; expire=4")
		_, _ = w.Write([]byte("c29ja3M1Oi8vMTI3LjAuMC4xOjEwODA="))
	})
	mux.HandleFunc("/big", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(make([]byte, maxSubscriptionBody+1))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	fp := &fakeProtector{}
	SetProtector(fp)
	defer SetProtector(nil)
	r, err := FetchSubscription(srv.URL+"/old", "", 5000)
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != 200 || r.Body != "c29ja3M1Oi8vMTI3LjAuMC4xOjEwODA=" || r.UserInfo != "upload=1; download=2; total=3; expire=4" {
		t.Errorf("result %+v", r)
	}
	if ua != "DuoBaoProxy/"+version {
		t.Errorf("user agent %q", ua)
	}
	if len(fp.fds) == 0 {
		t.Error("subscription socket not protected")
	}
	if r, err = FetchSubscription(srv.URL+"/missing", "Clash", 5000); err != nil || r.Status != 404 {
		t.Errorf("404: %+v %v", r, err)
	}
	if _, err = FetchSubscription(srv.URL+"/big", "", 10000); err == nil {
		t.Error("oversized body accepted")
	}
	if _, err = FetchSubscription("http://127.0.0.1:1/", "", 2000); err == nil {
		t.Error("closed port succeeded")
	}
}

// echoServer accepts and closes TCP connections.
func echoServer(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	return ln.Addr().String()
}

func TestDirectProbesProtected(t *testing.T) {
	addr := echoServer(t)
	fp := &fakeProtector{}
	SetProtector(fp)
	defer SetProtector(nil)
	if ms, err := TCPPing(addr, 2000); err != nil || ms < 0 {
		t.Fatalf("tcp ping %d %v", ms, err)
	}
	if len(fp.fds) != 1 {
		t.Errorf("tcp ping protected %d sockets", len(fp.fds))
	}
	r, err := Probe("loss", "", "", addr, "", 3, 2000)
	if err != nil {
		t.Fatal(err)
	}
	var st struct{ Loss float64 }
	if err = json.Unmarshal([]byte(r.Report), &st); err != nil || st.Loss != 0 {
		t.Errorf("loss report %s %v", r.Report, err)
	}
	if len(fp.fds) != 4 {
		t.Errorf("loss protected %d sockets in total, want 4", len(fp.fds))
	}
	if _, err = Probe("nope", "", "", "", "", 0, 0); err == nil {
		t.Error("unknown probe without a running core succeeded")
	}
	if _, err = URLTest("", "", "http://"+addr, 1000); err == nil {
		t.Error("URLTest without a running core succeeded")
	}
}

const (
	singboxDirect = `{"log":{"level":"warn"},"outbounds":[{"type":"direct","tag":"proxy"}]}`
	xrayFreedom   = `{"log":{"loglevel":"warning"},"outbounds":[{"protocol":"freedom","tag":"proxy"}]}`
)

func httpTarget(t *testing.T) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	t.Cleanup(srv.Close)
	return srv.URL
}

func TestURLTestThrowAway(t *testing.T) {
	url := httpTarget(t)
	for _, tc := range []struct{ core, config string }{{"sing-box", singboxDirect}, {"xray", xrayFreedom}} {
		if ms, err := URLTest(tc.core, tc.config, url, 5000); err != nil || ms < 0 {
			t.Errorf("%s throw-away: %d %v", tc.core, ms, err)
		}
	}
	if Status() != "" {
		t.Errorf("throw-away left %q running", Status())
	}
}

func TestStartStopSingBox(t *testing.T) {
	url := httpTarget(t)
	logs := &fakeLogHandler{}
	SetLogHandler(logs)
	defer SetLogHandler(nil)
	if err := Start(&StartOptions{CoreID: "", Config: singboxDirect, StatsTagsJSON: `["proxy"]`}, nil); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	if Status() != "sing-box" {
		t.Fatalf("status %q", Status())
	}
	if _, err := os.Stat(filepath.Join(currentPaths().base, "cache.db")); err != nil {
		t.Errorf("cache.db not under BasePath: %v", err)
	}
	if err := Start(&StartOptions{Config: singboxDirect}, nil); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Errorf("second start: %v", err)
	}
	if ms, err := URLTest("sing-box", "", url, 5000); err != nil || ms < 0 {
		t.Errorf("running-core url test: %d %v", ms, err)
	}
	if !strings.Contains(ListCoresJSON(), `"id":"sing-box","version"`) || !strings.Contains(ListCoresJSON(), `"running":true`) {
		t.Errorf("list cores: %s", ListCoresJSON())
	}
	_ = QueryStats("proxy") // counters may be zero; it must not fail
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	if err := Stop(); err != nil {
		t.Errorf("second stop: %v", err)
	}
	if Status() != "" || QueryStats("proxy").Uplink != 0 {
		t.Errorf("after stop: %q", Status())
	}
}

func TestStartXrayWithFront(t *testing.T) {
	url := httpTarget(t)
	// The front here has no TUN (none on the test host); what matters is the
	// two-engine lifecycle around it.
	err := Start(&StartOptions{CoreID: "xray", Config: xrayFreedom, StatsTagsJSON: `["proxy"]`, FrontConfig: singboxDirect}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if Status() != "xray" || frontSB == nil || !frontSB.Running() {
		t.Fatalf("status %q front %v", Status(), frontSB)
	}
	var cores []coreInfo
	_ = json.Unmarshal([]byte(ListCoresJSON()), &cores)
	for _, c := range cores {
		if c.Running != (c.ID == "xray") {
			t.Errorf("front shows up as a registered core: %s", ListCoresJSON())
		}
	}
	if _, err := URLTest("xray", "", url, 5000); err != nil {
		t.Errorf("url test through running xray: %v", err)
	}
	if err = Stop(); err != nil {
		t.Fatal(err)
	}
	if Status() != "" || frontSB != nil {
		t.Fatalf("after stop: %q %v", Status(), frontSB)
	}

	// A front that fails to start takes xray down with it.
	err = Start(&StartOptions{CoreID: "xray", Config: xrayFreedom, FrontConfig: `{"outbounds":[{"type":"nope"}]}`}, nil)
	if err == nil || !strings.HasPrefix(err.Error(), "tun front: ") {
		t.Fatalf("bad front: %v", err)
	}
	if Status() != "" || frontSB != nil {
		t.Errorf("partial start left %q running", Status())
	}
	if err = Start(&StartOptions{CoreID: "xray", Config: "{"}, nil); err == nil || Status() != "" {
		t.Errorf("bad xray config: %v, status %q", err, Status())
	}
	if err = Start(&StartOptions{Config: singboxDirect, StatsTagsJSON: "{"}, nil); err == nil {
		t.Error("bad stats tags accepted")
	}
}
