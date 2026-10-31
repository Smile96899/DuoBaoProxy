package mobile

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"time"
)

// maxSubscriptionBody caps a subscription download.
const maxSubscriptionBody = 16 << 20

// SubResult is one subscription download.
type SubResult struct {
	// Body is the response body (links, base64 blob or Clash YAML).
	Body string
	// UserInfo is the raw subscription-userinfo header, "" when absent.
	UserInfo string
	// Status is the final HTTP status code; the desktop treats anything
	// but 200 as a failure ("更新失败：HTTP <status>").
	Status int32
}

// FetchSubscription downloads a subscription directly (never through the
// VPN or any proxy: sockets are protected on Android), following redirects.
// userAgent "" means "DuoBaoProxy/<version>", the desktop default; timeoutMs
// <= 0 means 30 s. A non-200 status is not an error: the result carries it.
func FetchSubscription(url, userAgent string, timeoutMs int32) (*SubResult, error) {
	return fetchSubscription(url, userAgent, timeoutMs, false)
}

// FetchSubscriptionProxied is FetchSubscription through the running
// engine's proxy outbound (通过代理更新); it fails when nothing runs.
func FetchSubscriptionProxied(url, userAgent string, timeoutMs int32) (*SubResult, error) {
	return fetchSubscription(url, userAgent, timeoutMs, true)
}

func fetchSubscription(url, userAgent string, timeoutMs int32, proxied bool) (*SubResult, error) {
	timeout := 30 * time.Second
	if timeoutMs > 0 {
		timeout = time.Duration(timeoutMs) * time.Millisecond
	}
	if userAgent == "" {
		userAgent = "DuoBaoProxy/" + version
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	dial := protectedDialer().DialContext
	if proxied {
		d, release, err := svc.RunningDialer(ctx)
		if err != nil {
			return nil, err
		}
		defer release()
		dial = d.DialContext
	}
	tr := &http.Transport{
		Proxy:               nil, // direct, like the desktop's Qt fetch
		DialContext:         dial,
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: timeout,
	}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxSubscriptionBody+1))
	if err != nil {
		return nil, err
	}
	if len(body) > maxSubscriptionBody {
		return nil, fmt.Errorf("订阅内容超过 %d MiB", maxSubscriptionBody>>20)
	}
	return &SubResult{
		Body:     string(body),
		UserInfo: resp.Header.Get("Subscription-Userinfo"),
		Status:   int32(resp.StatusCode),
	}, nil
}
