package mobile

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

// localDNSTransport replaces sing-box's `type: local` DNS transport with the
// host resolver (raw mode of libbox's platformTransport, libbox dns.go). On
// Android the stock one reads the missing /etc/resolv.conf and falls back to
// 127.0.0.1:53 (spec/critic.md B3).
type localDNSTransport struct {
	dns.TransportAdapter
	resolver       LocalDNS
	preferred      *local.PreferredDomainResolver
	networkManager adapter.NetworkManager
}

var (
	_ adapter.DNSTransport                    = (*localDNSTransport)(nil)
	_ adapter.DNSTransportWithPreferredDomain = (*localDNSTransport)(nil)
	_ adapter.DNSTransportWithEnvironment     = (*localDNSTransport)(nil)
)

// registerLocalDNS overrides the "local" constructor in registry (a fresh
// include.DNSTransportRegistry(); register() overwrites by type,
// dns/transport_registry.go:75-80).
func registerLocalDNS(registry *dns.TransportRegistry, resolver LocalDNS) {
	dns.RegisterTransport[option.LocalDNSServerOptions](registry, C.DNSTypeLocal, func(ctx context.Context, logger log.ContextLogger, tag string, options option.LocalDNSServerOptions) (adapter.DNSTransport, error) {
		return newLocalDNSTransport(ctx, logger, resolver, tag, options)
	})
}

func newLocalDNSTransport(ctx context.Context, logger log.ContextLogger, resolver LocalDNS, tag string, options option.LocalDNSServerOptions) (*localDNSTransport, error) {
	preferred, err := local.NewPreferredDomainResolver(ctx, logger, options)
	if err != nil {
		return nil, err
	}
	return &localDNSTransport{
		TransportAdapter: dns.NewTransportAdapterWithLocalOptions(C.DNSTypeLocal, tag, options),
		resolver:         resolver,
		preferred:        preferred,
		networkManager:   service.FromContext[adapter.NetworkManager](ctx),
	}, nil
}

func (t *localDNSTransport) Start(stage adapter.StartStage, _ *adapter.Scope) error {
	t.preferred.Start(stage)
	return nil
}

func (t *localDNSTransport) Reset() {}

func (t *localDNSTransport) PreferredDomain(domain string) bool {
	return t.preferred.PreferredDomain(domain)
}

func (t *localDNSTransport) Environment() []string {
	if t.networkManager == nil {
		return nil
	}
	if iface := t.networkManager.DefaultNetworkInterface(); iface != nil {
		return iface.DNSServers
	}
	return nil
}

func (t *localDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if response := t.preferred.Lookup(message); response != nil {
		return response, nil
	}
	query, err := message.Pack()
	if err != nil {
		return nil, err
	}
	type result struct {
		raw []byte
		err error
	}
	// The host call blocks without a context; abandon it on cancel.
	done := make(chan result, 1)
	go func() {
		raw, err := t.resolver.Exchange(query)
		done <- result{raw, err}
	}()
	select {
	case r := <-done:
		if r.err != nil {
			return nil, r.err
		}
		response := new(mDNS.Msg)
		if err = response.Unpack(r.raw); err != nil {
			return nil, E.Cause(err, "parse response")
		}
		response.Id = message.Id
		return response, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (t *localDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() {
		callback(t.Exchange(ctx, message))
	}()
}
