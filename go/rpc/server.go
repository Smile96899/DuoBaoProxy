// Package rpc exposes the registered cores to the GUI over gRPC.
package rpc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"google.golang.org/grpc"

	"hproxy/core"
	"hproxy/node"
	"hproxy/probe"
	"hproxy/render"
	"hproxy/render/singbox"
	"hproxy/render/xray"
	"hproxy/rpc/gen"
)

// renderers maps core ids to config renderers. IKEv2 has none: its node
// carries the profile JSON that Start consumes directly.
var renderers = map[string]render.Renderer{
	core.IDSingBox: singbox.New(),
	core.IDXray:    xray.New(),
}

var Debug bool

type Server struct {
	gen.UnimplementedLibcoreServiceServer

	mu      sync.Mutex
	running core.Core
	logFn   func(string)
}

func NewServer(logFn func(string)) *Server {
	if logFn == nil {
		logFn = func(string) {}
	}
	return &Server{logFn: logFn}
}

func errResp(err error) *gen.ErrorResp {
	if err != nil {
		return &gen.ErrorResp{Error: err.Error()}
	}
	return &gen.ErrorResp{}
}

func coreID(id string) string {
	if id == "" {
		return core.IDSingBox
	}
	return id
}

func (s *Server) Exit(context.Context, *gen.EmptyReq) (*gen.EmptyResp, error) {
	s.mu.Lock()
	if s.running != nil {
		_ = s.running.Stop()
	}
	s.mu.Unlock()
	os.Exit(0)
	return &gen.EmptyResp{}, nil
}

func (s *Server) ListCores(context.Context, *gen.EmptyReq) (*gen.ListCoresResp, error) {
	out := &gen.ListCoresResp{}
	for _, c := range core.List() {
		kind := gen.CoreKind_Proxy
		if c.Kind() == core.KindSystemVPN {
			kind = gen.CoreKind_SystemVPN
		}
		out.Cores = append(out.Cores, &gen.CoreInfo{
			Id:        c.ID(),
			Version:   c.Version(),
			Kind:      kind,
			Protocols: c.Protocols(),
			Running:   c.Running(),
		})
	}
	return out, nil
}

