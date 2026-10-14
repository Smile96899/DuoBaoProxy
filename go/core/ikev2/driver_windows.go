//go:build windows

package ikev2

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// windowsDriver uses the built-in RAS IKEv2 client through PowerShell's
// VpnClient module and rasdial. Everything runs in the current user's
// phonebook, so no elevation is needed except to install a CA certificate.
type windowsDriver struct{}

func newDriver() driver { return windowsDriver{} }

func ps(ctx context.Context, logf func(string), script string) (string, error) {
	return run(ctx, logf, "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
}

func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

func (windowsDriver) connect(ctx context.Context, p *Profile, logf func(string)) error {
	if p.Auth == AuthPSK {
		return errors.New("ikev2: Windows does not support pre-shared keys for IKEv2; use EAP or certificate")
	}
	if p.CACertPEM != "" {
		if err := installCA(ctx, p, logf); err != nil {
			logf("ikev2: CA install skipped: " + err.Error())
		}
	}
	if p.Auth == AuthCert {
		if err := installClientCert(ctx, p, logf); err != nil {
			return err
		}
	}

	auth := "Eap"
	if p.Auth == AuthCert {
		auth = "MachineCertificate"
	}
	split := "$false"
	if p.SplitTunnel {
		split = "$true"
	}
	var b strings.Builder
	fmt.Fprintf(&b, "$ErrorActionPreference='Stop';")
	fmt.Fprintf(&b, "Remove-VpnConnection -Name %s -Force -ErrorAction SilentlyContinue;", psQuote(p.Name))
	fmt.Fprintf(&b, "Add-VpnConnection -Name %s -ServerAddress %s -TunnelType Ikev2 -AuthenticationMethod %s -EncryptionLevel Required -RememberCredential -SplitTunneling:%s -Force;",
		psQuote(p.Name), psQuote(p.Server), auth, split)
	// Modern cipher suite; most strongSwan/Mikrotik/RouterOS gateways accept it.
	fmt.Fprintf(&b, "Set-VpnConnectionIPsecConfiguration -ConnectionName %s -AuthenticationTransformConstants GCMAES256 -CipherTransformConstants GCMAES256 -EncryptionMethod AES256 -IntegrityCheckMethod SHA256 -PfsGroup ECP384 -DHGroup ECP384 -PassThru -Force | Out-Null;", psQuote(p.Name))
	for _, r := range p.Routes {
		fmt.Fprintf(&b, "Add-VpnConnectionRoute -ConnectionName %s -DestinationPrefix %s -ErrorAction SilentlyContinue;", psQuote(p.Name), psQuote(r))
	}
	if _, err := ps(ctx, logf, b.String()); err != nil {
		return err
	}

	args := []string{p.Name}
	if p.Auth == AuthEAP {
		args = append(args, p.Username, p.Password)
	}
	if _, err := run(ctx, logf, "rasdial.exe", args...); err != nil {
		return err
	}
	return nil
}

func (windowsDriver) disconnect(ctx context.Context, p *Profile) error {
	_, err := run(ctx, nil, "rasdial.exe", p.Name, "/disconnect")
	_, _ = ps(ctx, nil, fmt.Sprintf("Remove-VpnConnection -Name %s -Force -ErrorAction SilentlyContinue", psQuote(p.Name)))
	return err
}

func (windowsDriver) connected(ctx context.Context, p *Profile) (bool, error) {
	out, err := run(ctx, nil, "rasdial.exe")
	if err != nil {
		return false, err
	}
	for _, line := range strings.Split(out, "\n") {
		if strings.EqualFold(strings.TrimSpace(line), p.Name) {
			return true, nil
		}
	}
	return false, nil
}

func tempFile(pattern string, data []byte) (string, error) {
	f, err := os.CreateTemp("", pattern)
	if err != nil {
		return "", err
	}
	defer f.Close()
	if _, err = f.Write(data); err != nil {
		return "", err
	}
	return f.Name(), nil
}

func installCA(ctx context.Context, p *Profile, logf func(string)) error {
	path, err := tempFile("hproxy-ca-*.crt", []byte(p.CACertPEM))
	if err != nil {
		return err
	}
	defer os.Remove(path)
	// Machine Root store is what RAS consults for IKEv2 gateway certs; this
	// needs administrator rights and fails silently otherwise.
	_, err = run(ctx, logf, "certutil.exe", "-addstore", "-f", "Root", filepath.Clean(path))
	return err
}

func installClientCert(ctx context.Context, p *Profile, logf func(string)) error {
	raw, err := base64.StdEncoding.DecodeString(p.ClientCertP12)
	if err != nil {
		return fmt.Errorf("ikev2: client_cert_p12 is not base64: %w", err)
	}
	path, err := tempFile("hproxy-client-*.p12", raw)
	if err != nil {
		return err
	}
	defer os.Remove(path)
	script := fmt.Sprintf("$ErrorActionPreference='Stop'; $pw = ConvertTo-SecureString %s -AsPlainText -Force; Import-PfxCertificate -FilePath %s -CertStoreLocation Cert:\\LocalMachine\\My -Password $pw | Out-Null",
		psQuote(p.ClientCertPass), psQuote(path))
	_, err = ps(ctx, logf, script)
	return err
}
