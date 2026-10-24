package api

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"duobaoproxy/core"
	"duobaoproxy/core/singbox"
	"duobaoproxy/node"
	"duobaoproxy/render"
)

func TestImportNodes(t *testing.T) {
	r := ImportNodes("socks5://127.0.0.1:1080#a\n" +
		"trojan://secret@example.com:443?sni=example.com#b\n" +
		"vmess://garbage")
	if len(r.Nodes) != 2 || len(r.Errors) != 1 {
		t.Fatalf("import: %+v", r)
	}
	var n node.Node
	if err := json.Unmarshal([]byte(r.Nodes[1]), &n); err != nil || n.Protocol != node.Trojan {
		t.Fatalf("node %s: %v", r.Nodes[1], err)
	}
	link, err := ShareLink(r.Nodes[0], false)
	if err != nil || !strings.HasPrefix(link, "socks") {
		t.Fatalf("share: %q %v", link, err)
	}
}

func TestShareLinkErrors(t *testing.T) {
	_, err := ShareLink(`{"protocol":"wireguard"}`, false)
	if err == nil || err.Error() != "wireguard 没有通用的分享链接格式" {
		t.Fatalf("wireguard: %v", err)
	}
	if _, err := ShareLink("{", false); err == nil {
		t.Fatal("bad json accepted")
	}
}

func TestRenderConfigErrors(t *testing.T) {
	const n = `{"protocol":"socks","server":"127.0.0.1","port":1080}`
	for _, tc := range []struct {
		core     string
		chain    []string
		settings string
		want     string
	}{
		{"", nil, "", "empty chain"},
		{"", []string{"{"}, "", "node: "},
		{"", []string{n}, "{", "settings: "},
		{"nope", []string{n}, "", core.ErrUnknownCore.Error()},
	} {
		_, err := RenderConfig(tc.core, tc.chain, tc.settings, render.ModeFull)
		if err == nil || !strings.HasPrefix(err.Error(), tc.want) {
			t.Errorf("%+v: got %v", tc, err)
		}
	}
	// "" means sing-box.
	r, err := RenderConfig("", []string{n}, "", render.ModeTest)
	if err != nil || r.Config == "" || len(r.StatsTags) == 0 || r.StatsTags[0] != "proxy" {
		t.Fatalf("render: %+v %v", r, err)
	}
}

func TestIdle(t *testing.T) {
	var s Service
	if err := s.Stop(""); err != nil {
		t.Fatalf("stop: %v", err)
	}
	if err := s.Stop(core.IDXray); err != nil {
		t.Fatalf("stop xray: %v", err)
	}
	if id := s.Status(); id != "" {
		t.Fatalf("status: %q", id)
	}
	if tr := s.QueryStats("proxy"); tr != (core.Traffic{}) {
		t.Fatalf("stats: %+v", tr)
	}
	_, err := s.Test(context.Background(), TestRequest{Mode: TestURLTest, URL: "http://cp.cloudflare.com/"})
	if !errors.Is(err, core.ErrNotRunning) {
		t.Fatalf("urltest: %v", err)
	}
	if _, err := s.Test(context.Background(), TestRequest{Mode: 99}); err == nil || err.Error() != "unknown test mode" {
		t.Fatalf("mode: %v", err)
	}
}

func TestProbeUnknown(t *testing.T) {
	core.Register(singbox.New())
	var s Service
	r, err := s.Test(context.Background(), TestRequest{
		Mode:   TestProbe,
		Probe:  "nope",
		Config: `{"log": {"level": "warn"}, "outbounds": [{"type": "direct", "tag": "proxy"}]}`,
	})
	if err == nil || err.Error() != `unknown probe "nope"` || r != (TestResult{}) {
		t.Fatalf("probe: %+v %v", r, err)
	}
}
