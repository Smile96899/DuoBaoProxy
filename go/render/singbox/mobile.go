package singbox

import (
	"encoding/json"
	"fmt"
	"strings"

	"duobaoproxy/render"
)

// Android VpnService / iOS Packet Tunnel specifics (Settings.Platform != "").
// The host opens the tunnel from the inbound's options, so the interface
// name comes from the OS and routes and package lists are applied there.

// mobileMTU caps iOS below the utun headroom; sing-box's own 4064 default
// never applies because settings always carry an MTU.
func mobileMTU(set *render.Settings) int {
	if set.Platform == render.PlatformIOS && set.TUN.MTU > 4064 {
		return 4064
	}
	return set.TUN.MTU
}

// mobileTUN is the TUN inbound for a VPN host. Both address families are
// always routed into the tunnel so IPv6 cannot leak around it; with IPv6
// off the route rejects it instead (ipv6Reject). strict_route stays off:
// the kill switch is the OS's own setting.
func mobileTUN(set *render.Settings, mtu int, export bool) render.Obj {
	in := render.Obj{
		"type": "tun", "tag": "tun-in", "auto_route": true, "stack": set.TUN.Stack, "mtu": mtu,
		"address": []any{"172.19.0.1/28", "fdfe:dcba:9876::1/126"},
	}
	if export {
		return in // the package lists name this device's apps, including our own
	}
	include, exclude := tunPackages(set)
	if len(include) > 0 {
		in["include_package"] = render.Strings(include)
	}
	if len(exclude) > 0 {
		in["exclude_package"] = render.Strings(exclude)
	}
	return in
}

// tunPackages maps tun.rule_process onto the VpnService allow / disallow
// lists (Android forbids mixing them). Our own package must stay outside
// the VPN so neither engine's sockets loop back in: in exclude mode it is
// always excluded, in include mode it is simply never included. An empty
// allow-list would mean "every app", so it falls back to excluding us.
func tunPackages(set *render.Settings) (include, exclude []string) {
	if set.Platform != render.PlatformAndroid {
		return nil, nil
	}
	own := strings.TrimSpace(set.OwnPackage)
	seen := map[string]bool{"": true, own: true}
	var list []string
	for _, p := range set.TUN.RuleProcess {
		if p = strings.TrimSpace(p); !seen[p] {
			seen[p] = true
			list = append(list, p)
		}
	}
	if set.TUN.RuleWhite && len(list) > 0 {
		return list, nil
	}
	if !set.TUN.RuleWhite {
		exclude = list
	}
	if own != "" {
		exclude = append(exclude, own)
	}
	return nil, exclude
}

// ipv6Reject keeps IPv6 inside the tunnel when the user turned it off.
func ipv6Reject() render.Obj {
	return render.Obj{"inbound": []any{"tun-in"}, "ip_version": 6, "action": "reject"}
}

// addPlatformServices adds what the platform's process needs: on iOS the
// OOM killer, which frees memory under the Network Extension limit.
func addPlatformServices(cfg render.Obj, set *render.Settings) {
	if set.Platform == render.PlatformIOS {
		cfg["services"] = []any{render.Obj{"type": "oom-killer"}}
	}
}

// RenderXrayFront renders the sing-box config that carries the mobile TUN
// for xray: everything from the tunnel goes to xray's loopback mixed
// inbound on 127.0.0.1:port (TCP and UDP, DNS included; xray sniffs and
// routes), except Android per-app choices, which only sing-box can see.
// MTU is 1500 because xray's socks UDP path reads one 8 KiB buffer per
// packet.
func RenderXrayFront(set render.Settings, port int, user, pass string) ([]byte, error) {
	if !set.Mobile() {
		return nil, fmt.Errorf("render: the xray TUN front is for mobile only")
	}
	if port <= 0 || port > 65535 {
		return nil, fmt.Errorf("render: xray bridge port %d out of range", port)
	}
	set.Defaults()
	socks := render.Obj{"type": "socks", "tag": "xray", "server": "127.0.0.1", "server_port": port, "version": "5"}
	if user != "" && pass != "" {
		socks["username"], socks["password"] = user, pass
	}
	cfg := render.Obj{
		"log":       render.Obj{"level": set.LogLevel},
		"inbounds":  []any{mobileTUN(&set, 1500, false)},
		"outbounds": []any{socks, render.Obj{"type": "direct", "tag": "direct"}},
	}
	var rules []any
	if !set.TUN.IPv6 {
		rules = append(rules, ipv6Reject())
	}
	rules = append(rules, render.Obj{"ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "action": "reject"})
	rename := func(out string) string {
		switch out {
		case "proxy":
			return "xray"
		case "block":
			return "block"
		}
		return "direct"
	}
	rules = append(rules, appRules(set.PlatformApps(), set.Platform == render.PlatformAndroid, rename)...)
	if len(set.TUN.RuleCIDR) > 0 {
		matchOut := "direct"
		if set.TUN.RuleWhite {
			matchOut = "xray"
		}
		rules = append(rules, render.Obj{"ip_cidr": render.Strings(set.TUN.RuleCIDR), "outbound": matchOut})
	}
	// auto_detect_interface lets the platform protect the direct
	// outbound's sockets; the socks hop to loopback never leaves the device.
	cfg["route"] = render.Obj{"rules": rules, "final": "xray", "auto_detect_interface": true}
	addPlatformServices(cfg, &set)
	return json.MarshalIndent(cfg, "", "  ")
}
