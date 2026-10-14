package render_test

import (
	"bytes"
	"context"
	"os"
	"strings"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	xcore "github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"

	"hproxy/node"
	"hproxy/render"
	"hproxy/render/singbox"
	"hproxy/render/xray"
)

var links = []string{
	"vmess://eyJ2IjoiMiIsInBzIjoidm1lc3MtbiIsImFkZCI6ImV4YW1wbGUuY29tIiwicG9ydCI6IjQ0MyIsImlkIjoiYjgzMWM0OWEtOTk3Ni00YjQ1LWI2NzUtNjM3ZTg1ZjI1YmYwIiwiYWlkIjoiMCIsIm5ldCI6IndzIiwiaG9zdCI6ImNkbi5leGFtcGxlLmNvbSIsInBhdGgiOiIvd3MiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJleGFtcGxlLmNvbSJ9",
	"vless://b831c49a-9976-4b45-b675-637e85f25bf0@1.2.3.4:443?security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed0ZHtgptVU&sid=6ba85179e30d4fc2&type=tcp&flow=xtls-rprx-vision#vless-reality",
	"trojan://password123@trojan.example.com:443?security=tls&sni=trojan.example.com&type=grpc&serviceName=grpc#trojan-grpc",
	"ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@ss.example.com:8388#ss-aead",
	"ss://2022-blake3-aes-128-gcm:MTIzNDU2Nzg5MDEyMzQ1Ng%3D%3D@ss.example.com:8388#ss-2022",
	"socks5://user:pass@127.0.0.1:1080#socks",
	"hy2://pw@hy2.example.com:443?sni=hy2.example.com&insecure=1&obfs=salamander&obfs-password=obfs#hy2",
	"tuic://b831c49a-9976-4b45-b675-637e85f25bf0:pw@tuic.example.com:443?congestion_control=bbr&alpn=h3&sni=tuic.example.com#tuic",
	"naive+https://user:pass@naive.example.com:443#naive",
}

func settings() render.Settings {
	var s render.Settings
	s.Inbound.Port = 2080
	s.Inbound.Sniff = render.SniffForRouting
	s.Routing.DNSRouting = true
	s.Routing.ProxyDomain = []string{"geosite:google", "full:example.org"}
	s.Routing.DirectDomain = []string{"geosite:cn", "domain:lan"}
	s.Routing.BlockDomain = []string{"keyword:ads"}
	s.Routing.DirectIP = []string{"geoip:cn", "10.0.0.0/8"}
	s.Mux.DefaultOn = true
	return s
}

func TestLinksRoundTrip(t *testing.T) {
	for _, l := range links {
		n, err := node.ParseLink(l)
		if err != nil {
			t.Fatalf("%s: %v", l, err)
		}
		back := n.ShareLink()
		n2, err := node.ParseLink(back)
		if err != nil {
			t.Fatalf("re-parse %s: %v", back, err)
		}
		if n2.Server != n.Server || n2.Port != n.Port || n2.Protocol != n.Protocol || n2.Name != n.Name {
			t.Errorf("round trip mismatch:\n %s\n %s", l, back)
		}
		h, err := node.ParseLink(n.HProxyLink())
		if err != nil || h.Server != n.Server {
			t.Errorf("hproxy link failed for %s: %v", l, err)
		}
	}
}

