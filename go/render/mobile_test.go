package render_test

import (
	"bytes"
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"

	xcore "github.com/xtls/xray-core/core"

	"duobaoproxy/node"
	"duobaoproxy/render"
	"duobaoproxy/render/singbox"
	"duobaoproxy/render/xray"
)

// The mobile configs are validated by constructing them for real: sing-box
// through include.Context + UnmarshalExtendedContext + box.New (loadSingBox;
// box.New does not open the TUN, so no platform interface is needed until
// Start), xray through core.LoadConfig + core.New as go/core/xray does.

const ownPkg = "com.duobao.proxy"

func mobileSettings(t *testing.T, platform string) render.Settings {
	t.Helper()
	var s render.Settings
	err := json.Unmarshal([]byte(`{
		"inbound": {"port": 2080, "username": "u", "password": "p"},
		"apps": [
			{"name": "Chrome", "process": "chrome.exe", "outbound": "proxy"},
			{"name": "Bank", "process": "com.bank.app", "outbound": "bypass", "platform": "android"},
			{"name": "Ads", "process": "com.ads.app", "outbound": "block", "platform": "android"},
			{"name": "Browser", "process": "org.mozilla.firefox", "outbound": "proxy", "platform": "android"}
		],
		"tun": {"enabled": true, "strict_route": true, "interface_name": "x",
			"rule_process": ["com.game.app", "com.duobao.proxy", " "], "rule_cidr": ["192.168.0.0/16"]},
		"routing": {
			"proxy_domain": ["geosite:google", "full:example.org"],
			"direct_domain": ["geosite:cn"], "direct_ip": ["geoip:cn", "geoip:private"],
			"block_domain": ["keyword:ads"], "dns_routing": true
		},
		"clash_api_port": 9090
	}`), &s)
	if err != nil {
		t.Fatal(err)
	}
	s.Platform, s.OwnPackage = platform, ownPkg
	return s
}

func decode(t *testing.T, cfg []byte) map[string]any {
	t.Helper()
	var v map[string]any
	if err := json.Unmarshal(cfg, &v); err != nil {
		t.Fatalf("%v\n%s", err, cfg)
	}
	return v
}

func inboundByType(cfg map[string]any, typ string) map[string]any {
	ins, _ := cfg["inbounds"].([]any)
	for _, in := range ins {
		if m := in.(map[string]any); m["type"] == typ {
			return m
		}
	}
	return nil
}

func routeRules(cfg map[string]any) []map[string]any {
	var out []map[string]any
	route, _ := cfg["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, r := range rules {
		out = append(out, r.(map[string]any))
	}
	return out
}

func hasRule(rules []map[string]any, key string, val any) bool {
	for _, r := range rules {
		if reflect.DeepEqual(r[key], val) {
			return true
		}
	}
	return false
}

func list(v ...any) []any { return v }

