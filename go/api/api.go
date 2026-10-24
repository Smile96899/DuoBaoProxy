// Package api is the transport-free core API: everything the GUI can ask
// the core for, as plain Go functions. go/rpc serves it over gRPC to the
// desktop GUI; go/mobile binds it for the Android/iOS hosts.
//
// Engines are not registered here; the caller does core.Register itself.
package api

import (
	"encoding/json"
	"errors"

	"duobaoproxy/core"
	"duobaoproxy/node"
	"duobaoproxy/render"
	"duobaoproxy/render/singbox"
	"duobaoproxy/render/xray"
)

// Renderers maps core ids to config renderers.
var Renderers = map[string]render.Renderer{
	core.IDSingBox: singbox.New(),
	core.IDXray:    xray.New(),
}

// Renderer returns the config renderer for a core id ("" means sing-box).
func Renderer(id string) (render.Renderer, bool) {
	r, ok := Renderers[CoreID(id)]
	return r, ok
}

// CoreID maps the empty id to sing-box, the default core.
func CoreID(id string) string {
	if id == "" {
		return core.IDSingBox
	}
	return id
}

// CoreInfo describes one registered core.
type CoreInfo struct {
	ID        string
	Version   string
	Protocols []string
	Running   bool
}

// ListCores lists every registered core, sorted by id.
func ListCores() []CoreInfo {
	var out []CoreInfo
	for _, c := range core.List() {
		out = append(out, CoreInfo{
			ID:        c.ID(),
			Version:   c.Version(),
			Protocols: c.Protocols(),
			Running:   c.Running(),
		})
	}
	return out
}

// ImportResult holds one node.Node JSON object per imported node, plus the
// per-line errors.
type ImportResult struct {
	Nodes  []string
	Errors []string
}

// ImportNodes parses share links, a base64 blob or Clash YAML.
func ImportNodes(text string) ImportResult {
	r := node.Import(text)
	out := ImportResult{Errors: r.Errors}
	for _, n := range r.Nodes {
		b, err := json.Marshal(n)
		if err != nil {
			out.Errors = append(out.Errors, err.Error())
			continue
		}
		out.Nodes = append(out.Nodes, string(b))
	}
	return out
}

// ShareLink returns the share link of one node.Node JSON object. With
// v2raynVMess set, vmess nodes use the v2rayN base64 format.
func ShareLink(nodeJSON string, v2raynVMess bool) (string, error) {
	var n node.Node
	if err := json.Unmarshal([]byte(nodeJSON), &n); err != nil {
		return "", err
	}
	if v2raynVMess && n.Protocol == node.VMess {
		return n.VMessLinkV2RayN(), nil
	}
	link := n.ShareLink()
	if link == "" {
		return "", errors.New(n.Protocol + " 没有通用的分享链接格式")
	}
	return link, nil
}

// RenderResult is a rendered config plus the outbound tags whose traffic
// the GUI counts (exit first).
type RenderResult struct {
	Config    string
	StatsTags []string
}

// RenderConfig renders a chain of node.Node JSON objects (client outwards,
// the last is the exit) with render.Settings JSON for one core.
func RenderConfig(coreID string, chainJSON []string, settingsJSON string, mode render.Mode) (RenderResult, error) {
	id := CoreID(coreID)
	var chain []*node.Node
	for _, raw := range chainJSON {
		var n node.Node
		if err := json.Unmarshal([]byte(raw), &n); err != nil {
			return RenderResult{}, errors.New("node: " + err.Error())
		}
		chain = append(chain, &n)
	}
	if len(chain) == 0 {
		return RenderResult{}, errors.New("empty chain")
	}
	r, ok := Renderers[id]
	if !ok {
		return RenderResult{}, core.ErrUnknownCore
	}
	var set render.Settings
	if settingsJSON != "" {
		if err := json.Unmarshal([]byte(settingsJSON), &set); err != nil {
			return RenderResult{}, errors.New("settings: " + err.Error())
		}
	}
	res, err := r.Render(render.Request{Chain: chain, Settings: set, Mode: mode})
	if err != nil {
		return RenderResult{}, err
	}
	return RenderResult{Config: string(res.Config), StatsTags: res.StatsTags}, nil
}
