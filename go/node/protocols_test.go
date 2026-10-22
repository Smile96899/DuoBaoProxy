package node

import "testing"

func TestHysteriaV1LinkRoundTrip(t *testing.T) {
	link := "hysteria://h.example.com:443?auth=pw&peer=h.example.com&upmbps=50&downmbps=100&obfs=xplus&obfsParam=ob&insecure=1#hy1"
	n, err := ParseLink(link)
	if err != nil {
		t.Fatal(err)
	}
	if n.Protocol != Hysteria || n.Hysteria == nil || n.Hysteria.Auth != "pw" || n.Hysteria.UpMbps != 50 || n.Hysteria.DownMbps != 100 || n.Hysteria.Obfs != "ob" {
		t.Fatalf("parsed %+v / %+v", n, n.Hysteria)
	}
	if n.QUICTLS == nil || n.QUICTLS.SNI != "h.example.com" || !n.QUICTLS.AllowInsecure {
		t.Fatalf("tls %+v", n.QUICTLS)
	}
	back, err := ParseLink(n.ShareLink())
	if err != nil {
		t.Fatal(err)
	}
	if *back.Hysteria != *n.Hysteria || *back.QUICTLS != *n.QUICTLS || back.Server != n.Server || back.Port != n.Port {
		t.Fatalf("round trip mismatch:\n %s\n %s", link, n.ShareLink())
	}
}

func TestClashWireGuardAndShadowTLS(t *testing.T) {
	yaml := `
proxies:
  - {name: wg-bare, type: wireguard, server: 1.2.3.4, port: 51820, ip: 172.16.0.2, ipv6: "fd01::2", private-key: priv, public-key: pub, pre-shared-key: psk, reserved: "AQID", mtu: 1280, udp: true}
  - name: wg-peers
    type: wireguard
    ip: 10.0.0.2
    private-key: priv
    peers:
      - {server: 5.6.7.8, port: 2408, public-key: pub2, reserved: [4, 5, 6], allowed-ips: ["0.0.0.0/0"]}
  - {name: stls, type: ss, server: 1.2.3.4, port: 443, cipher: aes-256-gcm, password: sspw, plugin: shadow-tls, client-fingerprint: chrome, plugin-opts: {host: www.microsoft.com, password: tlspw}}
  - {name: hy1, type: hysteria, server: 1.2.3.4, port: 443, auth-str: pw, up: 30, down: 200, sni: a.b, skip-cert-verify: true}
`
	r := Import(yaml)
	if len(r.Errors) > 0 || len(r.Nodes) != 4 {
		t.Fatalf("import: %+v nodes=%d", r.Errors, len(r.Nodes))
	}
	wg := r.Nodes[0]
	if err := wg.Validate(); err != nil {
		t.Fatal(err)
	}
	if wg.WireGuard.LocalAddress != "172.16.0.2/32,fd01::2/128" || wg.WireGuard.Reserved != "1,2,3" || wg.WireGuard.PreSharedKey != "psk" || wg.WireGuard.MTU != 1280 {
		t.Fatalf("wg %+v", wg.WireGuard)
	}
	wg2 := r.Nodes[1]
	if err := wg2.Validate(); err != nil {
		t.Fatal(err)
	}
	if wg2.Server != "5.6.7.8" || wg2.Port != 2408 || wg2.WireGuard.PeerPublicKey != "pub2" || wg2.WireGuard.Reserved != "4,5,6" {
		t.Fatalf("wg peers %+v %+v", wg2, wg2.WireGuard)
	}
	st := r.Nodes[2]
	if err := st.Validate(); err != nil {
		t.Fatal(err)
	}
	if st.Protocol != ShadowTLS || st.Password != "tlspw" || st.ShadowTLS.SSPassword != "sspw" || st.ShadowTLS.Version != 2 || st.Method != "aes-256-gcm" || st.Stream.SNI != "www.microsoft.com" || st.Stream.Fingerprint != "chrome" {
		t.Fatalf("shadowtls %+v %+v %+v", st, st.ShadowTLS, st.Stream)
	}
	hy := r.Nodes[3]
	if hy.Protocol != Hysteria || hy.Hysteria.Auth != "pw" || hy.Hysteria.UpMbps != 30 || hy.QUICTLS.SNI != "a.b" || !hy.QUICTLS.AllowInsecure {
		t.Fatalf("hysteria %+v %+v", hy.Hysteria, hy.QUICTLS)
	}
}

func TestReservedBytes(t *testing.T) {
	if r := ReservedBytes("1,2,3"); r == nil || r[2] != 3 {
		t.Fatal("valid triple rejected")
	}
	for _, bad := range []string{"1,2", "1,2,300", "a,b,c", "1,2,3,4"} {
		if ReservedBytes(bad) != nil {
			t.Fatalf("%q accepted", bad)
		}
	}
}
