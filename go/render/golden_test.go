package render_test

import (
	"bytes"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"sort"
	"testing"

	"duobaoproxy/node"
	"duobaoproxy/render"
	"duobaoproxy/render/singbox"
	"duobaoproxy/render/xray"
)

// The golden files pin the desktop output (Platform == ""): any change to
// them is a desktop behaviour change. Regenerate with
// go test ./render -run TestGolden -update
var update = flag.Bool("update", false, "rewrite render/testdata/golden")

// goldenCases are settings as the GUI stores them (data.json "settings").
var goldenCases = []struct {
	name     string
	settings string
	chain    bool // two hops instead of one
}{
	{name: "rules", settings: `{
		"inbound": {"port": 2080, "sniff": 1, "username": "u", "password": "p"},
		"apps": [
			{"name": "Chrome", "process": "chrome.exe", "outbound": "proxy"},
			{"name": "Tool", "process": "C:\\Tools\\tool.exe", "outbound": "bypass"},
			{"name": "Ads", "process": "ads.exe", "outbound": "block"}
		],
		"routing": {
			"proxy_domain": ["geosite:google", "full:example.org", "keyword:gpt"],
			"direct_domain": ["geosite:cn", "domain:lan", "regexp:^cn\\."],
			"block_domain": ["geosite:category-ads-all", "keyword:ads"],
			"proxy_ip": ["8.8.8.8/32"],
			"direct_ip": ["geoip:cn", "geoip:private", "10.0.0.0/8"],
			"block_ip": ["1.2.3.4/32"],
			"dns_routing": true, "remote_dns_strategy": "prefer_ipv4", "domain_strategy": "IPIfNonMatch"
		},
		"mux": {"default_on": true, "padding": true},
		"clash_api_port": 9090, "clash_api_secret": "s"
	}`},
	{name: "rules-tun", chain: true, settings: `{
		"inbound": {"port": 2080, "sniff": 2},
		"apps": [{"name": "Chrome", "process": "chrome.exe", "outbound": "bypass"}],
		"tun": {"enabled": true, "ipv6": true, "strict_route": true, "fake_dns": true,
			"rule_process": ["game.exe"], "rule_cidr": ["192.168.0.0/16"]},
		"routing": {"proxy_domain": ["geosite:google"], "direct_domain": ["geosite:cn"], "direct_ip": ["geoip:cn"], "dns_routing": true}
	}`},
	{name: "tun-white", settings: `{
		"tun": {"enabled": true, "stack": "system", "mtu": 1500, "rule_white": true, "rule_process": ["steam.exe"]},
		"routing": {"final": "bypass", "proxy_domain": ["full:a.example"]}
	}`},
	{name: "global", settings: `{"inbound": {"port": 7890, "address": "0.0.0.0"}, "routing": {"final": "proxy"}}`},
	{name: "direct", settings: `{"inbound": {"port": 7890}, "log_level": "warn", "routing": {"final": "bypass", "dns_final_out": "bypass", "underlying_dns": "223.5.5.5"}}`},
}

var goldenModes = []struct {
	name string
	mode render.Mode
}{{"full", render.ModeFull}, {"test", render.ModeTest}, {"export", render.ModeExport}}

func goldenChain(t *testing.T, two bool) []*node.Node {
	a, err := node.ParseLink(links[1]) // vless reality, IP server
	if err != nil {
		t.Fatal(err)
	}
	if !two {
		return []*node.Node{a}
	}
	b, err := node.ParseLink(links[2]) // trojan grpc, domain server
	if err != nil {
		t.Fatal(err)
	}
	return []*node.Node{a, b}
}

// canonical makes the output comparable: the sing-box renderer collects
// rule sets in a map, so only route.rule_set order varies between runs.
// Numbers keep their exact text and keys stay sorted, so for everything
// else this is the identity on MarshalIndent output.
func canonical(t *testing.T, cfg []byte) []byte {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(cfg))
	dec.UseNumber()
	var v map[string]any
	if err := dec.Decode(&v); err != nil {
		t.Fatalf("output is not JSON: %v\n%s", err, cfg)
	}
	if route, ok := v["route"].(map[string]any); ok {
		if sets, ok := route["rule_set"].([]any); ok {
			sort.Slice(sets, func(i, j int) bool {
				return sets[i].(map[string]any)["tag"].(string) < sets[j].(map[string]any)["tag"].(string)
			})
		}
	}
	out, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestGolden(t *testing.T) {
	engines := []struct {
		name string
		r    render.Renderer
	}{{"singbox", singbox.New()}, {"xray", xray.New()}}
	dir := filepath.Join("testdata", "golden")
	if *update {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	for _, e := range engines {
		for _, c := range goldenCases {
			var set render.Settings
			if err := json.Unmarshal([]byte(c.settings), &set); err != nil {
				t.Fatalf("%s: %v", c.name, err)
			}
			for _, m := range goldenModes {
				var got []byte
				res, err := e.r.Render(render.Request{Chain: goldenChain(t, c.chain), Settings: set, Mode: m.mode})
				if err != nil {
					got = []byte("error: " + err.Error() + "\n")
				} else {
					got = canonical(t, res.Config)
					tags, _ := json.Marshal(res.StatsTags)
					got = append(append(append(got, "\nstats_tags: "...), tags...), '\n')
				}
				file := filepath.Join(dir, e.name+"-"+c.name+"-"+m.name+".golden")
				if *update {
					if err := os.WriteFile(file, got, 0o644); err != nil {
						t.Fatal(err)
					}
					continue
				}
				want, err := os.ReadFile(file)
				if err != nil {
					t.Fatalf("%v (run with -update to create)", err)
				}
				if !bytes.Equal(got, want) {
					t.Errorf("%s differs from golden output:\n%s", file, got)
				}
			}
		}
	}
}
