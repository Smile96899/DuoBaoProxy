package main

import (
	"os"
	"regexp"
	"sync"
	"time"
)

var ansi = regexp.MustCompile("\x1b\\[[0-9;]*m")

// logFile is a tiny size-capped log sink: when the file exceeds maxBytes
// it is truncated and writing starts over. It also echoes to stderr so the
// GUI can show live core output.
type logFile struct {
	mu       sync.Mutex
	path     string
	maxBytes int64
	f        *os.File
}

func newLogFile(path string, maxBytes int64) (*logFile, error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil, err
	}
	return &logFile{path: path, maxBytes: maxBytes, f: f}, nil
}

func (l *logFile) WriteLine(line string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	msg := time.Now().Format("2006-01-02 15:04:05 ") + line + "\n"
	_, _ = os.Stderr.WriteString(msg)
	if st, err := l.f.Stat(); err == nil && st.Size() > l.maxBytes {
		_ = l.f.Truncate(0)
		_, _ = l.f.Seek(0, 0)
	}
	_, _ = l.f.WriteString(msg)
}
