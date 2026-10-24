package singbox

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCacheFilePath(t *testing.T) {
	defer func(old string) { CacheFile = old }(CacheFile)
	CacheFile = ""
	if got, want := cacheFilePath(), filepath.Join(os.TempDir(), "duobaoproxy-singbox-cache.db"); got != want {
		t.Errorf("default = %q, want %q (desktop path changed)", got, want)
	}
	CacheFile = "/data/user/0/app/files/cache.db"
	if got := cacheFilePath(); got != CacheFile {
		t.Errorf("override = %q", got)
	}
}
