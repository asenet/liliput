package plugin

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/grafana/grafana-plugin-sdk-go/backend"
	"github.com/grafana/grafana-plugin-sdk-go/backend/instancemgmt"
	"github.com/grafana/grafana-plugin-sdk-go/data"
)

// Global template registry shared across all datasource instances.
var (
	registry   = make(map[int64]string)
	registryMu sync.RWMutex
)

// RegisterRequest is the JSON body for POST /register.
type RegisterRequest struct {
	TID int64  `json:"tid"`
	Tpl string `json:"tpl"`
}

// levelFromPrefix maps a single-char level prefix to a severity string.
var levelFromPrefix = map[byte]string{
	'T': "trace",
	'D': "debug",
	'I': "info",
	'W': "warning",
	'E': "error",
}

// LokiQueryResponse is the relevant subset of Loki's query_range response.
type LokiQueryResponse struct {
	Data struct {
		Result []LokiStream `json:"result"`
	} `json:"data"`
}

type LokiStream struct {
	Stream map[string]string `json:"stream"`
	Values [][]string        `json:"values"` // [timestamp_ns, line]
}

// Rehydrate replaces each {} placeholder with the corresponding param.
func Rehydrate(template string, params []string) string {
	result := template
	for _, p := range params {
		result = strings.Replace(result, "{}", p, 1)
	}
	return result
}

// Datasource implements backend.QueryDataHandler and backend.CallResourceHandler.
type Datasource struct {
	lokiURL    string
	httpClient *http.Client
}

type datasourceSettings struct {
	LokiURL string `json:"lokiUrl"`
}

func NewDatasource(_ context.Context, settings backend.DataSourceInstanceSettings) (instancemgmt.Instance, error) {
	var s datasourceSettings
	if err := json.Unmarshal(settings.JSONData, &s); err != nil {
		s.LokiURL = "http://loki:3100"
	}
	if s.LokiURL == "" {
		s.LokiURL = "http://loki:3100"
	}
	return &Datasource{
		lokiURL:    s.LokiURL,
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}, nil
}

func (d *Datasource) Dispose() {}

// CallResource handles /register and /templates endpoints.
func (d *Datasource) CallResource(_ context.Context, req *backend.CallResourceRequest, sender backend.CallResourceResponseSender) error {
	switch {
	case req.Path == "register" && req.Method == "POST":
		return d.handleRegister(req, sender)
	case req.Path == "templates" && req.Method == "GET":
		return d.handleTemplates(sender)
	default:
		return sender.Send(&backend.CallResourceResponse{Status: 404, Body: []byte("not found")})
	}
}

func (d *Datasource) handleRegister(req *backend.CallResourceRequest, sender backend.CallResourceResponseSender) error {
	var r RegisterRequest
	if err := json.Unmarshal(req.Body, &r); err != nil {
		return sender.Send(&backend.CallResourceResponse{Status: 400, Body: []byte(err.Error())})
	}

	registryMu.Lock()
	registry[r.TID] = r.Tpl
	registryMu.Unlock()

	backend.Logger.Info("Registered template", "tid", r.TID, "tpl", r.Tpl)
	return sender.Send(&backend.CallResourceResponse{Status: 200, Body: []byte("ok")})
}

func (d *Datasource) handleTemplates(sender backend.CallResourceResponseSender) error {
	registryMu.RLock()
	b, _ := json.Marshal(registry)
	registryMu.RUnlock()

	return sender.Send(&backend.CallResourceResponse{
		Status:  200,
		Headers: map[string][]string{"Content-Type": {"application/json"}},
		Body:    b,
	})
}

// QueryData proxies to Loki and rehydrates compact log lines.
func (d *Datasource) QueryData(_ context.Context, req *backend.QueryDataRequest) (*backend.QueryDataResponse, error) {
	response := backend.NewQueryDataResponse()

	for _, q := range req.Queries {
		response.Responses[q.RefID] = d.query(q)
	}

	return response, nil
}

func (d *Datasource) query(q backend.DataQuery) backend.DataResponse {
	// Parse query model from JSON.
	var qm struct {
		Expr      string `json:"expr"`
		Search    string `json:"search"`
		Severity  string `json:"severity"`
		QueryType string `json:"queryType"`
	}
	json.Unmarshal(q.JSON, &qm)

	if qm.QueryType == "stats" {
		return d.queryStats(q)
	}

	// Treat uninterpolated Grafana variables ($search, $severity) as empty.
	if strings.HasPrefix(qm.Search, "$") {
		qm.Search = ""
	}
	if strings.HasPrefix(qm.Severity, "$") {
		qm.Severity = ""
	}

	backend.Logger.Info("Query received", "expr", qm.Expr, "search", qm.Search, "severity", qm.Severity)
	searchLower := strings.ToLower(strings.TrimSpace(qm.Search))

	// Build severity allow-set from comma-separated or pipe-separated values.
	severityFilter := make(map[string]bool)
	if sv := strings.TrimSpace(qm.Severity); sv != "" && sv != "All" {
		for _, s := range strings.FieldsFunc(sv, func(r rune) bool { return r == ',' || r == '+' }) {
			s = strings.TrimSpace(strings.ToLower(s))
			if s != "" {
				severityFilter[s] = true
			}
		}
	}

	lokiLines, err := d.queryLoki(q)
	if err != nil {
		return backend.DataResponse{Error: err}
	}

	timestamps := make([]time.Time, 0, len(lokiLines))
	bodies := make([]string, 0, len(lokiLines))
	severities := make([]string, 0, len(lokiLines))

	for _, line := range lokiLines {
		body := line.body
		severity := "unknown"

		// Compact format: <level_char>[<tid>, <param1>, ...]
		if len(line.body) >= 3 {
			level, ok := levelFromPrefix[line.body[0]]
			if ok && line.body[1] == '[' {
				var arr []json.RawMessage
				if err := json.Unmarshal([]byte(line.body[1:]), &arr); err == nil && len(arr) >= 1 {
					var tid int64
					if err := json.Unmarshal(arr[0], &tid); err == nil && tid > 0 {
						registryMu.RLock()
						tpl, found := registry[tid]
						registryMu.RUnlock()

						if found {
							params := make([]string, len(arr)-1)
							for j := 1; j < len(arr); j++ {
								var v interface{}
								json.Unmarshal(arr[j], &v)
								params[j-1] = fmt.Sprintf("%v", v)
							}

							body = Rehydrate(tpl, params)
							severity = level
						}
					}
				}
			}
		}

		// Apply severity filter.
		if len(severityFilter) > 0 && !severityFilter[severity] {
			continue
		}

		// Apply full-text search filter on rehydrated body.
		if searchLower != "" && !strings.Contains(strings.ToLower(body), searchLower) {
			continue
		}

		timestamps = append(timestamps, line.ts)
		bodies = append(bodies, body)
		severities = append(severities, severity)
	}

	if len(timestamps) == 0 {
		return backend.DataResponse{}
	}

	frame := data.NewFrame("logs",
		data.NewField("timestamp", nil, timestamps),
		data.NewField("body", nil, bodies),
		data.NewField("severity", nil, severities),
	)
	frame.SetMeta(&data.FrameMeta{
		Type:                   data.FrameTypeLogLines,
		TypeVersion:            data.FrameTypeVersion{0, 1},
		PreferredVisualization: data.VisTypeLogs,
	})

	return backend.DataResponse{Frames: data.Frames{frame}}
}

