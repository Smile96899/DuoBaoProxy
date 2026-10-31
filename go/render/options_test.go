package render_test

import (
	"encoding/json"
	"reflect"
	"testing"

	"duobaoproxy/render"
	"duobaoproxy/render/singbox"
	"duobaoproxy/render/xray"
)

func TestDNSList(t *testing.T) {
	got := render.DNSList("223.5.5.5\nhttps://doh.pub/dns-query, 119.29.29.29  tls://1.1.1.1\n223.5.5.5", true)
	want := []string{"https://doh.pub/dns-query", "tls://1.1.1.1", "223.5.5.5", "119.29.29.29"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("doh first: %v", got)
	}
	if got := render.DNSList("223.5.5.5, https://doh.pub/dns-query", false); got[0] != "223.5.5.5" || len(got) != 2 {
		t.Fatalf("plain order: %v", got)
	}
	if render.FirstDNS("  ", true) != "" {
		t.Fatal("empty list")
	}
}

func TestMobileDNSOptionsSingBox(t *testing.T) {
	s := mobileSettings(t, render.PlatformAndroid)
	s.Routing.RemoteDNS = "8.8.8.8\nhttps://dns.google/dns-query"
	s.Routing.DirectDNS = "223.5.5.5\n119.29.29.29"
	s.Routing.DoHFirst = true
	s.TUN.LooseDNSHijack = true
	s.TCPFastOpen = true
	res := render1(t, singbox.New(), s, render.ModeFull)
	loadSingBox(t, res.Config)
	cfg := decode(t, res.Config)
	servers := cfg["dns"].(map[string]any)["servers"].([]any)
	if r := servers[0].(map[string]any); r["type"] != "https" || r["server"] != "dns.google" {
		t.Fatalf("remote: %v", r)
	}
	if d := servers[1].(map[string]any); d["server"] != "223.5.5.5" {
		t.Fatalf("direct: %v", d)
	}
	rules := routeRules(cfg)
	if !hasRule(rules, "ip_cidr", list("172.19.0.2/32", "fdfe:dcba:9876::2/128")) || hasRule(rules, "protocol", list("dns")) {
		t.Fatalf("loose hijack: %v", rules)
	}
	if out := cfg["outbounds"].([]any)[0].(map[string]any); out["tcp_fast_open"] != true {
		t.Fatalf("tfo: %v", out)
	}
}

func TestMobileDNSOptionsXray(t *testing.T) {
	s := mobileSettings(t, render.PlatformAndroid)
	s.Routing.RemoteDNS = "8.8.8.8\nhttps://dns.google/dns-query"
	s.Routing.DirectDNS = "223.5.5.5\n119.29.29.29"
	s.Routing.DoHFirst = true
	s.TUN.LooseDNSHijack = true
	s.TCPFastOpen = true
	s.Bridge = &render.Bridge{Port: 30000, User: "a", Pass: "b"}
	res := render1(t, xray.New(), s, render.ModeFull)
	loadXrayConfig(t, res.Config)
	cfg := decode(t, res.Config)
	var addrs []string
	for _, sv := range cfg["dns"].(map[string]any)["servers"].([]any) {
		if o, ok := sv.(map[string]any); ok {
			addrs = append(addrs, o["address"].(string))
		}
	}
	want := []string{"https://dns.google/dns-query", "8.8.8.8", "223.5.5.5", "119.29.29.29"}
	if !reflect.DeepEqual(addrs, want) {
		t.Fatalf("servers: %v", addrs)
	}
	routing := cfg["routing"].(map[string]any)["rules"].([]any)
	if ip := routing[0].(map[string]any)["ip"]; !reflect.DeepEqual(ip, list("172.19.0.2", "fdfe:dcba:9876::2")) {
		t.Fatalf("loose hijack: %v", routing[0])
	}
	b, _ := json.Marshal(cfg["outbounds"].([]any)[0])
	var out struct {
		StreamSettings struct {
			Sockopt struct {
				TCPFastOpen bool `json:"tcpFastOpen"`
			} `json:"sockopt"`
		} `json:"streamSettings"`
	}
	_ = json.Unmarshal(b, &out)
	if !out.StreamSettings.Sockopt.TCPFastOpen {
		t.Fatalf("tfo: %s", b)
	}
}
