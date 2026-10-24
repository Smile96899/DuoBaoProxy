package mobile

import (
	"fmt"

	"github.com/sagernet/sing-box/log"
)

// writeLog forwards one line to the host log handler, if any.
func writeLog(level log.Level, line string) {
	if h := currentLogHandler(); h != nil {
		h.WriteLog(int32(level), line)
	}
}

func logf(level log.Level, format string, args ...any) {
	if currentLogHandler() == nil {
		return
	}
	writeLog(level, "[mobile] "+fmt.Sprintf(format, args...))
}