func render1(t *testing.T, r render.Renderer, s render.Settings, mode render.Mode) *render.Result {
	t.Helper()
	n, err := node.ParseLink(links[1])
	if err != nil {
		t.Fatal(err)
	}
	res, err := r.Render(render.Request{Chain: []*node.Node{n}, Settings: s, Mode: mode})
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func TestMobileSingBox(t *testing.T) {
	for _, platform := range []string{render.PlatformAndroid, render.PlatformIOS} {
		t.Run(platform, func(t *testing.T) {
			android := platform == render.PlatformAndroid
			res := render1(t, singbox.New(), mobileSettings(t, platform), render.ModeFull)
			loadSingBox(t, res.Config)
			cfg := decode(t, res.Config)
			s := string(res.Config)

			tun := inboundByType(cfg, "tun")
			if tun == nil {
				t.Fatalf("no tun inbound:\n%s", s)
			}
			if _, ok := tun["interface_name"]; ok {
				t.Error("mobile tun must not name the interface")
			}
			if tun["auto_route"] != true || tun["strict_route"] == true {
				t.Errorf("auto_route / strict_route: %v / %v", tun["auto_route"], tun["strict_route"])
			}
			if !reflect.DeepEqual(tun["address"], list("172.19.0.1/28", "fdfe:dcba:9876::1/126")) {
				t.Errorf("address %v", tun["address"])
			}
			wantMTU, wantStack := 9000.0, "gvisor"
			if !android {
				wantMTU, wantStack = 4064, "mixed"
			}
			if tun["mtu"] != wantMTU || tun["stack"] != wantStack {
				t.Errorf("mtu %v stack %v", tun["mtu"], tun["stack"])
			}
			if android {
				if !reflect.DeepEqual(tun["exclude_package"], list("com.game.app", ownPkg)) || tun["include_package"] != nil {
					t.Errorf("packages: include %v exclude %v", tun["include_package"], tun["exclude_package"])
				}
			} else if tun["exclude_package"] != nil || tun["include_package"] != nil {
				t.Error("iOS has no per-app VPN")
			}

			rules := routeRules(cfg)
			if rules[0]["action"] != "sniff" || !reflect.DeepEqual(rules[1]["protocol"], list("dns")) {
				t.Errorf("sniff + hijack-dns must lead: %v %v", rules[0], rules[1])
			}
			if !hasRule(rules, "ip_version", 6.0) {
				t.Error("IPv6 off: missing ip_version 6 reject")
			}
			if strings.Contains(s, "chrome.exe") || strings.Contains(s, "process_name") {
				t.Error("desktop app rows must be skipped on mobile")
			}
			if hasRule(rules, "process_name", list("com.game.app")) {
				t.Error("rule_process belongs in the VPN package list on mobile")
			}
			if !hasRule(rules, "ip_cidr", list("192.168.0.0/16")) {
				t.Error("rule_cidr dropped")
			}
			if android {
				for _, want := range []map[string]any{
					{"package_name": list("com.bank.app"), "outbound": "bypass"},
					{"package_name": list("com.ads.app"), "action": "reject"},
					{"package_name": list("org.mozilla.firefox"), "outbound": "proxy"},
				} {
					found := false
					for _, r := range rules {
						found = found || reflect.DeepEqual(r, want)
					}
					if !found {
						t.Errorf("missing app rule %v", want)
					}
				}
			} else if strings.Contains(s, "package_name") {
				t.Error("iOS must drop per-app rules")
			}

			if cfg["experimental"] != nil {
				t.Error("no clash api on mobile")
			}
			if mixed := inboundByType(cfg, "mixed"); (mixed != nil) != android {
				t.Errorf("mixed inbound present=%v on %s", mixed != nil, platform)
			}
			svc, _ := cfg["services"].([]any)
			if android && svc != nil || !android && !reflect.DeepEqual(svc, list(map[string]any{"type": "oom-killer"})) {
				t.Errorf("services %v", svc)
			}

			// Test instances: no inbounds, still loadable.
			test := render1(t, singbox.New(), mobileSettings(t, platform), render.ModeTest)
			loadSingBox(t, test.Config)
			if inboundByType(decode(t, test.Config), "tun") != nil {
				t.Error("test config with tun")
			}

			// Export: platform-only fields go.
			exp := render1(t, singbox.New(), mobileSettings(t, platform), render.ModeExport)
			loadSingBox(t, exp.Config)
			ecfg := decode(t, exp.Config)
			etun := inboundByType(ecfg, "tun")
			if etun["exclude_package"] != nil || etun["include_package"] != nil || ecfg["services"] != nil {
				t.Errorf("export keeps platform fields:\n%s", exp.Config)
			}
			if route := ecfg["route"].(map[string]any); route["auto_detect_interface"] != nil {
				t.Error("export keeps auto_detect_interface")
			}
		})
	}
}

func TestMobilePackages(t *testing.T) {
	cases := []struct {
		white            bool
		process          []string
		include, exclude []any
	}{
		{false, nil, nil, list(ownPkg)},
		{false, []string{"a.b", ownPkg, "a.b"}, nil, list("a.b", ownPkg)},
		{true, []string{"a.b", ownPkg}, list("a.b"), nil},
		{true, []string{ownPkg}, nil, list(ownPkg)}, // empty allow-list = every app; keep us out
	}
	for _, c := range cases {
		s := mobileSettings(t, render.PlatformAndroid)
		s.TUN.RuleWhite, s.TUN.RuleProcess, s.TUN.IPv6 = c.white, c.process, true
		res := render1(t, singbox.New(), s, render.ModeFull)
		cfg := decode(t, res.Config)
		tun := inboundByType(cfg, "tun")
		if inc, _ := tun["include_package"].([]any); !reflect.DeepEqual(inc, c.include) {
			t.Errorf("white=%v %v: include %v, want %v", c.white, c.process, inc, c.include)
		}
		if exc, _ := tun["exclude_package"].([]any); !reflect.DeepEqual(exc, c.exclude) {
			t.Errorf("white=%v %v: exclude %v, want %v", c.white, c.process, exc, c.exclude)
		}
		if hasRule(routeRules(cfg), "ip_version", 6.0) {
			t.Error("IPv6 on: no reject rule")
		}
		if !reflect.DeepEqual(tun["address"], list("172.19.0.1/28", "fdfe:dcba:9876::1/126")) {
			t.Errorf("address %v", tun["address"])
		}
		loadSingBox(t, res.Config)
	}
	// iOS keeps a user MTU below the cap.
	s := mobileSettings(t, render.PlatformIOS)
	s.TUN.MTU, s.TUN.Stack = 1400, "system"
	tun := inboundByType(decode(t, render1(t, singbox.New(), s, render.ModeFull).Config), "tun")
	if tun["mtu"] != 1400.0 || tun["stack"] != "system" {
		t.Errorf("ios mtu %v stack %v", tun["mtu"], tun["stack"])
	}
}

// Desktop ignores rows recorded on Android.
func TestDesktopSkipsAndroidApps(t *testing.T) {
	s := settings()
	s.Apps = []render.AppRule{
		{Process: "curl.exe", Outbound: "bypass"},
		{Process: "com.bank.app", Outbound: "bypass", Platform: render.PlatformAndroid},
	}
	res := render1(t, singbox.New(), s, render.ModeFull)
	if !strings.Contains(string(res.Config), "curl.exe") || strings.Contains(string(res.Config), "com.bank.app") {
		t.Errorf("desktop app rows wrong:\n%s", res.Config)
	}
}

// loadXrayConfig loads like go/core/xray (core.LoadConfig "json" + core.New).
// Geo assets come from DUOBAO_TEST_XRAY_ASSETS when set; configs without
// geosite/geoip codes load without them.
func loadXrayConfig(t *testing.T, cfg []byte) {
	t.Helper()
	if dir := os.Getenv("DUOBAO_TEST_XRAY_ASSETS"); dir != "" {
		os.Setenv("XRAY_LOCATION_ASSET", dir)
	} else if bytes.Contains(cfg, []byte("geosite:")) || bytes.Contains(cfg, []byte("geoip:")) {
		t.Skip("set DUOBAO_TEST_XRAY_ASSETS to a dir with geoip.dat and geosite.dat")
	}
	c, err := xcore.LoadConfig("json", bytes.NewReader(cfg))
	if err != nil {
		t.Fatalf("xray parse: %v\n%s", err, cfg)
	}
	inst, err := xcore.New(c)
	if err != nil {
		t.Fatalf("xray new: %v\n%s", err, cfg)
	}
	_ = inst.Close()
}

func TestXrayFront(t *testing.T) {
	for _, platform := range []string{render.PlatformAndroid, render.PlatformIOS} {
		t.Run(platform, func(t *testing.T) {
			android := platform == render.PlatformAndroid
			s := mobileSettings(t, platform)
			// Geo-free rules so the xray side loads without .dat files.
			s.Routing.ProxyDomain = []string{"full:example.org"}
			s.Routing.DirectDomain = []string{"domain:cn"}
			s.Routing.DirectIP = []string{"10.0.0.0/8"}
			s.Inbound.Sniff = render.SniffDisabled // forced on anyway
			s.Bridge = &render.Bridge{Port: 47011, User: "bu", Pass: "bp"}

			res := render1(t, xray.New(), s, render.ModeFull)
			loadXrayConfig(t, res.Config)
			x := decode(t, res.Config)
			ins := x["inbounds"].([]any)
			bridge := ins[0].(map[string]any)
			if bridge["tag"] != "mixed-in" || bridge["listen"] != "127.0.0.1" || bridge["port"] != 47011.0 {
				t.Errorf("bridge inbound %v", bridge)
			}
			bs := bridge["settings"].(map[string]any)
			if bs["udp"] != true || bs["auth"] != "password" ||
				!reflect.DeepEqual(bs["accounts"], list(map[string]any{"user": "bu", "pass": "bp"})) {
				t.Errorf("bridge settings %v", bs)
			}
			if sn := bridge["sniffing"].(map[string]any); sn["enabled"] != true || sn["routeOnly"] != true {
				t.Errorf("bridge sniffing %v", sn)
			}
			// Android keeps the user's port next to the bridge; iOS never has one.
			if wantIns := map[bool]int{true: 2, false: 1}[android]; len(ins) != wantIns {
				t.Errorf("%d inbounds, want %d", len(ins), wantIns)
			}
			for _, o := range x["outbounds"].([]any) {
				o := o.(map[string]any)
				if o["protocol"] == "freedom" && !reflect.DeepEqual(o["settings"], map[string]any{"domainStrategy": "UseIP"}) {
					t.Errorf("freedom %v without UseIP", o["tag"])
				}
			}
			if res.StatsTags[0] != "proxy" {
				t.Errorf("stats tags %v", res.StatsTags)
			}

			if res.FrontConfig == nil {
				t.Fatal("no front config")
			}
			loadSingBox(t, res.FrontConfig)
			f := decode(t, res.FrontConfig)
			tun := inboundByType(f, "tun")
			if tun["mtu"] != 1500.0 || tun["interface_name"] != nil || tun["auto_route"] != true {
				t.Errorf("front tun %v", tun)
			}
			if strings.Contains(string(res.FrontConfig), "sniff") || strings.Contains(string(res.FrontConfig), "hijack-dns") {
				t.Error("front must leave sniffing and DNS to xray")
			}
			out := f["outbounds"].([]any)[0].(map[string]any)
			want := map[string]any{"type": "socks", "tag": "xray", "server": "127.0.0.1", "server_port": 47011.0,
				"version": "5", "username": "bu", "password": "bp"}
			if !reflect.DeepEqual(out, want) {
				t.Errorf("front outbound %v", out)
			}
			route := f["route"].(map[string]any)
			rules := routeRules(f)
			if route["final"] != "xray" || !hasRule(rules, "ip_cidr", list("224.0.0.0/3", "ff00::/8")) {
				t.Errorf("front route %v", route)
			}
			if !hasRule(rules, "ip_version", 6.0) {
				t.Error("front: IPv6 off without reject")
			}
			if android {
				if !reflect.DeepEqual(tun["exclude_package"], list("com.game.app", ownPkg)) {
					t.Errorf("front exclude_package %v", tun["exclude_package"])
				}
				for _, want := range []map[string]any{
					{"package_name": list("com.bank.app"), "outbound": "direct"},
					{"package_name": list("com.ads.app"), "action": "reject"},
					{"package_name": list("org.mozilla.firefox"), "outbound": "xray"},
				} {
					found := false
					for _, r := range rules {
						found = found || reflect.DeepEqual(r, want)
					}
					if !found {
						t.Errorf("front: missing app rule %v", want)
					}
				}
			} else {
				if strings.Contains(string(res.FrontConfig), "package") {
					t.Error("iOS front with package rules")
				}
				if !reflect.DeepEqual(f["services"], list(map[string]any{"type": "oom-killer"})) {
					t.Errorf("iOS front services %v", f["services"])
				}
			}

			// Export and test drop the bridge; a full render needs one.
			for _, mode := range []render.Mode{render.ModeExport, render.ModeTest} {
				r := render1(t, xray.New(), s, mode)
				if r.FrontConfig != nil || strings.Contains(string(r.Config), "47011") {
					t.Errorf("mode %d keeps the bridge", mode)
				}
				loadXrayConfig(t, r.Config)
			}
			s.Bridge = nil
			n, _ := node.ParseLink(links[1])
			if _, err := xray.New().Render(render.Request{Chain: []*node.Node{n}, Settings: s}); err == nil {
				t.Error("front mode without bridge must fail")
			}
		})
	}
	if _, err := singbox.RenderXrayFront(settings(), 1080, "", ""); err == nil {
		t.Error("desktop has no xray front")
	}
}