func TestImportClash(t *testing.T) {
	y := `
proxies:
  - {name: ss1, type: ss, server: 1.1.1.1, port: 443, cipher: aes-256-gcm, password: pw, plugin: obfs, plugin-opts: {mode: http, host: bing.com}}
  - name: vm1
    type: vmess
    server: v.example.com
    port: 443
    uuid: b831c49a-9976-4b45-b675-637e85f25bf0
    alterId: 0
    cipher: auto
    tls: true
    network: ws
    ws-opts: {path: /ws, headers: {Host: cdn.example.com}}
  - {name: hy, type: hysteria2, server: h.example.com, port: 443, password: pw, sni: h.example.com, up: "50 Mbps", down: "100 Mbps"}
`
	r := node.Import(y)
	if len(r.Errors) > 0 || len(r.Nodes) != 3 {
		t.Fatalf("import: %d nodes, errors %v", len(r.Nodes), r.Errors)
	}
	if r.Nodes[0].Plugin != "obfs-local;obfs=http;obfs-host=bing.com" {
		t.Errorf("plugin: %q", r.Nodes[0].Plugin)
	}
	if r.Nodes[1].Stream.Host != "cdn.example.com" || r.Nodes[1].Stream.Security != "tls" {
		t.Errorf("vmess ws: %+v", r.Nodes[1].Stream)
	}
	if r.Nodes[2].Hysteria2.UpMbps != 50 {
		t.Errorf("hy2 up: %d", r.Nodes[2].Hysteria2.UpMbps)
	}
}

func loadSingBox(t *testing.T, cfg []byte) {
	t.Helper()
	ctx := include.Context(context.Background())
	opts, err := json.UnmarshalExtendedContext[option.Options](ctx, cfg)
	if err != nil {
		t.Fatalf("sing-box parse: %v\n%s", err, cfg)
	}
	ctx = service.ContextWithDefaultRegistry(ctx)
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		t.Fatalf("sing-box new: %v\n%s", err, cfg)
	}
	_ = b.Close()
}

func loadXray(t *testing.T, cfg []byte) {
	t.Helper()
	if dir := os.Getenv("HPROXY_TEST_XRAY_ASSETS"); dir != "" {
		os.Setenv("XRAY_LOCATION_ASSET", dir)
	} else {
		t.Skip("set HPROXY_TEST_XRAY_ASSETS to a dir with geoip.dat and geosite.dat")
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

func TestRenderSingBox(t *testing.T) {
	r := singbox.New()
	for _, l := range links {
		n, _ := node.ParseLink(l)
		if !r.Supports(n.Protocol) {
			continue
		}
		for _, mode := range []render.Mode{render.ModeFull, render.ModeTest} {
			res, err := r.Render(render.Request{Chain: []*node.Node{n}, Settings: settings(), Mode: mode})
			if err != nil {
				t.Fatalf("%s: %v", l, err)
			}
			loadSingBox(t, res.Config)
			if res.StatsTags[0] != "proxy" {
				t.Errorf("stats tags %v", res.StatsTags)
			}
		}
	}
	// chain + tun
	a, _ := node.ParseLink(links[1])
	b, _ := node.ParseLink(links[2])
	s := settings()
	s.TUN.Enabled = true
	s.TUN.FakeDNS = true
	res, err := r.Render(render.Request{Chain: []*node.Node{a, b}, Settings: s, Mode: render.ModeFull})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(res.Config), `"detour": "hop-0"`) {
		t.Errorf("chain detour missing:\n%s", res.Config)
	}
	loadSingBox(t, res.Config)
}

func TestRenderXray(t *testing.T) {
	r := xray.New()
	for _, l := range links {
		n, _ := node.ParseLink(l)
		if !r.Supports(n.Protocol) {
			continue
		}
		if strings.HasPrefix(l, "ss://2022") {
			continue // xray's shadowsocks 2022 needs its own outbound settings
		}
		for _, mode := range []render.Mode{render.ModeFull, render.ModeTest} {
			res, err := r.Render(render.Request{Chain: []*node.Node{n}, Settings: settings(), Mode: mode})
			if err != nil {
				t.Fatalf("%s: %v", l, err)
			}
			loadXray(t, res.Config)
		}
	}
	a, _ := node.ParseLink(links[1])
	b, _ := node.ParseLink(links[2])
	res, err := r.Render(render.Request{Chain: []*node.Node{a, b}, Settings: settings(), Mode: render.ModeFull})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(res.Config), `"dialerProxy": "hop-0"`) {
		t.Errorf("chain dialerProxy missing:\n%s", res.Config)
	}
	loadXray(t, res.Config)
}
