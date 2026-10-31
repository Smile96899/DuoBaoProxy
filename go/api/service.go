package api

import (
	"context"
	"fmt"
	"sync"

	"duobaoproxy/core"
)

// Service owns the running core. Only one core runs at a time.
type Service struct {
	mu      sync.Mutex
	running core.Core
}

// StartOptions is one Start call.
type StartOptions struct {
	// CoreID selects the core; "" means sing-box.
	CoreID string
	// Config is the engine-native JSON configuration.
	Config string
	// StatsOutbounds enables traffic counters for these outbound tags.
	StatsOutbounds []string
	// LogWriter receives engine log lines. May be nil.
	LogWriter func(line string)
	// AssetDir holds geo databases (xray). Empty keeps the core default.
	AssetDir string
}

// Start starts a core. ctx lives as long as the core and may carry values
// the engine reads (the mobile platform interface for sing-box). It fails
// when any core is already running.
func (s *Service) Start(ctx context.Context, o StartOptions) error {
	c, err := core.Get(CoreID(o.CoreID))
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running != nil {
		return fmt.Errorf("%s is already running; stop it first", s.running.ID())
	}
	err = c.Start(ctx, core.StartOptions{
		Config:         []byte(o.Config),
		StatsOutbounds: o.StatsOutbounds,
		LogWriter:      o.LogWriter,
		AssetDir:       o.AssetDir,
	})
	if err != nil {
		return err
	}
	s.running = c
	return nil
}

// Stop stops the running core; a no-op when nothing runs. A non-empty
// coreID must name the running core.
func (s *Service) Stop(coreID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running == nil {
		return nil
	}
	if coreID != "" && coreID != s.running.ID() {
		return fmt.Errorf("%s is not running", coreID)
	}
	err := s.running.Stop()
	s.running = nil
	return err
}

// Status returns the running core id, or "" when none runs.
func (s *Service) Status() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running != nil {
		return s.running.ID()
	}
	return ""
}

// QueryStats returns the cumulative traffic of one outbound tag of the
// running core. Zero when nothing runs or on any error.
func (s *Service) QueryStats(tag string) core.Traffic {
	s.mu.Lock()
	c := s.running
	s.mu.Unlock()
	if c == nil {
		return core.Traffic{}
	}
	t, err := c.Stats(tag)
	if err != nil {
		return core.Traffic{}
	}
	return t
}

// RunningDialer dials through the running core's proxy outbound; release
// it with the returned func. core.ErrNotRunning when nothing runs.
func (s *Service) RunningDialer(ctx context.Context) (core.Dialer, func(), error) {
	return s.dialerFor(ctx, "", "")
}

// dialerFor resolves the dialer a test should use: a throw-away instance
// of coreID when config is non-empty, otherwise the running core. ctx is
// handed to the core so a throw-away instance can carry it.
func (s *Service) dialerFor(ctx context.Context, coreID, config string) (core.Dialer, func(), error) {
	if config != "" {
		c, err := core.Get(CoreID(coreID))
		if err != nil {
			return nil, nil, err
		}
		return c.Dialer(ctx, []byte(config))
	}
	s.mu.Lock()
	c := s.running
	s.mu.Unlock()
	if c == nil {
		return nil, nil, core.ErrNotRunning
	}
	return c.Dialer(ctx, nil)
}
