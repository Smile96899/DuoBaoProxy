package mobile

import (
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"runtime/debug"
	"sync"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"

	"duobaoproxy/api"
	"duobaoproxy/core"
	"duobaoproxy/core/singbox"
	"duobaoproxy/core/xray"
	"duobaoproxy/probe"
	"duobaoproxy/render"
)

// version is the app version, set with -ldflags "-X duobaoproxy/mobile.version=...".
var version = "dev"

// SetupOptions describes where the host keeps the core's files.
type SetupOptions struct {
	// BasePath is persistent storage (Android filesDir, iOS App Group
	// container): cache.db, crash.log and the sing-box working directory.
	BasePath string
	// TempPath is scratch storage (Android cacheDir, iOS tmp); it becomes
	// TMPDIR. Empty means BasePath/tmp.
	TempPath string
	// AssetPath holds geoip.dat / geosite.dat (xray) and local .srs files.
	// Empty means BasePath.
	AssetPath string
	// Platform is "android" or "ios"; it is injected into rendered settings.
	Platform string
	// OwnPackage is the Android package name ("" on iOS); it is always kept
	// outside the VPN.
	OwnPackage string
	// Debug logs every start config.
	Debug bool
}

// paths is the state Setup stores for the rest of the package.
type paths struct {
	base, temp, asset    string
	platform, ownPackage string
	debug                bool
}

var (
	registerOnce sync.Once
	setupMu      sync.Mutex
	setupState   paths
	crashFile    *os.File
)

func currentPaths() paths {
	setupMu.Lock()
	defer setupMu.Unlock()
	return setupState
}

// Setup prepares the process: it registers both engines (once), creates the
// directories, sets TMPDIR and XRAY_LOCATION_ASSET, points the sing-box
// cache at BasePath/cache.db and sends Go crash output to BasePath/crash.log.
// Calling it again (e.g. 重启核心) applies the new options.
func Setup(o *SetupOptions) error {
	if o == nil || o.BasePath == "" {
		return errors.New("mobile: SetupOptions.BasePath is required")
	}
	switch o.Platform {
	case "", render.PlatformAndroid, render.PlatformIOS:
	default:
		return errors.New("mobile: unknown platform " + o.Platform)
	}
	registerOnce.Do(func() {
		core.Register(singbox.New())
		core.Register(xray.New())
		// Direct probes dial with protected sockets (Android VpnService.protect).
		probe.Direct = protectedSystemDialer{}
	})

	p := paths{
		base:       o.BasePath,
		temp:       o.TempPath,
		asset:      o.AssetPath,
		platform:   o.Platform,
		ownPackage: o.OwnPackage,
		debug:      o.Debug,
	}
	if p.temp == "" {
		p.temp = filepath.Join(p.base, "tmp")
	}
	if p.asset == "" {
		p.asset = p.base
	}
	for _, dir := range []string{p.base, p.temp, p.asset} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	// gomobile does not set TMPDIR; Go's fallback /data/local/tmp is not
	// writable for apps.
	if err := os.Setenv("TMPDIR", p.temp); err != nil {
		return err
	}
	if err := os.Setenv("XRAY_LOCATION_ASSET", p.asset); err != nil {
		return err
	}
	singbox.CacheFile = filepath.Join(p.base, "cache.db")

	setupMu.Lock()
	defer setupMu.Unlock()
	setupState = p
	if crashFile == nil {
		f, err := os.OpenFile(filepath.Join(p.base, "crash.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
		if err != nil {
			return err
		}
		if err = debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
			_ = f.Close()
			return err
		}
		crashFile = f // stays open for the life of the process
	}
	if C.IsIos || p.platform == render.PlatformIOS {
		// libbox ReloadSetupOptions: the Network Extension is killed at ~50 MiB.
		const limit = 50 << 20
		debug.SetGCPercent(50)
		debug.SetMemoryLimit(limit * 4 / 5)
	} else {
		debug.SetMemoryLimit(math.MaxInt64)
	}
	logf(log.LevelInfo, "setup %s (%s) base=%s", version, p.platform, p.base)
	return nil
}

// Version returns the app version baked in at build time.
func Version() string { return version }

// coreInfo is one ListCoresJSON entry.
type coreInfo struct {
	ID        string   `json:"id"`
	Version   string   `json:"version"`
	Protocols []string `json:"protocols"`
	Running   bool     `json:"running"`
}

// ListCoresJSON lists the registered engines, sorted by id:
//
//	[{"id":"sing-box","version":"1.14.3","protocols":["socks",...],"running":false}, ...]
//
// It is empty ("[]") before Setup.
func ListCoresJSON() string {
	out := []coreInfo{}
	for _, c := range api.ListCores() {
		out = append(out, coreInfo{ID: c.ID, Version: c.Version, Protocols: nonNil(c.Protocols), Running: c.Running})
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// Supports reports whether the engine coreID ("" means sing-box) can carry
// protocol, from the same list the renderer and the engine use.
func Supports(coreID, protocol string) bool {
	r, ok := api.Renderer(coreID)
	return ok && r.Supports(protocol)
}