func (d *Datasource) queryStats(q backend.DataQuery) backend.DataResponse {
	lokiLines, err := d.queryLoki(q)
	if err != nil {
		return backend.DataResponse{Error: err}
	}

	var compactBytes int64
	var fullBytes int64

	for _, line := range lokiLines {
		compactBytes += int64(len(line.body))

		// Try to rehydrate to get full size.
		if len(line.body) >= 3 {
			level, ok := levelFromPrefix[line.body[0]]
			if ok && line.body[1] == '[' {
				var arr []json.RawMessage
				if err := json.Unmarshal([]byte(line.body[1:]), &arr); err == nil && len(arr) >= 1 {
					var tid int64
					if err := json.Unmarshal(arr[0], &tid); err == nil && tid > 0 {
						registryMu.RLock()
						tpl, found := registry[tid]
						registryMu.RUnlock()

						if found {
							params := make([]string, len(arr)-1)
							for j := 1; j < len(arr); j++ {
								var v interface{}
								json.Unmarshal(arr[j], &v)
								params[j-1] = fmt.Sprintf("%v", v)
							}

							full := fmt.Sprintf("%s %s", level, Rehydrate(tpl, params))
							fullBytes += int64(len(full))
							continue
						}
					}
				}
			}
		}

		// Not compact — same size.
		fullBytes += int64(len(line.body))
	}

	savingsPct := float64(0)
	if fullBytes > 0 {
		savingsPct = float64(fullBytes-compactBytes) / float64(fullBytes) * 100
	}

	frame := data.NewFrame("stats",
		data.NewField("compact_bytes", nil, []float64{float64(compactBytes)}),
		data.NewField("full_bytes", nil, []float64{float64(fullBytes)}),
		data.NewField("savings_percent", nil, []float64{savingsPct}),
		data.NewField("line_count", nil, []float64{float64(len(lokiLines))}),
	)

	return backend.DataResponse{Frames: data.Frames{frame}}
}

type logLine struct {
	ts   time.Time
	body string
}

func (d *Datasource) queryLoki(q backend.DataQuery) ([]logLine, error) {
	// Default LogQL query — get all logs
	logQL := `{job=~".+"}`

	// Parse query JSON for custom LogQL
	var qm struct {
		Expr string `json:"expr"`
	}
	if err := json.Unmarshal(q.JSON, &qm); err == nil && qm.Expr != "" {
		logQL = qm.Expr
	}

	lokiURL := fmt.Sprintf("%s/loki/api/v1/query_range", d.lokiURL)
	params := url.Values{
		"query":     {logQL},
		"start":     {fmt.Sprintf("%d", q.TimeRange.From.UnixNano())},
		"end":       {fmt.Sprintf("%d", q.TimeRange.To.UnixNano())},
		"limit":     {"5000"},
		"direction": {"backward"},
	}

	resp, err := d.httpClient.Get(lokiURL + "?" + params.Encode())
	if err != nil {
		return nil, fmt.Errorf("loki request failed: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("reading loki response: %w", err)
	}

	var lokiResp LokiQueryResponse
	if err := json.Unmarshal(body, &lokiResp); err != nil {
		return nil, fmt.Errorf("parsing loki response: %w", err)
	}

	var lines []logLine
	for _, stream := range lokiResp.Data.Result {
		for _, entry := range stream.Values {
			if len(entry) < 2 {
				continue
			}
			nsStr := entry[0]
			var ns int64
			fmt.Sscanf(nsStr, "%d", &ns)
			ts := time.Unix(0, ns)

			lines = append(lines, logLine{ts: ts, body: entry[1]})
		}
	}

	return lines, nil
}

func (d *Datasource) CheckHealth(_ context.Context, _ *backend.CheckHealthRequest) (*backend.CheckHealthResult, error) {
	registryMu.RLock()
	count := len(registry)
	registryMu.RUnlock()

	return &backend.CheckHealthResult{
		Status:  backend.HealthStatusOk,
		Message: fmt.Sprintf("Liliput running, %d templates registered", count),
	}, nil
}
