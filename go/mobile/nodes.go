package mobile

import (
	"encoding/json"
	"errors"
	"strings"

	"duobaoproxy/api"
	"duobaoproxy/node"
	"duobaoproxy/qr"
)

// ImportResult is the outcome of ImportNodes.
type ImportResult struct {
	// NodesJSON is a JSON array of node objects (the node.Node schema of
	// data.json), "[]" when nothing was imported.
	NodesJSON string
	// ErrorsJSON is a JSON array of per-line error strings.
	ErrorsJSON string
	// Count is the number of imported nodes.
	Count int32
}

// ImportNodes parses share links (one per line), a base64 subscription blob
// or Clash/mihomo YAML.
func ImportNodes(text string) *ImportResult {
	r := api.ImportNodes(text)
	nodes := make([]json.RawMessage, 0, len(r.Nodes))
	for _, n := range r.Nodes {
		nodes = append(nodes, json.RawMessage(n))
	}
	nb, _ := json.Marshal(nodes)
	eb, _ := json.Marshal(nonNil(r.Errors))
	return &ImportResult{NodesJSON: string(nb), ErrorsJSON: string(eb), Count: int32(len(nodes))}
}

// ShareLink returns the share link of one node object. With v2raynVMess set,
// vmess nodes use the v2rayN base64 format. Protocols without a common link
// format (wireguard, ssh, anytls, shadowtls) return an error.
func ShareLink(nodeJSON string, v2raynVMess bool) (string, error) {
	return api.ShareLink(nodeJSON, v2raynVMess)
}

// NormalizeNode validates one node object the way the renderer will see it
// and returns it re-encoded in canonical form (e.g. WireGuard local
// addresses gain their /32 or /128), for the node editor. The error is the
// validation message.
func NormalizeNode(nodeJSON string) (string, error) {
	var n node.Node
	if err := json.Unmarshal([]byte(nodeJSON), &n); err != nil {
		return "", errors.New("node: " + err.Error())
	}
	if err := n.Validate(); err != nil {
		return "", err
	}
	b, err := json.Marshal(&n)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// errQRNotFound is what the UI shows when an image has no QR code.
var errQRNotFound = errors.New("图片中没有可识别的二维码")

func qrTexts(texts []string, err error) (string, error) {
	if errors.Is(err, qr.ErrNotFound) {
		return "", errQRNotFound
	}
	if err != nil {
		return "", err
	}
	return strings.Join(texts, "\n"), nil
}

// DecodeQR returns the text of every QR code in a PNG or JPEG image,
// joined by "\n".
func DecodeQR(image []byte) (string, error) {
	return qrTexts(qr.Decode(image))
}

// DecodeQRLuminance decodes a raw 8-bit luminance plane (the CameraX Y
// plane: row r starts at y[r*rowStride]), returning the texts joined by "\n".
func DecodeQRLuminance(y []byte, width, height, rowStride int32) (string, error) {
	return qrTexts(qr.DecodeLuminance(y, int(width), int(height), int(rowStride)))
}

// EncodeQR renders text as a size x size PNG (desktop uses 480; size <= 0
// means 480).
func EncodeQR(text string, size int32) ([]byte, error) {
	if size <= 0 {
		size = 480
	}
	return qr.Encode(text, int(size))
}
