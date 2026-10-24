package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/log"

	"duobaoproxy/api"
	"duobaoproxy/core"
	"duobaoproxy/core/singbox"
)

// StartOptions is one Start call, normally the fields of a RenderResult.
type StartOptions struct {
	// CoreID is "sing-box" or "xray" ("" means sing-box).
	CoreID string
	// Config is the engine-native config (RenderResult.Config).
	Config string
	// StatsTagsJSON is a JSON array of the outbound tags to count
	// (RenderResult.StatsTagsJSON); "" counts nothing.
	StatsTagsJSON string
	// FrontConfig is the sing-box TUN front for xray
	// (RenderResult.FrontConfig), "" for none.
	FrontConfig string
}

// Traffic is the cumulative byte count of one outbound tag.
type Traffic struct {
	Uplink   int64
	Downlink int64
}

var (
	// svc owns the main engine; the xray TUN front is a separate sing-box
	// instance owned here so the registry still reports one running core.
	svc     api.Service
	runMu   sync.Mutex
	frontSB *singbox.Core
)

// engineLog forwards engine lines to the LogHandler with their level.
func engineLog(line string) { writeLog(lineLevel(line), line) }

// lineLevel recovers the level from a formatted engine line: sing-box
// writes "INFO[0000] ..." (or "+0800 2026-01-01 ... INFO ..."), xray
// "... [Warning] ...". Unknown lines are info.
func lineLevel(line string) log.Level {
	head := line
	if len(head) > 80 {
		head = head[:80]
	}
	level, at := log.LevelInfo, len(head)
	for _, m := range []struct {
		mark  string
		level log.Level
	}{
		{"PANIC", log.LevelPanic}, {"[Panic]", log.LevelPanic},
		{"FATAL", log.LevelFatal}, {"[Fatal]", log.LevelFatal},
		{"ERROR", log.LevelError}, {"[Error]", log.LevelError},
		{"WARN", log.LevelWarn}, {"[Warning]", log.LevelWarn},
		{"INFO", log.LevelInfo}, {"[Info]", log.LevelInfo},
		{"DEBUG", log.LevelDebug}, {"[Debug]", log.LevelDebug},
		{"TRACE", log.LevelTrace},
	} {
		// The first level word wins: it is the line's own prefix, later
		// ones are part of the message.
		if i := strings.Index(head, m.mark); i >= 0 && i < at {
			level, at = m.level, i
		}
	}
	return level
}

// Start runs the engine. h is the VpnService / NEPacketTunnelProvider that
// opens the TUN for sing-box (nil without TUN). With a FrontConfig, xray
// starts first and the sing-box TUN front second; a failure stops whatever
// already started. It fails when an engine is already running.
func Start(o *StartOptions, h Host) error {
	if o == nil {
		return errors.New("mobile: nil StartOptions")
	}
	p := currentPaths()
	if p.base == "" {
		return errors.New("mobile: Setup was not called")
	}
	var tags []string
	if strings.TrimSpace(o.StatsTagsJSON) != "" {
		if err := json.Unmarshal([]byte(o.StatsTagsJSON), &tags); err != nil {
			return errors.New("stats tags: " + err.Error())
		}
	}
	id := api.CoreID(o.CoreID)

	runMu.Lock()
	defer runMu.Unlock()
	if running := svc.Status(); running != "" {
		return fmt.Errorf("%s is already running; stop it first", running)
	}
	if p.debug {
		logf(log.LevelDebug, "start %s: %s", id, o.Config)
		if o.FrontConfig != "" {
			logf(log.LevelDebug, "start front: %s", o.FrontConfig)
		}
	}
	defer debug.FreeOSMemory()

	ctx := context.Background()
	if id == core.IDSingBox {
		ctx = newBoxContext(h, p.base, p.temp)
	}
	err := svc.Start(ctx, api.StartOptions{
		CoreID:         id,
		Config:         o.Config,
		StatsOutbounds: tags,
		LogWriter:      engineLog,
		AssetDir:       p.asset,
	})
	if err != nil {
		return err
	}
	if o.FrontConfig == "" {
		return nil
	}
	front := singbox.New()
	err = front.Start(newBoxContext(h, p.base, p.temp), core.StartOptions{
		Config:    []byte(o.FrontConfig),
		LogWriter: engineLog,
	})
	if err != nil {
		_ = svc.Stop("")
		return fmt.Errorf("tun front: %w", err)
	}
	frontSB = front
	return nil
}

// Stop stops the engine (the front first, then xray). It is a no-op when
// nothing runs.
func Stop() error {
	runMu.Lock()
	defer runMu.Unlock()
	var errs []error
	if frontSB != nil {
		if err := frontSB.Stop(); err != nil {
			errs = append(errs, fmt.Errorf("tun front: %w", err))
		}
		frontSB = nil
	}
	if err := svc.Stop(""); err != nil {
		errs = append(errs, err)
	}
	debug.FreeOSMemory()
	return errors.Join(errs...)
}

// Status returns the running engine ("sing-box" / "xray"), or "". With the
// xray TUN front it reports "xray".
func Status() string { return svc.Status() }

// QueryStats returns the cumulative traffic of one outbound tag of the
// running engine (xray's own counters in front mode). Zero when nothing
// runs or on any error.
func QueryStats(tag string) *Traffic {
	t := svc.QueryStats(tag)
	return &Traffic{Uplink: t.Uplink, Downlink: t.Downlink}
}
