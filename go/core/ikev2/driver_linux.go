//go:build linux

package ikev2

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"strings"
)

// linuxDriver drives NetworkManager with the strongswan VPN plugin
// (package "network-manager-strongswan" / "NetworkManager-strongswan").
// It needs no root: NetworkManager performs the privileged work.
type linuxDriver struct{}

func newDriver() driver { return linuxDriver{} }

func (linuxDriver) connect(ctx context.Context, p *Profile, logf func(string)) error {
	if _, err := run(ctx, nil, "nmcli", "--version"); err != nil {
		return errors.New("ikev2: nmcli not found; install NetworkManager and the strongswan plugin")
	}
	_, _ = run(ctx, nil, "nmcli", "connection", "delete", p.Name)

	data := []string{
		"address=" + p.Server,
		"virtual=yes",
		"encap=yes",
		"ipcomp=no",
		"proposal=yes",
		"ike=aes256gcm16-sha256-ecp384,aes256-sha256-modp2048",
		"esp=aes256gcm16,aes256-sha256",
	}
	if p.RemoteID != "" {
		data = append(data, "remote-identity="+p.RemoteID)
	}
	if p.LocalID != "" {
		data = append(data, "local-identity="+p.LocalID)
	}
	if p.CACertPEM != "" {
		path, err := persistFile(p.Name+"-ca.pem", []byte(p.CACertPEM))
		if err != nil {
			return err
		}
		data = append(data, "certificate="+path)
	}
	secrets := []string{}
	switch p.Auth {
	case AuthEAP:
		data = append(data, "method=eap", "user="+p.Username)
		secrets = append(secrets, "password="+p.Password)
	case AuthPSK:
		data = append(data, "method=psk")
		if p.LocalID != "" {
			data = append(data, "user="+p.LocalID)
		}
		secrets = append(secrets, "password="+p.PSK)
	case AuthCert:
		raw, err := base64.StdEncoding.DecodeString(p.ClientCertP12)
		if err != nil {
			return fmt.Errorf("ikev2: client_cert_p12 is not base64: %w", err)
		}
		path, err := persistFile(p.Name+"-client.p12", raw)
		if err != nil {
			return err
		}
		data = append(data, "method=key", "usercert="+path)
		if p.ClientCertPass != "" {
			secrets = append(secrets, "password="+p.ClientCertPass)
		}
	}

	args := []string{"connection", "add", "type", "vpn", "con-name", p.Name, "ifname", "*",
		"vpn-type", "strongswan", "vpn.data", strings.Join(data, ",")}
	if len(secrets) > 0 {
		args = append(args, "vpn.secrets", strings.Join(secrets, ","))
	}
	if p.SplitTunnel {
		args = append(args, "ipv4.never-default", "yes")
		if len(p.Routes) > 0 {
			args = append(args, "ipv4.routes", strings.Join(p.Routes, ","))
		}
	}
	if _, err := run(ctx, logf, "nmcli", args...); err != nil {
		return err
	}
	_, err := run(ctx, logf, "nmcli", "connection", "up", p.Name)
	return err
}

func (linuxDriver) disconnect(ctx context.Context, p *Profile) error {
	_, err := run(ctx, nil, "nmcli", "connection", "down", p.Name)
	_, _ = run(ctx, nil, "nmcli", "connection", "delete", p.Name)
	return err
}

func (linuxDriver) connected(ctx context.Context, p *Profile) (bool, error) {
	out, err := run(ctx, nil, "nmcli", "-t", "-f", "NAME,TYPE", "connection", "show", "--active")
	if err != nil {
		return false, err
	}
	for _, line := range strings.Split(out, "\n") {
		if strings.HasPrefix(line, p.Name+":") {
			return true, nil
		}
	}
	return false, nil
}

// persistFile stores certificate material under the user's config dir so
// that NetworkManager can read it after we exit.
func persistFile(name string, data []byte) (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	dir = dir + "/h.proxy/ikev2"
	if err = os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	path := dir + "/" + name
	return path, os.WriteFile(path, data, 0o600)
}
