package node

import (
	"strings"
	"testing"
)

func TestShareLinkStandardSchemes(t *testing.T) {
	cases := []struct {
		n      *Node
		scheme string
	}{
		{&Node{Protocol: Socks, Server: "1.2.3.4", Port: 1080, Username: "u", Password: "p", Name: "x"}, "socks5://"},
		{&Node{Protocol: HTTP, Server: "1.2.3.4", Port: 8080}, "http://"},
		{&Node{Protocol: Shadowsocks, Server: "1.2.3.4", Port: 8388, Method: "aes-256-gcm", Password: "p"}, "ss://"},
		{&Node{Protocol: Trojan, Server: "1.2.3.4", Port: 443, Password: "p"}, "trojan://"},
	}
	for _, c := range cases {
		link := c.n.ShareLink()
		if !strings.HasPrefix(link, c.scheme) {
			t.Fatalf("%s: got %q", c.n.Protocol, link)
		}
		got, err := ParseLink(link)
		if err != nil {
			t.Fatalf("%s: %v", link, err)
		}
		if got.Server != c.n.Server || got.Port != c.n.Port || got.Protocol != c.n.Protocol {
			t.Fatalf("%s: round trip got %+v", link, got)
		}
	}
}
