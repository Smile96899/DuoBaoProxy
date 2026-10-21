package probe

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"hproxy/core"
)

// Probes the GUI runs one at a time per node. Each returns a JSON report;
// latency-like probes also return milliseconds for sorting.

const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

// get fetches url through d and returns status, final URL and up to 512 KiB of body.
func get(ctx context.Context, d core.Dialer, rawURL string, timeout time.Duration, header map[string]string) (int, string, string, error) {
	client := HTTPClient(d, timeout)
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, "", "", err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	for k, v := range header {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, "", "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	return resp.StatusCode, resp.Request.URL.String(), string(b), nil
}

// ---- loss / jitter -----------------------------------------------------------

type PingStats struct {
	Avg    int32   `json:"avg"`
	Min    int32   `json:"min"`
	Max    int32   `json:"max"`
	Jitter int32   `json:"jitter"`
	Loss   float64 `json:"loss"` // 0..1
}

// TCPPingStats connects to addr count times and summarises the results.
func TCPPingStats(ctx context.Context, addr string, count int, timeout time.Duration) PingStats {
	if count <= 0 {
		count = 10
	}
	var ok []int32
	for i := 0; i < count && ctx.Err() == nil; i++ {
		if ms, err := TCPPing(ctx, addr, timeout); err == nil {
			ok = append(ok, max(ms, 1))
		}
		if i < count-1 {
			time.Sleep(150 * time.Millisecond)
		}
	}
	s := PingStats{Loss: float64(count-len(ok)) / float64(count)}
	if len(ok) == 0 {
		return s
	}
	s.Min, s.Max = ok[0], ok[0]
	var sum, diff int64
	for i, v := range ok {
		sum += int64(v)
		s.Min, s.Max = min(s.Min, v), max(s.Max, v)
		if i > 0 {
			diff += int64(math.Abs(float64(v - ok[i-1])))
		}
	}
	s.Avg = int32(sum / int64(len(ok)))
	if len(ok) > 1 {
		s.Jitter = int32(diff / int64(len(ok)-1))
	}
	return s
}

// ---- speed -------------------------------------------------------------------

// DownloadSpeed returns bytes per second downloading url through d. A
// timeout mid-transfer still counts what arrived.
func DownloadSpeed(ctx context.Context, d core.Dialer, rawURL string, timeout time.Duration) (float64, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	client := HTTPClient(d, 0)
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	start := time.Now()
	n, _ := io.Copy(io.Discard, resp.Body)
	if n == 0 {
		return 0, fmt.Errorf("no data")
	}
	return float64(n) / math.Max(time.Since(start).Seconds(), 0.001), nil
}

type countingReader struct {
	left int64
	sent *int64
}

func (r *countingReader) Read(p []byte) (int, error) {
	if r.left <= 0 {
		return 0, io.EOF
	}
	n := int64(len(p))
	if n > r.left {
		n = r.left
	}
	clear(p[:n])
	r.left -= n
	*r.sent += n
	return int(n), nil
}

// UploadSpeed posts size bytes to url through d and returns bytes per second.
func UploadSpeed(ctx context.Context, d core.Dialer, rawURL string, size int64, timeout time.Duration) (float64, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	client := HTTPClient(d, 0)
	defer client.CloseIdleConnections()
	var sent int64
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, rawURL, &countingReader{left: size, sent: &sent})
	if err != nil {
		return 0, err
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")
	start := time.Now()
	resp, err := client.Do(req)
	secs := math.Max(time.Since(start).Seconds(), 0.001)
	if resp != nil {
		resp.Body.Close()
	}
	if sent == 0 {
		if err == nil {
			err = fmt.Errorf("no data sent")
		}
		return 0, err
	}
	return float64(sent) / secs, nil
}

// ---- exit IP and country -----------------------------------------------------

type Geo struct {
	IP      string `json:"ip"`
	Country string `json:"cc"`
}

// ExitGeo reads ip= and loc= from a Cloudflare trace style page.
func ExitGeo(ctx context.Context, d core.Dialer, traceURL string, timeout time.Duration) (Geo, error) {
	if traceURL == "" {
		traceURL = "https://www.cloudflare.com/cdn-cgi/trace"
	}
	_, _, body, err := get(ctx, d, traceURL, timeout, nil)
	if err != nil {
		return Geo{}, err
	}
	var g Geo
	for _, line := range strings.Split(body, "\n") {
		if v, ok := strings.CutPrefix(line, "ip="); ok {
			g.IP = strings.TrimSpace(v)
		} else if v, ok := strings.CutPrefix(line, "loc="); ok {
			g.Country = strings.TrimSpace(v)
		}
	}
	if g.IP == "" {
		return g, fmt.Errorf("no ip in trace")
	}
	return g, nil
}

// ---- unlock checks -----------------------------------------------------------

// Service is one site's verdict: ok, the region it serves, and a note.
type Service struct {
	OK     bool   `json:"ok"`
	Region string `json:"region,omitempty"`
	Note   string `json:"note,omitempty"`
}

var regionPath = regexp.MustCompile(`^/([a-z]{2})(?:-[a-z]{2})?/`)

func netflix(ctx context.Context, d core.Dialer, t time.Duration) Service {
	// A licensed (non-original) title: 200 means the full catalogue.
	status, final, _, err := get(ctx, d, "https://www.netflix.com/title/81280792", t, nil)
	if err != nil {
		return Service{Note: "连接失败"}
	}
	region := "US"
	if u, err := url.Parse(final); err == nil {
		if m := regionPath.FindStringSubmatch(u.Path); m != nil {
			region = strings.ToUpper(m[1])
		}
	}
	switch {
	case status == 200:
		return Service{OK: true, Region: region}
	case status == 404:
		return Service{Region: region, Note: "仅自制剧"}
	default:
		return Service{Note: fmt.Sprintf("HTTP %d", status)}
	}
}

func disney(ctx context.Context, d core.Dialer, t time.Duration) Service {
	status, final, _, err := get(ctx, d, "https://www.disneyplus.com/", t, nil)
	if err != nil {
		return Service{Note: "连接失败"}
	}
	if status >= 400 || strings.Contains(final, "unavailable") || strings.Contains(final, "preview") {
		return Service{Note: "不可用"}
	}
	return Service{OK: true}
}

var ytRegion = regexp.MustCompile(`"(?:countryCode|INNERTUBE_CONTEXT_GL)":"([A-Z]{2})"`)

func youtubePremium(ctx context.Context, d core.Dialer, t time.Duration) Service {
	status, _, body, err := get(ctx, d, "https://www.youtube.com/premium", t, nil)
	if err != nil {
		return Service{Note: "连接失败"}
	}
	region := ""
	if m := ytRegion.FindStringSubmatch(body); m != nil {
		region = m[1]
	}
	if status >= 400 || strings.Contains(body, "Premium is not available in your country") {
		return Service{Region: region, Note: "不可用"}
	}
	return Service{OK: true, Region: region}
}

func traceLoc(ctx context.Context, d core.Dialer, host string, t time.Duration) string {
	_, _, body, err := get(ctx, d, "https://"+host+"/cdn-cgi/trace", t, nil)
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(body, "\n") {
		if v, ok := strings.CutPrefix(line, "loc="); ok {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func chatgpt(ctx context.Context, d core.Dialer, t time.Duration) Service {
	status, _, body, err := get(ctx, d, "https://api.openai.com/compliance/cookie_requirements", t, map[string]string{"Authorization": "Bearer null"})
	if err != nil {
		return Service{Note: "连接失败"}
	}
	region := traceLoc(ctx, d, "chatgpt.com", t)
	if strings.Contains(body, "unsupported_country") {
		return Service{Region: region, Note: "地区不支持"}
	}
	if status >= 500 {
		return Service{Region: region, Note: fmt.Sprintf("HTTP %d", status)}
	}
	return Service{OK: true, Region: region}
}

func claude(ctx context.Context, d core.Dialer, t time.Duration) Service {
	status, final, _, err := get(ctx, d, "https://claude.ai/", t, nil)
	if err != nil {
		return Service{Note: "连接失败"}
	}
	region := traceLoc(ctx, d, "claude.ai", t)
	if strings.Contains(final, "unavailable") {
		return Service{Region: region, Note: "地区不支持"}
	}
	if status == 403 {
		return Service{Region: region, Note: "被拦截"}
	}
	return Service{OK: status < 400, Region: region}
}

func gemini(ctx context.Context, d core.Dialer, t time.Duration) Service {
	_, _, body, err := get(ctx, d, "https://gemini.google.com/", t, nil)
	if err != nil {
		return Service{Note: "连接失败"}
	}
	// The app page embeds this flag only where Gemini is offered.
	if strings.Contains(body, "45631641,null,true") {
		return Service{OK: true}
	}
	return Service{Note: "地区不支持"}
}

type check = func(context.Context, core.Dialer, time.Duration) Service

// pick keeps the checks named in only (comma separated); empty keeps all.
func pick(all map[string]check, only string) map[string]check {
	if strings.TrimSpace(only) == "" {
		return all
	}
	out := map[string]check{}
	for _, name := range strings.Split(only, ",") {
		if f, ok := all[strings.TrimSpace(name)]; ok {
			out[strings.TrimSpace(name)] = f
		}
	}
	return out
}

// runAll runs named checks concurrently and collects them into a map.
func runAll(ctx context.Context, d core.Dialer, t time.Duration, checks map[string]check) map[string]Service {
	type kv struct {
		k string
		v Service
	}
	ch := make(chan kv, len(checks))
	for k, f := range checks {
		go func() { ch <- kv{k, f(ctx, d, t)} }()
	}
	out := make(map[string]Service, len(checks))
	for range checks {
		r := <-ch
		out[r.k] = r.v
	}
	return out
}

func MediaUnlock(ctx context.Context, d core.Dialer, t time.Duration, only string) map[string]Service {
	return runAll(ctx, d, t, pick(map[string]check{"netflix": netflix, "disney": disney, "youtube": youtubePremium}, only))
}

func AIUnlock(ctx context.Context, d core.Dialer, t time.Duration, only string) map[string]Service {
	return runAll(ctx, d, t, pick(map[string]check{"chatgpt": chatgpt, "claude": claude, "gemini": gemini}, only))
}

// ---- DNS leak (bash.ws) ------------------------------------------------------

type DNSServer struct {
	IP      string `json:"ip"`
	CC      string `json:"cc"`
	Country string `json:"country"`
	ASN     string `json:"asn"`
}

// Leak means a DNS server in the user's own country (found without the
// proxy) saw the test names, i.e. lookups went out next to the tunnel.
type DNSLeak struct {
	Leak    bool        `json:"leak"`
	Local   string      `json:"local"` // country of this machine's own connection
	Servers []DNSServer `json:"servers"`
}

// DNSLeakTest asks bash.ws for a test id, makes the names resolve both on
// this machine (where a leak would happen) and through the node, then reads
// which DNS servers bash.ws saw.
func DNSLeakTest(ctx context.Context, d core.Dialer, t time.Duration) (DNSLeak, error) {
	_, _, id, err := get(ctx, d, "https://bash.ws/id", t, nil)
	id = strings.TrimSpace(id)
	if err != nil || id == "" {
		return DNSLeak{}, fmt.Errorf("bash.ws 不可用")
	}
	for i := 1; i <= 6; i++ {
		name := fmt.Sprintf("%d.%s.bash.ws", i, id)
		lctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		_, _ = net.DefaultResolver.LookupHost(lctx, name)
		cancel()
		if c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(name, "80")); err == nil {
			c.Close()
		}
	}
	_, _, body, err := get(ctx, d, "https://bash.ws/dnsleak/test/"+id+"?json", t, nil)
	if err != nil {
		return DNSLeak{}, err
	}
	var rows []struct {
		IP          string `json:"ip"`
		Country     string `json:"country"`
		CountryName string `json:"country_name"`
		ASN         string `json:"asn"`
		Type        string `json:"type"`
	}
	if err := json.Unmarshal([]byte(body), &rows); err != nil {
		return DNSLeak{}, fmt.Errorf("bash.ws 返回格式不对")
	}
	r := DNSLeak{Local: localCountry(ctx, t)}
	for _, row := range rows {
		if row.Type != "dns" {
			continue
		}
		cc := strings.ToUpper(row.Country)
		r.Servers = append(r.Servers, DNSServer{IP: row.IP, CC: cc, Country: row.CountryName, ASN: row.ASN})
		if r.Local != "" && cc == r.Local {
			r.Leak = true
		}
	}
	return r, nil
}

// localCountry asks Cloudflare, without the proxy, where this machine is.
func localCountry(ctx context.Context, t time.Duration) string {
	return traceLoc(ctx, core.SystemDialer{}, "www.cloudflare.com", t)
}
