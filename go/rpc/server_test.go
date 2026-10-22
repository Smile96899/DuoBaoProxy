package rpc

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"testing"
	"time"

	"duobaoproxy/core"
	"duobaoproxy/core/singbox"
	"duobaoproxy/core/xray"
	"duobaoproxy/rpc/gen"
)

const singboxCfg = `{
  "log": {"level": "warn"},
  "inbounds": [{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 17890}],
  "outbounds": [{"type": "direct", "tag": "proxy"}]
}`

// Throw-away test instances carry no inbounds, like the GUI generates them.
const singboxTempCfg = `{"log": {"level": "warn"}, "outbounds": [{"type": "direct", "tag": "proxy"}]}`
const xrayTempCfg = `{"log": {"loglevel": "warning"}, "outbounds": [{"tag": "proxy", "protocol": "freedom"}]}`

const xrayCfg = `{
  "log": {"loglevel": "warning"},
  "inbounds": [{"tag": "mixed-in", "protocol": "socks", "listen": "127.0.0.1", "port": 17890}],
  "outbounds": [{"tag": "proxy", "protocol": "freedom"}]
}`

// viaInbound sends one request through the local socks inbound so that the
// router (and therefore the stats tracker) sees the connection.
func viaInbound(t *testing.T) {
	t.Helper()
	proxyURL, _ := url.Parse("socks5://127.0.0.1:17890")
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}, Timeout: 8 * time.Second}
	resp, err := client.Get("http://cp.cloudflare.com/")
	if err != nil {
		t.Logf("via inbound: %v", err)
		return
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
}

func TestCoresRoundTrip(t *testing.T) {
	core.Register(singbox.New())
	core.Register(xray.New())
	s := NewServer(func(line string) { t.Log(line) })
	ctx := context.Background()

	cores, _ := s.ListCores(ctx, &gen.EmptyReq{})
	if len(cores.Cores) != 2 {
		t.Fatalf("want 2 cores, got %d", len(cores.Cores))
	}

	for _, tc := range []struct{ id, cfg, tmp string }{{core.IDSingBox, singboxCfg, singboxTempCfg}, {core.IDXray, xrayCfg, xrayTempCfg}} {
		t.Run(tc.id, func(t *testing.T) {
			r, _ := s.Start(ctx, &gen.LoadConfigReq{CoreId: tc.id, CoreConfig: tc.cfg, StatsOutbounds: []string{"proxy"}})
			if r.Error != "" {
				t.Fatalf("start: %s", r.Error)
			}
			st, _ := s.Status(ctx, &gen.EmptyReq{})
			if st.RunningCoreId != tc.id {
				t.Fatalf("status: %q", st.RunningCoreId)
			}
			// Starting a second core while one runs must be refused.
			r, _ = s.Start(ctx, &gen.LoadConfigReq{CoreId: tc.id, CoreConfig: tc.cfg})
			if r.Error == "" {
				t.Fatal("second start should fail")
			}
			tr, _ := s.Test(ctx, &gen.TestReq{Mode: gen.TestMode_UrlTest, Url: "http://cp.cloudflare.com/", Timeout: 8000})
			t.Logf("urltest via running %s: %dms err=%q", tc.id, tr.Ms, tr.Error)
			viaInbound(t)
			qs, _ := s.QueryStats(ctx, &gen.QueryStatsReq{Tag: "proxy", Direct: "downlink"})
			t.Logf("stats downlink: %d", qs.Traffic)
			if tr.Error == "" && qs.Traffic == 0 {
				t.Errorf("stats counter did not move")
			}
			// Throw-away instance from config.
			tr, _ = s.Test(ctx, &gen.TestReq{Mode: gen.TestMode_UrlTest, Url: "http://cp.cloudflare.com/", Timeout: 8000,
				Config: &gen.LoadConfigReq{CoreId: tc.id, CoreConfig: tc.tmp}})
			t.Logf("urltest via temp %s: %dms err=%q", tc.id, tr.Ms, tr.Error)
			if tr.Error != "" {
				t.Errorf("temp instance test failed: %s", tr.Error)
			}
			r, _ = s.Stop(ctx, &gen.StopReq{})
			if r.Error != "" {
				t.Fatalf("stop: %s", r.Error)
			}
			st, _ = s.Status(ctx, &gen.EmptyReq{})
			if st.RunningCoreId != "" {
				t.Fatalf("still running: %q", st.RunningCoreId)
			}
		})
	}
	tr, _ := s.Test(ctx, &gen.TestReq{Mode: gen.TestMode_TcpPing, Address: "1.1.1.1:443", Timeout: 3000})
	t.Logf("tcpping: %dms err=%q", tr.Ms, tr.Error)
}

func TestImportRenderStart(t *testing.T) {
	core.Register(singbox.New())
	core.Register(xray.New())
	s := NewServer(nil)
	ctx := context.Background()

	imp, _ := s.ImportNodes(ctx, &gen.ImportNodesReq{Text: "socks5://127.0.0.1:1#direct-ish\nvmess://garbage"})
	if len(imp.NodesJson) != 1 || len(imp.Errors) != 1 {
		t.Fatalf("import: %+v", imp)
	}
	link, _ := s.ShareLink(ctx, &gen.ShareLinkReq{NodeJson: imp.NodesJson[0]})
	if link.Error != "" || link.Link == "" {
		t.Fatalf("share: %+v", link)
	}
	for _, id := range []string{core.IDSingBox, core.IDXray} {
		r, _ := s.RenderConfig(ctx, &gen.RenderConfigReq{CoreId: id, ChainJson: imp.NodesJson, SettingsJson: `{"inbound":{"port":17891}}`})
		if r.Error != "" {
			t.Fatalf("%s render: %s", id, r.Error)
		}
		st, _ := s.Start(ctx, &gen.LoadConfigReq{CoreId: id, CoreConfig: r.Config, StatsOutbounds: r.StatsTags})
		if st.Error != "" {
			t.Fatalf("%s start: %s\n%s", id, st.Error, r.Config)
		}
		_, _ = s.Stop(ctx, &gen.StopReq{})
	}
}
