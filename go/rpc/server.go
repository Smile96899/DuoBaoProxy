// Package rpc exposes go/api to the GUI over gRPC.
package rpc

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"google.golang.org/grpc"

	"duobaoproxy/api"
	"duobaoproxy/render"
	"duobaoproxy/rpc/gen"
)

var Debug bool

// Server adapts api.Service to the gRPC service: it converts between the
// protobuf messages and the plain api types, and reports api errors in the
// response body (gRPC errors stay nil).
type Server struct {
	gen.UnimplementedLibcoreServiceServer

	svc   api.Service
	logFn func(string)
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

func errString(err error) string {
	if err != nil {
		return err.Error()
	}
	return ""
}

func (s *Server) Exit(context.Context, *gen.EmptyReq) (*gen.EmptyResp, error) {
	_ = s.svc.Stop("")
	os.Exit(0)
	return &gen.EmptyResp{}, nil
}

func (s *Server) ListCores(context.Context, *gen.EmptyReq) (*gen.ListCoresResp, error) {
	out := &gen.ListCoresResp{}
	for _, c := range api.ListCores() {
		out.Cores = append(out.Cores, &gen.CoreInfo{
			Id:        c.ID,
			Version:   c.Version,
			Protocols: c.Protocols,
			Running:   c.Running,
		})
	}
	return out, nil
}

func (s *Server) Start(ctx context.Context, in *gen.LoadConfigReq) (*gen.ErrorResp, error) {
	if Debug {
		log.Println("启动内核：", api.CoreID(in.CoreId), in.CoreConfig)
	}
	// The core outlives this request, so it must not inherit ctx.
	return errResp(s.svc.Start(context.Background(), api.StartOptions{
		CoreID:         in.CoreId,
		Config:         in.CoreConfig,
		StatsOutbounds: in.StatsOutbounds,
		LogWriter:      s.logFn,
		AssetDir:       in.AssetDir,
	})), nil
}

func (s *Server) Stop(ctx context.Context, in *gen.StopReq) (*gen.ErrorResp, error) {
	return errResp(s.svc.Stop(in.CoreId)), nil
}

func (s *Server) Status(context.Context, *gen.EmptyReq) (*gen.StatusResp, error) {
	return &gen.StatusResp{RunningCoreId: s.svc.Status()}, nil
}

func (s *Server) Test(ctx context.Context, in *gen.TestReq) (*gen.TestResp, error) {
	req := api.TestRequest{
		Mode:             api.TestMode(in.Mode),
		Timeout:          time.Duration(in.Timeout) * time.Millisecond,
		Address:          in.Address,
		URL:              in.Url,
		InAddress:        in.InAddress,
		FullLatency:      in.FullLatency,
		FullUDPLatency:   in.FullUdpLatency,
		FullSpeed:        in.FullSpeed,
		FullSpeedURL:     in.FullSpeedUrl,
		FullSpeedTimeout: time.Duration(in.FullSpeedTimeout) * time.Second,
		FullInOut:        in.FullInOut,
		Probe:            in.Probe,
		Count:            int(in.Count),
	}
	if in.Config != nil {
		req.CoreID, req.Config = in.Config.CoreId, in.Config.CoreConfig
	}
	r, err := s.svc.Test(ctx, req)
	return &gen.TestResp{Ms: r.Ms, FullReport: r.Report, Error: errString(err)}, nil
}

func (s *Server) QueryStats(ctx context.Context, in *gen.QueryStatsReq) (*gen.QueryStatsResp, error) {
	t := s.svc.QueryStats(in.Tag)
	if in.Direct == "downlink" {
		return &gen.QueryStatsResp{Traffic: t.Downlink}, nil
	}
	return &gen.QueryStatsResp{Traffic: t.Uplink}, nil
}

func (s *Server) ImportNodes(ctx context.Context, in *gen.ImportNodesReq) (*gen.ImportNodesResp, error) {
	r := api.ImportNodes(in.Text)
	return &gen.ImportNodesResp{NodesJson: r.Nodes, Errors: r.Errors}, nil
}

func (s *Server) ShareLink(ctx context.Context, in *gen.ShareLinkReq) (*gen.ShareLinkResp, error) {
	link, err := api.ShareLink(in.NodeJson, in.V2RaynVmess)
	return &gen.ShareLinkResp{Link: link, Error: errString(err)}, nil
}

func (s *Server) RenderConfig(ctx context.Context, in *gen.RenderConfigReq) (*gen.RenderConfigResp, error) {
	r, err := api.RenderConfig(in.CoreId, in.ChainJson, in.SettingsJson, render.Mode(in.Mode))
	if err != nil {
		return &gen.RenderConfigResp{Error: err.Error()}, nil
	}
	return &gen.RenderConfigResp{Config: r.Config, StatsTags: r.StatsTags}, nil
}

// Run parses the command line (after the sub-command), watches the parent
// process and serves gRPC on 127.0.0.1:<port> until the parent dies.
func Run(args []string, server gen.LibcoreServiceServer) {
	fs := flag.NewFlagSet("DuoBaoProxyCore", flag.ExitOnError)
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
	log.Printf("多宝代理核心 gRPC 服务监听于 %v\n", lis.Addr())
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
