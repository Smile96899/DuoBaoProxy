//go:build darwin

package ikev2

import (
	"context"
	"encoding/base64"
	"encoding/xml"
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/google/uuid"
)

// darwinDriver installs a configuration profile (.mobileconfig) describing
// the IKEv2 connection and then drives it with scutil. Profile installation
// opens System Settings and needs one-time user approval; after that the
// connection can be started and stopped silently.
type darwinDriver struct{}

func newDriver() driver { return darwinDriver{} }

func (darwinDriver) connect(ctx context.Context, p *Profile, logf func(string)) error {
	ok, err := darwinDriver{}.exists(ctx, p)
	if err != nil {
		return err
	}
	if !ok {
		path, err := writeMobileconfig(p)
		if err != nil {
			return err
		}
		if _, err = run(ctx, logf, "open", path); err != nil {
			return err
		}
		return errors.New("ikev2: profile opened in System Settings; approve it under Profiles, then connect again")
	}
	_, err = run(ctx, logf, "scutil", "--nc", "start", p.Name)
	return err
}

func (darwinDriver) disconnect(ctx context.Context, p *Profile) error {
	_, err := run(ctx, nil, "scutil", "--nc", "stop", p.Name)
	return err
}

func (darwinDriver) exists(ctx context.Context, p *Profile) (bool, error) {
	out, err := run(ctx, nil, "scutil", "--nc", "list")
	if err != nil {
		return false, err
	}
	return strings.Contains(out, `"`+p.Name+`"`), nil
}

func (d darwinDriver) connected(ctx context.Context, p *Profile) (bool, error) {
	out, err := run(ctx, nil, "scutil", "--nc", "status", p.Name)
	if err != nil {
		return false, err
	}
	return strings.HasPrefix(out, "Connected"), nil
}

type plistDict map[string]any

func (d plistDict) MarshalXML(e *xml.Encoder, start xml.StartElement) error {
	start.Name.Local = "dict"
	if err := e.EncodeToken(start); err != nil {
		return err
	}
	for k, v := range d {
		if err := e.EncodeElement(k, xml.StartElement{Name: xml.Name{Local: "key"}}); err != nil {
			return err
		}
		if err := encodePlistValue(e, v); err != nil {
			return err
		}
	}
	return e.EncodeToken(start.End())
}

func encodePlistValue(e *xml.Encoder, v any) error {
	switch x := v.(type) {
	case string:
		return e.EncodeElement(x, xml.StartElement{Name: xml.Name{Local: "string"}})
	case int:
		return e.EncodeElement(x, xml.StartElement{Name: xml.Name{Local: "integer"}})
	case bool:
		name := "false"
		if x {
			name = "true"
		}
		s := xml.StartElement{Name: xml.Name{Local: name}}
		if err := e.EncodeToken(s); err != nil {
			return err
		}
		return e.EncodeToken(s.End())
	case []byte:
		return e.EncodeElement(base64.StdEncoding.EncodeToString(x), xml.StartElement{Name: xml.Name{Local: "data"}})
	case plistDict:
		return x.MarshalXML(e, xml.StartElement{})
	case []any:
		s := xml.StartElement{Name: xml.Name{Local: "array"}}
		if err := e.EncodeToken(s); err != nil {
			return err
		}
		for _, item := range x {
			if err := encodePlistValue(e, item); err != nil {
				return err
			}
		}
		return e.EncodeToken(s.End())
	}
	return fmt.Errorf("plist: unsupported type %T", v)
}

func writeMobileconfig(p *Profile) (string, error) {
	ike := plistDict{
		"RemoteAddress":          p.Server,
		"RemoteIdentifier":       p.RemoteID,
		"DisableMOBIKE":          0,
		"DisableRedirect":        0,
		"EnablePFS":              1,
		"EnableCertificateRevocationCheck": 0,
		"IKESecurityAssociationParameters": plistDict{
			"EncryptionAlgorithm": "AES-256-GCM", "IntegrityAlgorithm": "SHA2-256", "DiffieHellmanGroup": 20, "LifeTimeInMinutes": 1440,
		},
		"ChildSecurityAssociationParameters": plistDict{
			"EncryptionAlgorithm": "AES-256-GCM", "IntegrityAlgorithm": "SHA2-256", "DiffieHellmanGroup": 20, "LifeTimeInMinutes": 1440,
		},
	}
	if p.LocalID != "" {
		ike["LocalIdentifier"] = p.LocalID
	}
	payloads := []any{}
	switch p.Auth {
	case AuthEAP:
		ike["AuthenticationMethod"] = "None"
		ike["ExtendedAuthEnabled"] = 1
		ike["AuthName"] = p.Username
		ike["AuthPassword"] = p.Password
	case AuthPSK:
		ike["AuthenticationMethod"] = "SharedSecret"
		ike["SharedSecret"] = p.PSK
	case AuthCert:
		raw, err := base64.StdEncoding.DecodeString(p.ClientCertP12)
		if err != nil {
			return "", fmt.Errorf("ikev2: client_cert_p12 is not base64: %w", err)
		}
		certUUID := uuid.NewString()
		ike["AuthenticationMethod"] = "Certificate"
		ike["PayloadCertificateUUID"] = certUUID
		payloads = append(payloads, plistDict{
			"PayloadType": "com.apple.security.pkcs12", "PayloadVersion": 1, "PayloadIdentifier": "proxy.h.ikev2.cert",
			"PayloadUUID": certUUID, "PayloadDisplayName": p.Name + " client certificate",
			"PayloadContent": raw, "Password": p.ClientCertPass,
		})
	}
	if p.CACertPEM != "" {
		payloads = append(payloads, plistDict{
			"PayloadType": "com.apple.security.root", "PayloadVersion": 1, "PayloadIdentifier": "proxy.h.ikev2.ca",
			"PayloadUUID": uuid.NewString(), "PayloadDisplayName": p.Name + " CA",
			"PayloadContent": []byte(p.CACertPEM),
		})
	}
	vpn := plistDict{
		"PayloadType": "com.apple.vpn.managed", "PayloadVersion": 1, "PayloadIdentifier": "proxy.h.ikev2.vpn",
		"PayloadUUID": uuid.NewString(), "PayloadDisplayName": p.Name,
		"UserDefinedName": p.Name, "VPNType": "IKEv2", "IKEv2": ike,
	}
	if !p.SplitTunnel {
		vpn["OverridePrimary"] = 1
	}
	payloads = append(payloads, vpn)
	root := plistDict{
		"PayloadType": "Configuration", "PayloadVersion": 1, "PayloadIdentifier": "proxy.h.ikev2",
		"PayloadUUID": uuid.NewString(), "PayloadDisplayName": p.Name, "PayloadContent": payloads,
	}
	var b strings.Builder
	b.WriteString(xml.Header)
	b.WriteString(`<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">` + "\n")
	b.WriteString(`<plist version="1.0">`)
	enc := xml.NewEncoder(&b)
	if err := root.MarshalXML(enc, xml.StartElement{}); err != nil {
		return "", err
	}
	if err := enc.Flush(); err != nil {
		return "", err
	}
	b.WriteString(`</plist>`)
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	dir += "/h.proxy/ikev2"
	if err = os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	path := dir + "/" + p.Name + ".mobileconfig"
	return path, os.WriteFile(path, []byte(b.String()), 0o600)
}
