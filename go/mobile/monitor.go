package mobile

import (
	"slices"

	"github.com/sagernet/sing-box/adapter"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/x/list"
)

// interfaceMonitor is the platform's tun.DefaultInterfaceMonitor; the host
// drives it through NetworkListener (mirrors libbox monitor.go).
type interfaceMonitor platform

var _ tun.DefaultInterfaceMonitor = (*interfaceMonitor)(nil)

func (m *interfaceMonitor) Start() error {
	if m.host == nil {
		return nil // test platform: no default interface, sockets are protected
	}
	return m.host.StartNetworkMonitor(&NetworkListener{p: (*platform)(m)})
}

func (m *interfaceMonitor) Close() error {
	m.access.Lock()
	m.monitorClosed = true
	m.access.Unlock()
	if m.host == nil {
		return nil
	}
	return m.host.CloseNetworkMonitor()
}

func (m *interfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.defaultInterface
}

func (m *interfaceMonitor) OverrideAndroidVPN() bool { return false }
func (m *interfaceMonitor) AndroidVPNEnabled() bool  { return false }

func (m *interfaceMonitor) RegisterCallback(cb tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(cb)
}

func (m *interfaceMonitor) UnregisterCallback(e *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(e)
}

func (m *interfaceMonitor) RegisterMyInterface(name string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, name)
}

func (m *interfaceMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterfaces
}

// NetworkListener is handed to Host.StartNetworkMonitor; the host calls it
// on every default network change.
type NetworkListener struct {
	p *platform
}

// UpdateDefaultInterface reports the current default (non-VPN) network:
// its interface name and OS index, or index -1 when there is none.
// expensive = metered, constrained = iOS Low Data Mode (false on Android).
func (l *NetworkListener) UpdateDefaultInterface(name string, index int32, expensive, constrained bool) {
	if l == nil || l.p == nil {
		return
	}
	// Run on a fresh goroutine and wait: calls arriving on a foreign
	// (JNI-attached) thread can crash the Go stack growth, golang/go#68760
	// (libbox monitor.go:102-113 FixAndroidStack).
	done := make(chan struct{})
	go func() {
		defer close(done)
		l.p.updateDefaultInterface(name, int(index), expensive, constrained)
	}()
	<-done
}

func (p *platform) updateDefaultInterface(name string, index int, expensive, constrained bool) {
	p.access.Lock()
	if p.monitorClosed || p.nm == nil {
		p.access.Unlock()
		return
	}
	nm := p.nm
	// Set before UpdateInterfaces so NetworkInterfaces marks the new
	// default as expensive/constrained.
	p.defaultIndex = index
	p.expensive = expensive
	p.constrained = constrained
	p.access.Unlock()

	if err := nm.UpdateInterfaces(); err != nil {
		p.logError(E.Cause(err, "update interfaces"))
	}

	if index == -1 {
		p.access.Lock()
		p.defaultInterface = nil
		p.defaultDNS = nil
		p.defaultInitialized = true
		callbacks := p.callbacks.Array()
		p.access.Unlock()
		for _, cb := range callbacks {
			cb(nil, 0)
		}
		return
	}
	iface, err := nm.InterfaceFinder().ByIndex(index)
	if err != nil {
		p.logError(E.Cause(err, "find updated interface: ", name))
		return
	}
	var dnsServers []string
	for _, it := range nm.NetworkInterfaces() {
		if it.Index == index {
			dnsServers = it.DNSServers
			break
		}
	}
	p.access.Lock()
	old := p.defaultInterface
	oldDNS := p.defaultDNS
	p.defaultInterface = iface
	p.defaultDNS = dnsServers
	if p.defaultInitialized && old != nil && old.Name == iface.Name && old.Index == iface.Index && slices.Equal(oldDNS, dnsServers) {
		p.access.Unlock()
		return
	}
	p.defaultInitialized = true
	callbacks := p.callbacks.Array()
	p.access.Unlock()
	for _, cb := range callbacks {
		cb(iface, 0)
	}
}

var _ networkManager = (adapter.NetworkManager)(nil)
