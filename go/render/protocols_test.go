package render_test

import (
	"encoding/json"
	"testing"

	"duobaoproxy/node"
	"duobaoproxy/render"
	"duobaoproxy/render/singbox"
	"duobaoproxy/render/xray"
)

// Nodes without a share-link format: the rendered configs must load into the real cores.
var jsonNodes = []string{
	`{"protocol":"wireguard","server":"1.2.3.4","port":51820,"wireguard":{"private_key":"MLTh4UnH2hcuP9XLaMEaMIh3hUrC9tLupK0iCGEW7V4=","peer_public_key":"g53lk6LHjfalgwNSWFUSoMr0iQPfGYxTq3RzACWfQzs=","pre_shared_key":"iNluqUVumeg6vSdU+Y8AhDCif+EvbsHMmF8vw8F9ed4=","local_address":"10.66.0.2,fd00::2","reserved":"1,2,3","mtu":1280}}`,
	`{"protocol":"hysteria","server":"1.2.3.4","port":443,"hysteria":{"auth":"pw","up_mbps":50,"down_mbps":100},"quic_tls":{"sni":"a.b","insecure":true}}`,
	`{"protocol":"shadowtls","server":"1.2.3.4","port":443,"password":"tlspw","method":"aes-256-gcm","shadowtls":{"version":3,"ss_password":"sspw"},"stream":{"sni":"www.microsoft.com","utls":"chrome"}}`,
}

func TestRenderNewProtocols(t *testing.T) {
	for _, js := range jsonNodes {
		var n node.Node
		if err := json.Unmarshal([]byte(js), &n); err != nil {
			t.Fatal(err)
		}
		for _, mode := range []render.Mode{render.ModeFull, render.ModeTest} {
			res, err := singbox.New().Render(render.Request{Chain: []*node.Node{&n}, Settings: settings(), Mode: mode})
			if err != nil {
				t.Fatalf("sing-box %s: %v", n.Protocol, err)
			}
			var cfg map[string]any
			_ = json.Unmarshal(res.Config, &cfg)
			if n.Protocol == node.WireGuard {
				eps, _ := cfg["endpoints"].([]any)
				if len(eps) != 1 {
					t.Fatalf("wireguard must render as one endpoint, got %v", cfg["endpoints"])
				}
				addr := eps[0].(map[string]any)["address"].([]any)
				if addr[0] != "10.66.0.2/32" || addr[1] != "fd00::2/128" {
					t.Fatalf("bare addresses not normalised: %v", addr)
				}
			}
			if n.Protocol == node.ShadowTLS {
				var tags []string
				for _, o := range cfg["outbounds"].([]any) {
					tags = append(tags, o.(map[string]any)["tag"].(string))
				}
				if tags[0] != "proxy-stls" || tags[1] != "proxy" {
					t.Fatalf("shadowtls outbounds %v", tags)
				}
			}
			loadSingBox(t, res.Config)
		}
	}
	// WireGuard on xray too.
	var wg node.Node
	_ = json.Unmarshal([]byte(jsonNodes[0]), &wg)
	res, err := xray.New().Render(render.Request{Chain: []*node.Node{&wg}, Settings: settings(), Mode: render.ModeFull})
	if err != nil {
		t.Fatal(err)
	}
	loadXray(t, res.Config)
}

// A two-hop chain through a shadowtls exit keeps the detour order intact.
func TestShadowTLSChain(t *testing.T) {
	first, _ := node.ParseLink("socks5://u:p@9.9.9.9:1080#hop")
	var exit node.Node
	_ = json.Unmarshal([]byte(jsonNodes[2]), &exit)
	res, err := singbox.New().Render(render.Request{Chain: []*node.Node{first, &exit}, Settings: settings(), Mode: render.ModeFull})
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]any
	_ = json.Unmarshal(res.Config, &cfg)
	detour := map[string]string{}
	for _, o := range cfg["outbounds"].([]any) {
		m := o.(map[string]any)
		d, _ := m["detour"].(string)
		detour[m["tag"].(string)] = d
	}
	if detour["proxy"] != "proxy-stls" || detour["proxy-stls"] != "hop-0" {
		t.Fatalf("detours %v", detour)
	}
	loadSingBox(t, res.Config)
}