func (s *Server) Start(ctx context.Context, in *gen.LoadConfigReq) (*gen.ErrorResp, error) {
	if Debug {
		log.Println("启动内核：", coreID(in.CoreId), in.CoreConfig)
	}
	c, err := core.Get(coreID(in.CoreId))
	if err != nil {
		return errResp(err), nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running != nil {
		return errResp(fmt.Errorf("%s is already running; stop it first", s.running.ID())), nil
	}
	err = c.Start(context.Background(), core.StartOptions{
		Config:         []byte(in.CoreConfig),
		StatsOutbounds: in.StatsOutbounds,
		LogWriter:      s.logFn,
		AssetDir:       in.AssetDir,
	})
	if err != nil {
		return errResp(err), nil
	}
	s.running = c
	return errResp(nil), nil
}

func (s *Server) Stop(ctx context.Context, in *gen.StopReq) (*gen.ErrorResp, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running == nil {
		return errResp(nil), nil
	}
	if in.CoreId != "" && in.CoreId != s.running.ID() {
		return errResp(fmt.Errorf("%s is not running", in.CoreId)), nil
	}
	err := s.running.Stop()
	s.running = nil
	return errResp(err), nil
}

func (s *Server) Status(context.Context, *gen.EmptyReq) (*gen.StatusResp, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := &gen.StatusResp{}
	if s.running != nil {
		out.RunningCoreId = s.running.ID()
	}
	return out, nil
}

// dialerFor resolves the dialer a test should use: a throw-away instance
// when a config is supplied, otherwise the running core.
func (s *Server) dialerFor(ctx context.Context, cfg *gen.LoadConfigReq) (core.Dialer, func(), error) {
	if cfg != nil && cfg.CoreConfig != "" {
		c, err := core.Get(coreID(cfg.CoreId))
		if err != nil {
			return nil, nil, err
		}
		return c.Dialer(ctx, []byte(cfg.CoreConfig))
	}
	s.mu.Lock()
	c := s.running
	s.mu.Unlock()
	if c == nil {
		return nil, nil, core.ErrNotRunning
	}
	return c.Dialer(ctx, nil)
}

func (s *Server) Test(ctx context.Context, in *gen.TestReq) (*gen.TestResp, error) {
	out := &gen.TestResp{}
	timeout := time.Duration(in.Timeout) * time.Millisecond
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	switch in.Mode {
	case gen.TestMode_TcpPing:
		ms, err := probe.TCPPing(ctx, in.Address, timeout)
		out.Ms = ms
		if err != nil {
			out.Error = err.Error()
		}
	case gen.TestMode_UrlTest, gen.TestMode_FullTest:
		d, release, err := s.dialerFor(ctx, in.Config)
		if err != nil {
			out.Error = err.Error()
			return out, nil
		}
		defer release()
		if in.Mode == gen.TestMode_UrlTest {
			out.Ms, err = probe.URLTest(ctx, d, in.Url, timeout)
			if err != nil {
				out.Error = err.Error()
			}
			return out, nil
		}
		r := probe.FullTest(ctx, d, probe.FullTestOptions{
			URL:          in.Url,
			Timeout:      timeout,
			Latency:      in.FullLatency,
			UDPLatency:   in.FullUdpLatency,
			Speed:        in.FullSpeed,
			SpeedURL:     in.FullSpeedUrl,
			SpeedTimeout: time.Duration(in.FullSpeedTimeout) * time.Second,
			InOut:        in.FullInOut,
			InAddress:    in.InAddress,
		})
		out.Ms = r.LatencyMs
		out.FullReport = r.Report
	case gen.TestMode_Probe:
		return s.runProbe(ctx, in, timeout), nil
	default:
		out.Error = "unknown test mode"
	}
	return out, nil
}

// runProbe answers TestMode_Probe: ms for latency-like checks, JSON in full_report.
func (s *Server) runProbe(ctx context.Context, in *gen.TestReq, timeout time.Duration) *gen.TestResp {
	out := &gen.TestResp{}
	report := func(v any) {
		b, _ := json.Marshal(v)
		out.FullReport = string(b)
	}
	fail := func(err error) *gen.TestResp { out.Error = err.Error(); return out }

	// Checks that talk to the server directly.
	switch in.Probe {
	case "icmp":
		host, _, err := net.SplitHostPort(in.Address)
		if err != nil {
			host = in.Address
		}
		ms, err := probe.ICMPPing(ctx, host, timeout)
		if err != nil {
			return fail(err)
		}
		out.Ms = ms
		return out
	case "loss":
		st := probe.TCPPingStats(ctx, in.Address, int(in.Count), timeout)
		out.Ms = st.Avg
		report(st)
		return out
	}

	// The rest go through the node.
	d, release, err := s.dialerFor(ctx, in.Config)
	if err != nil {
		return fail(err)
	}
	defer release()
	switch in.Probe {
	case "udp":
		server, name := in.Address, in.Url
		if server == "" {
			server = "8.8.8.8:53"
		}
		if name == "" {
			name = "www.google.com"
		}
		ms, err := probe.UDPLatencyTo(ctx, d, server, name, timeout)
		if err != nil {
			return fail(err)
		}
		out.Ms = max(ms, 1)
	case "download":
		bps, err := probe.DownloadSpeed(ctx, d, in.Url, timeout)
		if err != nil {
			return fail(err)
		}
		report(map[string]float64{"bps": bps})
	case "upload":
		size := int64(in.Count)
		if size <= 0 {
			size = 10
		}
		bps, err := probe.UploadSpeed(ctx, d, in.Url, size*1024*1024, timeout)
		if err != nil {
			return fail(err)
		}
		report(map[string]float64{"bps": bps})
	case "geo":
		g, err := probe.ExitGeo(ctx, d, in.Url, timeout)
		if err != nil {
			return fail(err)
		}
		report(g)
	case "media":
		report(probe.MediaUnlock(ctx, d, timeout, in.Url))
	case "ai":
		report(probe.AIUnlock(ctx, d, timeout, in.Url))
	case "dnsleak":
		r, err := probe.DNSLeakTest(ctx, d, timeout)
		if err != nil {
			return fail(err)
		}
		report(r)
	default:
		return fail(fmt.Errorf("unknown probe %q", in.Probe))
	}
	return out
}

func (s *Server) QueryStats(ctx context.Context, in *gen.QueryStatsReq) (*gen.QueryStatsResp, error) {
	out := &gen.QueryStatsResp{}
	s.mu.Lock()
	c := s.running
	s.mu.Unlock()
	if c == nil {
		return out, nil
	}
	t, err := c.Stats(in.Tag)
	if err != nil {
		return out, nil
	}
	if in.Direct == "downlink" {
		out.Traffic = t.Downlink
	} else {
		out.Traffic = t.Uplink
	}
	return out, nil
}

func (s *Server) ImportNodes(ctx context.Context, in *gen.ImportNodesReq) (*gen.ImportNodesResp, error) {
	r := node.Import(in.Text)
	out := &gen.ImportNodesResp{Errors: r.Errors}
	for _, n := range r.Nodes {
		b, err := json.Marshal(n)
		if err != nil {
			out.Errors = append(out.Errors, err.Error())
			continue
		}
		out.NodesJson = append(out.NodesJson, string(b))
	}
	return out, nil
}

func (s *Server) ShareLink(ctx context.Context, in *gen.ShareLinkReq) (*gen.ShareLinkResp, error) {
	var n node.Node
	if err := json.Unmarshal([]byte(in.NodeJson), &n); err != nil {
		return &gen.ShareLinkResp{Error: err.Error()}, nil
	}
	switch {
	case in.HproxyFormat:
		return &gen.ShareLinkResp{Link: n.HProxyLink()}, nil
	case in.V2RaynVmess && n.Protocol == node.VMess:
		return &gen.ShareLinkResp{Link: n.VMessLinkV2RayN()}, nil
	}
	return &gen.ShareLinkResp{Link: n.ShareLink()}, nil
}

func (s *Server) RenderConfig(ctx context.Context, in *gen.RenderConfigReq) (*gen.RenderConfigResp, error) {
	id := coreID(in.CoreId)
	var chain []*node.Node
	for _, raw := range in.ChainJson {
		var n node.Node
		if err := json.Unmarshal([]byte(raw), &n); err != nil {
			return &gen.RenderConfigResp{Error: "node: " + err.Error()}, nil
		}
		chain = append(chain, &n)
	}
	if len(chain) == 0 {
		return &gen.RenderConfigResp{Error: "empty chain"}, nil
	}
	if id == core.IDIKEv2 {
		// The IKEv2 profile is the config; chains make no sense here.
		n := chain[len(chain)-1]
		if n.Protocol != node.IKEv2 || len(n.IKEv2) == 0 {
			return &gen.RenderConfigResp{Error: "ikev2 core needs an ikev2 node"}, nil
		}
		return &gen.RenderConfigResp{Config: string(n.IKEv2)}, nil
	}
	r, ok := renderers[id]
	if !ok {
		return &gen.RenderConfigResp{Error: core.ErrUnknownCore.Error()}, nil
	}
	var set render.Settings
	if in.SettingsJson != "" {
		if err := json.Unmarshal([]byte(in.SettingsJson), &set); err != nil {
			return &gen.RenderConfigResp{Error: "settings: " + err.Error()}, nil
		}
	}
	res, err := r.Render(render.Request{Chain: chain, Settings: set, Mode: render.Mode(in.Mode)})
	if err != nil {
		return &gen.RenderConfigResp{Error: err.Error()}, nil
	}
	return &gen.RenderConfigResp{Config: string(res.Config), StatsTags: res.StatsTags}, nil
}

// Run parses the command line (after the sub-command), watches the parent
// process and serves gRPC on 127.0.0.1:<port> until the parent dies.
func Run(args []string, server gen.LibcoreServiceServer) {
	fs := flag.NewFlagSet("h_core", flag.ExitOnError)
	token := fs.String("token", "", "shared secret; read from stdin when empty")
	port := fs.Int("port", 19810, "listen port on 127.0.0.1")
	debug := fs.Bool("debug", false, "log every request")
	_ = fs.Parse(args)
	Debug = *debug

	go watchParent()

	if *token == "" {
		_, _ = os.Stderr.WriteString("Please set a token: ")
		sc := bufio.NewScanner(os.Stdin)
		if sc.Scan() {
			*token = strings.TrimSpace(sc.Text())
		}
	}
	if *token == "" {
		fmt.Println("缺少访问令牌（-token）")
		os.Exit(1)
	}
	_, _ = os.Stderr.WriteString("已设置访问令牌\n")

	lis, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(*port))
	if err != nil {
		log.Fatalf("监听失败：%v", err)
	}
	a := authenticator{token: *token}
	g := grpc.NewServer(grpc.UnaryInterceptor(a.unary), grpc.StreamInterceptor(a.stream))
	gen.RegisterLibcoreServiceServer(g, server)
	log.Printf("h.proxy 核心 gRPC 服务监听于 %v\n", lis.Addr())
	if err := g.Serve(lis); err != nil && !errors.Is(err, grpc.ErrServerStopped) {
		log.Fatalf("服务启动失败：%v", err)
	}
}

func watchParent() {
	parent, err := os.FindProcess(os.Getppid())
	if err != nil {
		log.Fatalln("find parent:", err)
	}
	if runtime.GOOS == "windows" {
		state, err := parent.Wait()
		log.Fatalln("parent exited:", state, err)
	}
	for {
		time.Sleep(10 * time.Second)
		if err := parent.Signal(syscall.Signal(0)); err != nil {
			log.Fatalln("parent exited:", err)
		}
	}
}
