package mobile

import (
	"context"
	"os"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

// newBoxContext builds a fresh sing-box context for one engine instance
// (spec/singbox-mobile.md §4, libbox config.go baseContext): the file
// manager rooted at workDir/tempDir, the include registries (with `local`
// DNS swapped for the host resolver when SetLocalDNS was called), and a
// platform interface over h. A nil h gives the throw-away test variant:
// protector + local DNS, no TUN host. Never reuse the context across
// starts: box.New registers its managers into its service registry.
//
// The desktop core's include.Context(parent) keeps these registries, since
// box.Context only fills in missing ones (box.go:80-106).
func newBoxContext(h Host, workDir, tempDir string) context.Context {
	return newBoxContextWith(newPlatform(h), workDir, tempDir)
}

// newTestContext is the context for throw-away probe/URL-test instances.
func newTestContext(workDir, tempDir string) context.Context {
	return newBoxContext(nil, workDir, tempDir)
}

func newBoxContextWith(p *platform, workDir, tempDir string) context.Context {
	dnsRegistry := include.DNSTransportRegistry()
	if resolver := currentLocalDNS(); resolver != nil {
		registerLocalDNS(dnsRegistry, resolver)
	}
	ctx := filemanager.WithDefault(context.Background(), workDir, tempDir, os.Getuid(), os.Getgid())
	ctx = box.Context(ctx,
		include.InboundRegistry(),
		include.OutboundRegistry(),
		include.EndpointRegistry(),
		dnsRegistry,
		include.ServiceRegistry(),
		include.CertificateProviderRegistry(),
	)
	service.MustRegister[adapter.PlatformInterface](ctx, p)
	return ctx
}

// platformFromContext returns the platform registered by newBoxContext
// (e.g. to pass as box.Options.PlatformLogWriter), or nil.
func platformFromContext(ctx context.Context) *platform {
	p, _ := service.FromContext[adapter.PlatformInterface](ctx).(*platform)
	return p
}
