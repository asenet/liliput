package plugin

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/grafana/grafana-plugin-sdk-go/backend"
	"github.com/grafana/grafana-plugin-sdk-go/backend/instancemgmt"
	"github.com/grafana/grafana-plugin-sdk-go/data"
)

// Global template registry shared across all datasource instances.
// The server is the single source of truth for template IDs — clients
// send only the template text and receive a server-assigned ID back.
var (
	registry      = make(map[int64]string) // tid → template
	templateIndex = make(map[string]int64) // template → tid (reverse index)
	nextID        int64                    // last assigned ID
	registryMu    sync.RWMutex
	registryPath  string // file path for persistent storage
)

func init() {
	registryPath = defaultRegistryPath()
	loadRegistryFromFile()
}

// defaultRegistryPath returns the path to the persistent registry file.
// Uses LILIPUT_REGISTRY_PATH env var if set, otherwise falls back to
// a file in the user's config directory.
func defaultRegistryPath() string {
	if p := os.Getenv("LILIPUT_REGISTRY_PATH"); p != "" {
		return p
	}
	configDir, err := os.UserConfigDir()
	if err != nil {
		configDir = os.TempDir()
	}
	dir := filepath.Join(configDir, "liliput")
	os.MkdirAll(dir, 0755)
	return filepath.Join(dir, "templates.json")
}

// loadRegistryFromFile loads the template registry from the persistent JSON file
// and rebuilds the reverse index and next ID counter.
func loadRegistryFromFile() {
	if registryPath == "" {
		return
	}
	data, err := os.ReadFile(registryPath)
	if err != nil {
		return // file doesn't exist yet — that's fine
	}
	var loaded map[int64]string
	if err := json.Unmarshal(data, &loaded); err != nil {
		backend.Logger.Warn("Failed to parse registry file", "path", registryPath, "err", err)
		return
	}
	registryMu.Lock()
	for k, v := range loaded {
		registry[k] = v
		templateIndex[v] = k
		if k > nextID {
			nextID = k
		}
	}
	registryMu.Unlock()
	backend.Logger.Info("Loaded templates from file", "path", registryPath, "count", len(loaded))
}

// saveRegistryToFile persists the current registry to disk.
func saveRegistryToFile() {
	if registryPath == "" {
		return
	}
	registryMu.RLock()
	data, err := json.Marshal(registry)
	registryMu.RUnlock()
	if err != nil {
		backend.Logger.Warn("Failed to marshal registry", "err", err)
		return
	}
	if err := os.WriteFile(registryPath, data, 0644); err != nil {
		backend.Logger.Warn("Failed to write registry file", "path", registryPath, "err", err)
	}
}

// SetRegistryPath overrides the persistent storage path (for testing).
func SetRegistryPath(path string) {
	registryPath = path
}

// RegisterRequest is the JSON body for POST /register.
// Clients send only the template text; the server assigns the ID.
type RegisterRequest struct {
	Tpl string `json:"tpl"`
}

// RegisterResponse is returned from POST /register with the server-assigned ID.
type RegisterResponse struct {
	TID int64 `json:"tid"`
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

// rehydratedLine holds the result of parsing and rehydrating a compact log line.
type rehydratedLine struct {
	body     string
	severity string
}

// looksLikeStackTrace checks if a string looks like a Java stack trace
// (contains "\n\tat " or "\n\t... " patterns), rather than just any multiline string.
func looksLikeStackTrace(s string) bool {
	return strings.Contains(s, "\n\tat ") || strings.Contains(s, "\n\t... ")
}

// detectSeverityFromText attempts to detect the severity level from a plain-text
// log line prefix (e.g. "WARN Error in module payments" from fallback mode or
// "Error in module payments" from level passthrough).
func detectSeverityFromText(line string) string {
	upper := strings.ToUpper(line)
	prefixes := []struct {
		prefix string
		level  string
	}{
		{"ERROR ", "error"},
		{"WARN ", "warning"},
		{"WARNING ", "warning"},
		{"INFO ", "info"},
		{"DEBUG ", "debug"},
		{"TRACE ", "trace"},
	}
	for _, p := range prefixes {
		if strings.HasPrefix(upper, p.prefix) {
			return p.level
		}
	}
	return "unknown"
}

// RehydrateLine parses a single log line. If it's in compact format (e.g. I[1,"alice"]),
// it looks up the template and produces a full message. If the template is missing,
// it returns a placeholder. Non-compact lines are returned with severity detected
// from their text prefix (for fallback/passthrough logs).
func RehydrateLine(line string) rehydratedLine {
	if len(line) < 3 {
		return rehydratedLine{body: line, severity: detectSeverityFromText(line)}
	}

	level, ok := levelFromPrefix[line[0]]
	if !ok || line[1] != '[' {
		return rehydratedLine{body: line, severity: detectSeverityFromText(line)}
	}

	var arr []json.RawMessage
	if err := json.Unmarshal([]byte(line[1:]), &arr); err != nil || len(arr) < 1 {
		return rehydratedLine{body: line, severity: detectSeverityFromText(line)}
	}

	var tid int64
	if err := json.Unmarshal(arr[0], &tid); err != nil || tid <= 0 {
		return rehydratedLine{body: line, severity: detectSeverityFromText(line)}
	}

	// Extract params (skip MDC objects/arrays and stack traces for rehydration placeholders).
	params := make([]string, 0, len(arr)-1)
	var extraParts []string
	for j := 1; j < len(arr); j++ {
		raw := arr[j]
		trimmed := strings.TrimSpace(string(raw))
		// If it's a JSON object (uncompressed MDC map), treat as extra context.
		if len(trimmed) > 0 && trimmed[0] == '{' {
			extraParts = append(extraParts, trimmed)
			continue
		}
		// If it's a JSON array, check if it's a compressed MDC: [schemaId, val1, val2, ...]
		if len(trimmed) > 0 && trimmed[0] == '[' {
			mdcJSON := expandCompressedMDC(trimmed)
			if mdcJSON != "" {
				extraParts = append(extraParts, mdcJSON)
				continue
			}
			// Not a valid compressed MDC — treat as regular param
		}
		var v interface{}
		json.Unmarshal(raw, &v)
		s := fmt.Sprintf("%v", v)
		// Only treat as stack trace if it actually looks like a Java stack trace,
		// not just any multiline string (which could be a legitimate parameter).
		if looksLikeStackTrace(s) {
			extraParts = append(extraParts, s)
			continue
		}
		params = append(params, s)
	}

	registryMu.RLock()
	tpl, found := registry[tid]
	registryMu.RUnlock()

	if found {
		body := Rehydrate(tpl, params)
		if len(extraParts) > 0 {
			body += "\n" + strings.Join(extraParts, "\n")
		}
		return rehydratedLine{body: body, severity: level}
	}

	// Template not found — produce a placeholder instead of failing.
	placeholder := fmt.Sprintf("[UNKNOWN TEMPLATE #%d] %s", tid, strings.Join(params, " "))
	if len(extraParts) > 0 {
		placeholder += "\n" + strings.Join(extraParts, "\n")
	}
	return rehydratedLine{body: placeholder, severity: level}
}

// expandCompressedMDC takes a JSON array like [schemaId,"val1","val2"] where schemaId
// refers to a registered "@mdc:key1,key2" template, and reconstructs the full MDC JSON
// object like {"key1":"val1","key2":"val2"}. Returns "" if it's not a valid compressed MDC.
func expandCompressedMDC(rawArray string) string {
	var arr []json.RawMessage
	if err := json.Unmarshal([]byte(rawArray), &arr); err != nil || len(arr) < 1 {
		return ""
	}

	var schemaID int64
	if err := json.Unmarshal(arr[0], &schemaID); err != nil || schemaID <= 0 {
		return ""
	}

	registryMu.RLock()
	schema, found := registry[schemaID]
	registryMu.RUnlock()

	if !found || !strings.HasPrefix(schema, "@mdc:") {
		return ""
	}

	keys := strings.Split(strings.TrimPrefix(schema, "@mdc:"), ",")
	if len(keys) != len(arr)-1 {
		return ""
	}

	// Build JSON object from keys + values
	mdcMap := make(map[string]string, len(keys))
	for i, key := range keys {
		var val string
		if err := json.Unmarshal(arr[i+1], &val); err != nil {
			val = strings.Trim(string(arr[i+1]), "\"")
		}
		mdcMap[key] = val
	}

	result, _ := json.Marshal(mdcMap)
	return string(result)
}

// findTemplatesContaining returns template IDs whose static text (with {} removed)
// contains the given word (case-insensitive). MDC schemas (@mdc:...) are skipped.
func findTemplatesContaining(word string) []int64 {
	wordLower := strings.ToLower(word)
	var tids []int64
	registryMu.RLock()
	for tid, tpl := range registry {
		if strings.HasPrefix(tpl, "@mdc:") {
			continue
		}
		// Remove placeholders and check if the word appears in static text
		static := strings.ToLower(strings.ReplaceAll(tpl, "{}", ""))
		if strings.Contains(static, wordLower) {
			tids = append(tids, tid)
		}
	}
	registryMu.RUnlock()
	return tids
}

// buildSearchFilters builds LogQL filter stages to push search down to Loki.
// For each search word it produces a |~ filter that matches either:
//   - compact lines with matching template IDs (for words found in template text), or
//   - the literal word in the stored line (for parameter values and fallback lines).
//
// All filters are AND-ed (each |~ stage narrows the result set).
func buildSearchFilters(search string) string {
	words := strings.Fields(search)
	if len(words) == 0 {
		return ""
	}

	var filters []string
	for _, word := range words {
		if len(word) < 3 {
			// Very short words match too broadly — skip push-down,
			// the post-rehydration filter will handle them.
			continue
		}

		escaped := regexp.QuoteMeta(word)
		tids := findTemplatesContaining(word)

		if len(tids) == 0 {
			// Pure parameter word — must appear literally in the line.
			filters = append(filters, fmt.Sprintf(`|~ "(?i)%s"`, escaped))
		} else if len(tids) > 20 {
			// Too many matching templates — a broad literal filter is more efficient.
			filters = append(filters, fmt.Sprintf(`|~ "(?i)%s"`, escaped))
		} else {
			// Word found in template text — match by TID OR literal presence.
			// TID pattern matches compact lines like I[1,...] or W[3,...]
			tidStrs := make([]string, len(tids))
			for i, tid := range tids {
				tidStrs[i] = fmt.Sprintf("%d", tid)
			}
			tidAlt := strings.Join(tidStrs, "|")
			filters = append(filters, fmt.Sprintf(`|~ "(\\[(%s)[,\\]]|(?i)%s)"`, tidAlt, escaped))
		}
	}

	if len(filters) == 0 {
		return ""
	}
	return " " + strings.Join(filters, " ")
}

// buildSeverityFilter builds a LogQL filter for severity level prefixes.
// Compact lines start with a level character (I, W, E, D, T); fallback lines
// start with the level word (INFO, WARN, ERROR, etc.).
func buildSeverityFilter(severityFilter map[string]bool) string {
	if len(severityFilter) == 0 {
		return ""
	}

	// Map severity names to compact prefixes and fallback prefixes
	prefixMap := map[string]struct{ compact, fallback string }{
		"info":    {"I", "INFO"},
		"warning": {"W", "WARN"},
		"error":   {"E", "ERROR"},
		"debug":   {"D", "DEBUG"},
		"trace":   {"T", "TRACE"},
	}

	var compactPrefixes []string
	var fallbackPrefixes []string
	for sev := range severityFilter {
		if p, ok := prefixMap[sev]; ok {
			compactPrefixes = append(compactPrefixes, p.compact)
			fallbackPrefixes = append(fallbackPrefixes, p.fallback)
		}
	}

	if len(compactPrefixes) == 0 {
		return ""
	}

	// Match compact format (e.g. "I[" or "W[") or fallback format (e.g. "INFO " or "WARN ")
	compactAlt := strings.Join(compactPrefixes, "|")
	fallbackAlt := strings.Join(fallbackPrefixes, "|")
	return fmt.Sprintf(` |~ "^((%s)\\[|(%s) )"`, compactAlt, fallbackAlt)
}

// defaultSelector queries Loki for available labels and builds a catch-all
// stream selector using the first label found. Caches result for performance.
func (d *Datasource) defaultSelector() string {
	resp, err := d.httpClient.Get(d.lokiURL + "/loki/api/v1/labels")
	if err != nil {
		return `{job=~".+"}`
	}
	defer resp.Body.Close()

	var result struct {
		Data []string `json:"data"`
	}
	body, _ := io.ReadAll(resp.Body)
	if json.Unmarshal(body, &result) != nil || len(result.Data) == 0 {
		return `{job=~".+"}`
	}

	// Use first non-internal label
	for _, l := range result.Data {
		if l != "__name__" {
			return fmt.Sprintf(`{%s=~".+"}`, l)
		}
	}
	return `{job=~".+"}`
}

// buildLokiSelector builds a LogQL stream selector from a label map.
// Picks the most specific label to avoid overly broad queries.
func buildLokiSelector(labels map[string]string) string {
	if len(labels) == 0 {
		return `{job=~".+"}`
	}
	// Prefer compose_service, then container_name, then job, then first available.
	preferred := []string{"compose_service", "container_name", "job"}
	for _, key := range preferred {
		if val, ok := labels[key]; ok && val != "" {
			return fmt.Sprintf(`{%s="%s"}`, key, val)
		}
	}
	// Fallback: use first label
	for k, v := range labels {
		return fmt.Sprintf(`{%s="%s"}`, k, v)
	}
	return `{job=~".+"}`
}

// extractLokiSearchFilter builds LogQL |= filter chain from a raw compact log line.
// From W[1,"alice",17,6,"27741.55","PLN"] it produces:
//
//	|= "[1," |= "alice" |= "27741.55" |= "PLN"
//
// Each |= narrows results further, uniquely identifying the line.
func extractLokiSearchFilter(raw string) string {
	if len(raw) < 3 {
		return ""
	}

	// Compact format: X[tid, ...] where X is level prefix
	if _, ok := levelFromPrefix[raw[0]]; ok && raw[1] == '[' {
		var parts []string

		// Extract template ID prefix like "[1,"
		for i := 2; i < len(raw); i++ {
			if raw[i] == ',' || raw[i] == ']' {
				parts = append(parts, raw[1:i+1])
				break
			}
		}

		// Extract all quoted string values
		inQuote := false
		start := 0
		for i := 2; i < len(raw); i++ {
			if raw[i] == '"' && (i == 0 || raw[i-1] != '\\') {
				if !inQuote {
					inQuote = true
					start = i + 1
				} else {
					val := raw[start:i]
					if len(val) >= 3 {
						parts = append(parts, val)
					}
					inQuote = false
				}
			}
		}

		if len(parts) == 0 {
			return ""
		}

		var filters []string
		for _, p := range parts {
			filters = append(filters, fmt.Sprintf(`|= "%s"`, p))
		}
		return strings.Join(filters, " ")
	}

	// Fallback plain text: strip quotes, take first 50 chars
	safe := strings.ReplaceAll(raw, `"`, "")
	if len(safe) > 50 {
		safe = safe[:50]
	}
	return fmt.Sprintf(`|= "%s"`, safe)
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
		httpClient: &http.Client{Timeout: 120 * time.Second},
	}, nil
}

func (d *Datasource) Dispose() {}

// CallResource handles /register, /templates, /labels and /label/*/values endpoints.
func (d *Datasource) CallResource(_ context.Context, req *backend.CallResourceRequest, sender backend.CallResourceResponseSender) error {
	switch {
	case req.Path == "register" && req.Method == "POST":
		return d.handleRegister(req, sender)
	case req.Path == "templates" && req.Method == "GET":
		return d.handleTemplates(sender)
	case req.Path == "labels" && req.Method == "GET":
		return d.proxyLokiGet("/loki/api/v1/labels", sender)
	case strings.HasPrefix(req.Path, "label/") && strings.HasSuffix(req.Path, "/values") && req.Method == "GET":
		// req.Path is "label/{name}/values"
		labelName := strings.TrimSuffix(strings.TrimPrefix(req.Path, "label/"), "/values")
		return d.proxyLokiGet("/loki/api/v1/label/"+url.PathEscape(labelName)+"/values", sender)
	default:
		return sender.Send(&backend.CallResourceResponse{Status: 404, Body: []byte("not found")})
	}
}

// proxyLokiGet forwards a GET request to Loki and returns the response body.
func (d *Datasource) proxyLokiGet(path string, sender backend.CallResourceResponseSender) error {
	resp, err := d.httpClient.Get(d.lokiURL + path)
	if err != nil {
		return sender.Send(&backend.CallResourceResponse{Status: 502, Body: []byte("loki request failed: " + err.Error())})
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return sender.Send(&backend.CallResourceResponse{Status: 502, Body: []byte("reading loki response: " + err.Error())})
	}

	return sender.Send(&backend.CallResourceResponse{
		Status:  resp.StatusCode,
		Headers: map[string][]string{"Content-Type": {"application/json"}},
		Body:    body,
	})
}

func (d *Datasource) handleRegister(req *backend.CallResourceRequest, sender backend.CallResourceResponseSender) error {
	var r RegisterRequest
	if err := json.Unmarshal(req.Body, &r); err != nil || r.Tpl == "" {
		return sender.Send(&backend.CallResourceResponse{Status: 400, Body: []byte("missing or invalid tpl")})
	}

	registryMu.Lock()
	// Return existing ID if this template was already registered (idempotent).
	if existingID, exists := templateIndex[r.Tpl]; exists {
		registryMu.Unlock()
		resp, _ := json.Marshal(RegisterResponse{TID: existingID})
		return sender.Send(&backend.CallResourceResponse{
			Status:  200,
			Headers: map[string][]string{"Content-Type": {"application/json"}},
			Body:    resp,
		})
	}
	// Assign new sequential ID.
	nextID++
	tid := nextID
	registry[tid] = r.Tpl
	templateIndex[r.Tpl] = tid
	registryMu.Unlock()

	// Persist to disk after each new registration.
	saveRegistryToFile()

	backend.Logger.Info("Registered template", "tid", tid, "tpl", r.Tpl)
	resp, _ := json.Marshal(RegisterResponse{TID: tid})
	return sender.Send(&backend.CallResourceResponse{
		Status:  200,
		Headers: map[string][]string{"Content-Type": {"application/json"}},
		Body:    resp,
	})
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

	if qm.QueryType == "costs" {
		return d.queryCosts(q)
	}

	if qm.QueryType == "templates" {
		return d.queryTemplates()
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

	// Push search and severity filters down to Loki for efficient pre-filtering.
	augmentedExpr := qm.Expr
	if augmentedExpr == "" {
		augmentedExpr = d.defaultSelector()
	}
	augmentedExpr += buildSeverityFilter(severityFilter)
	augmentedExpr += buildSearchFilters(searchLower)

	backend.Logger.Info("Querying Loki", "augmentedExpr", augmentedExpr)

	lokiLines, err := d.queryLoki(q, augmentedExpr)
	if err != nil {
		return backend.DataResponse{Error: err}
	}

	backend.Logger.Info("Loki returned", "lines", len(lokiLines))

	timestamps := make([]time.Time, 0, len(lokiLines))
	bodies := make([]string, 0, len(lokiLines))
	severities := make([]string, 0, len(lokiLines))
	rawLines := make([]string, 0, len(lokiLines))
	lokiSearch := make([]string, 0, len(lokiLines))
	labelsCol := make([]json.RawMessage, 0, len(lokiLines))
	var firstLabels map[string]string

	for _, line := range lokiLines {
		rh := RehydrateLine(line.body)

		if len(severityFilter) > 0 && !severityFilter[rh.severity] {
			continue
		}
		if searchLower != "" && !strings.Contains(strings.ToLower(rh.body), searchLower) {
			continue
		}

		if firstLabels == nil {
			firstLabels = line.labels
		}
		timestamps = append(timestamps, line.ts)
		bodies = append(bodies, rh.body)
		severities = append(severities, rh.severity)
		rawLines = append(rawLines, line.body)
		lokiSearch = append(lokiSearch, extractLokiSearchFilter(line.body))
		lb, _ := json.Marshal(line.labels)
		labelsCol = append(labelsCol, lb)
	}

	if len(timestamps) == 0 {
		return backend.DataResponse{}
	}

	frame := data.NewFrame("logs",
		data.NewField("labels", nil, labelsCol),
		data.NewField("timestamp", nil, timestamps),
		data.NewField("body", nil, bodies),
		data.NewField("severity", nil, severities),
		data.NewField("lokiSearchKey", nil, lokiSearch),
		data.NewField("rawLog", nil, rawLines),
	)
	frame.SetMeta(&data.FrameMeta{
		Type:                   data.FrameTypeLogLines,
		TypeVersion:            data.FrameTypeVersion{0, 1},
		PreferredVisualization: data.VisTypeLogs,
	})

	// Add data link on lokiSearchKey field.
	lokiSelector := buildLokiSelector(firstLabels)
	searchKeyField := frame.Fields[4] // lokiSearchKey
	searchKeyField.Config = &data.FieldConfig{
		Links: []data.DataLink{
			{
				Title: "View in Loki (raw)",
				Internal: &data.InternalDataLink{
					DatasourceUID:  "loki-raw",
					DatasourceName: "Loki (raw)",
					Query: map[string]interface{}{
						"refId": "A",
						"expr":  lokiSelector + ` ${__value.raw}`,
					},
				},
			},
		},
	}

	// Make raw log visible in log detail view.
	rawField := frame.Fields[5] // rawLog
	rawField.Config = &data.FieldConfig{
		Custom: map[string]interface{}{
			"hidden": false,
		},
	}

	return backend.DataResponse{Frames: data.Frames{frame}}
}

// Logging service ingestion prices per GB (USD).
// These are approximate public prices as of 2025 for the most popular services.
var logPricingPerGB = map[string]float64{
	"aws_cloudwatch": 0.50,  // AWS CloudWatch Logs ingestion
	"datadog":        0.10,  // Datadog Log Management ingestion
	"grafana_cloud":  0.50,  // Grafana Cloud Logs
	"elastic_cloud":  0.95,  // Elastic Cloud ingestion
	"newrelic":       0.35,  // New Relic (beyond free tier)
	"splunk_cloud":   2.00,  // Splunk Cloud (approximate)
	"gcp_logging":    0.50,  // Google Cloud Logging
	"azure_monitor":  2.76,  // Azure Monitor Logs ingestion
}

func (d *Datasource) queryStats(q backend.DataQuery) backend.DataResponse {
	lokiLines, err := d.queryLokiWithLimit(q, "", 0)
	if err != nil {
		return backend.DataResponse{Error: err}
	}

	var compactBytes int64
	var fullBytes int64

	for _, line := range lokiLines {
		compactBytes += int64(len(line.body))

		rh := RehydrateLine(line.body)
		if rh.severity != "unknown" {
			full := fmt.Sprintf("%s %s", rh.severity, rh.body)
			fullBytes += int64(len(full))
		} else {
			fullBytes += int64(len(line.body))
		}
	}

	savingsPct := float64(0)
	if fullBytes > 0 {
		savingsPct = float64(fullBytes-compactBytes) / float64(fullBytes) * 100
	}

	savedBytes := fullBytes - compactBytes

	// Extrapolate to monthly savings based on query time range.
	queryDuration := q.TimeRange.To.Sub(q.TimeRange.From)
	monthlyMultiplier := float64(0)
	if queryDuration.Seconds() > 0 {
		monthlyMultiplier = (30 * 24 * 3600) / queryDuration.Seconds()
	}
	monthlySavedBytes := float64(savedBytes) * monthlyMultiplier
	monthlyFullBytes := float64(fullBytes) * monthlyMultiplier
	monthlySavedGB := monthlySavedBytes / 1e9
	monthlyFullGB := monthlyFullBytes / 1e9

	frame := data.NewFrame("stats",
		data.NewField("compact_bytes", nil, []float64{float64(compactBytes)}),
		data.NewField("full_bytes", nil, []float64{float64(fullBytes)}),
		data.NewField("savings_percent", nil, []float64{savingsPct}),
		data.NewField("saved_bytes", nil, []float64{float64(savedBytes)}),
		data.NewField("monthly_full_bytes", nil, []float64{monthlyFullBytes}),
		data.NewField("monthly_saved_bytes", nil, []float64{monthlySavedBytes}),
	)

	// Add per-provider monthly cost savings fields to the stats frame.
	// Sorted for deterministic field order.
	providerOrder := []struct {
		key  string
		name string
	}{
		{"aws_cloudwatch", "AWS CloudWatch"},
		{"azure_monitor", "Azure Monitor"},
		{"datadog", "Datadog"},
		{"elastic_cloud", "Elastic Cloud"},
		{"gcp_logging", "GCP Logging"},
		{"grafana_cloud", "Grafana Cloud"},
		{"newrelic", "New Relic"},
		{"splunk_cloud", "Splunk Cloud"},
	}

	for _, p := range providerOrder {
		pricePerGB := logPricingPerGB[p.key]
		costWithout := monthlyFullGB * pricePerGB
		costWith := (monthlyFullGB - monthlySavedGB) * pricePerGB
		saved := costWithout - costWith
		frame.Fields = append(frame.Fields,
			data.NewField("cost_monthly_without_"+p.key, nil, []float64{costWithout}),
			data.NewField("cost_monthly_with_"+p.key, nil, []float64{costWith}),
			data.NewField("cost_saved_"+p.key, nil, []float64{saved}),
		)
	}

	return backend.DataResponse{Frames: data.Frames{frame}}
}

// queryCosts returns a table frame with per-provider monthly cost comparison.
func (d *Datasource) queryCosts(q backend.DataQuery) backend.DataResponse {
	lokiLines, err := d.queryLokiWithLimit(q, "", 0)
	if err != nil {
		return backend.DataResponse{Error: err}
	}

	var compactBytes, fullBytes int64
	for _, line := range lokiLines {
		compactBytes += int64(len(line.body))
		rh := RehydrateLine(line.body)
		if rh.severity != "unknown" {
			fullBytes += int64(len(fmt.Sprintf("%s %s", rh.severity, rh.body)))
		} else {
			fullBytes += int64(len(line.body))
		}
	}

	savedBytes := fullBytes - compactBytes
	queryDuration := q.TimeRange.To.Sub(q.TimeRange.From)
	monthlyMultiplier := float64(0)
	if queryDuration.Seconds() > 0 {
		monthlyMultiplier = (30 * 24 * 3600) / queryDuration.Seconds()
	}
	monthlySavedGB := float64(savedBytes) / 1e9 * monthlyMultiplier
	monthlyFullGB := float64(fullBytes) / 1e9 * monthlyMultiplier

	providerOrder := []struct {
		key  string
		name string
	}{
		{"aws_cloudwatch", "AWS CloudWatch"},
		{"azure_monitor", "Azure Monitor"},
		{"datadog", "Datadog"},
		{"elastic_cloud", "Elastic Cloud"},
		{"gcp_logging", "GCP Logging"},
		{"grafana_cloud", "Grafana Cloud"},
		{"newrelic", "New Relic"},
		{"splunk_cloud", "Splunk Cloud"},
	}

	providers := make([]string, 0, len(providerOrder))
	pricesPerGB := make([]float64, 0, len(providerOrder))
	costsWithout := make([]float64, 0, len(providerOrder))
	costsWith := make([]float64, 0, len(providerOrder))
	costsSaved := make([]float64, 0, len(providerOrder))

	for _, p := range providerOrder {
		pricePerGB := logPricingPerGB[p.key]
		costWithout := monthlyFullGB * pricePerGB
		costWith := (monthlyFullGB - monthlySavedGB) * pricePerGB
		providers = append(providers, p.name)
		pricesPerGB = append(pricesPerGB, pricePerGB)
		costsWithout = append(costsWithout, costWithout)
		costsWith = append(costsWith, costWith)
		costsSaved = append(costsSaved, costWithout-costWith)
	}

	frame := data.NewFrame("cost_comparison",
		data.NewField("Provider", nil, providers),
		data.NewField("Price/GB", nil, pricesPerGB),
		data.NewField("Without Liliput", nil, costsWithout),
		data.NewField("With Liliput", nil, costsWith),
		data.NewField("Saved/mo", nil, costsSaved),
	)

	return backend.DataResponse{Frames: data.Frames{frame}}
}

func (d *Datasource) queryTemplates() backend.DataResponse {
	registryMu.RLock()
	ids := make([]int64, 0, len(registry))
	tpls := make([]string, 0, len(registry))
	placeholders := make([]int64, 0, len(registry))
	types := make([]string, 0, len(registry))
	for id, tpl := range registry {
		ids = append(ids, id)
		if strings.HasPrefix(tpl, "@mdc:") {
			tpls = append(tpls, strings.TrimPrefix(tpl, "@mdc:"))
			keys := strings.Split(strings.TrimPrefix(tpl, "@mdc:"), ",")
			placeholders = append(placeholders, int64(len(keys)))
			types = append(types, "mdc")
		} else {
			tpls = append(tpls, tpl)
			placeholders = append(placeholders, int64(strings.Count(tpl, "{}")))
			types = append(types, "log")
		}
	}
	registryMu.RUnlock()

	// Sort by ID
	for i := 0; i < len(ids)-1; i++ {
		for j := i + 1; j < len(ids); j++ {
			if ids[i] > ids[j] {
				ids[i], ids[j] = ids[j], ids[i]
				tpls[i], tpls[j] = tpls[j], tpls[i]
				placeholders[i], placeholders[j] = placeholders[j], placeholders[i]
				types[i], types[j] = types[j], types[i]
			}
		}
	}

	frame := data.NewFrame("templates",
		data.NewField("ID", nil, ids),
		data.NewField("Type", nil, types),
		data.NewField("Template", nil, tpls),
		data.NewField("Params", nil, placeholders),
	)

	return backend.DataResponse{Frames: data.Frames{frame}}
}

type logLine struct {
	ts     time.Time
	body   string
	labels map[string]string
}

func (d *Datasource) queryLoki(q backend.DataQuery, logQL string) ([]logLine, error) {
	return d.queryLokiWithLimit(q, logQL, 5000)
}

func (d *Datasource) queryLokiWithLimit(q backend.DataQuery, logQL string, maxLines int) ([]logLine, error) {
	if logQL == "" {
		var qm struct {
			Expr string `json:"expr"`
		}
		if err := json.Unmarshal(q.JSON, &qm); err == nil && qm.Expr != "" {
			logQL = qm.Expr
		} else {
			logQL = d.defaultSelector()
		}
	}

	// For bounded queries, do a single fetch.
	if maxLines > 0 && maxLines <= 5000 {
		return d.fetchLokiPage(logQL, q.TimeRange.From.UnixNano(), q.TimeRange.To.UnixNano(), maxLines)
	}

	// For large/unbounded queries, paginate in batches of 5000 (Loki's default limit).
	// We walk backward through time, using the oldest timestamp from each batch
	// as the new "end" for the next page.
	const batchSize = 5000
	const maxPages = 20 // cap at 100k lines
	if maxLines <= 0 {
		maxLines = maxPages * batchSize
	}

	var allLines []logLine
	endNs := q.TimeRange.To.UnixNano()
	startNs := q.TimeRange.From.UnixNano()
	page := 0

	for len(allLines) < maxLines {
		if page > 0 {
			time.Sleep(100 * time.Millisecond)
		}
		page++

		batch, err := d.fetchLokiPage(logQL, startNs, endNs, batchSize)
		if err != nil {
			if page > 1 {
				// Return what we have so far instead of failing.
				backend.Logger.Warn("Loki pagination stopped early", "page", page, "err", err, "linesCollected", len(allLines))
				break
			}
			return nil, err
		}

		allLines = append(allLines, batch...)

		if len(batch) < batchSize {
			break // no more data
		}

		// Find the oldest timestamp in this batch to use as next page boundary.
		oldestNs := endNs
		for _, line := range batch {
			if ns := line.ts.UnixNano(); ns < oldestNs {
				oldestNs = ns
			}
		}

		// Move end to just before the oldest entry to avoid duplicates.
		endNs = oldestNs - 1
		if endNs <= startNs {
			break
		}
	}

	backend.Logger.Info("queryLokiWithLimit done", "totalLines", len(allLines), "maxLines", maxLines)
	return allLines, nil
}

// fetchLokiPage performs a single Loki query_range request.
func (d *Datasource) fetchLokiPage(logQL string, startNs, endNs int64, limit int) ([]logLine, error) {
	lokiURL := fmt.Sprintf("%s/loki/api/v1/query_range", d.lokiURL)
	params := url.Values{
		"query":     {logQL},
		"start":     {fmt.Sprintf("%d", startNs)},
		"end":       {fmt.Sprintf("%d", endNs)},
		"limit":     {fmt.Sprintf("%d", limit)},
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

	if resp.StatusCode != 200 {
		preview := string(body)
		if len(preview) > 300 {
			preview = preview[:300]
		}
		return nil, fmt.Errorf("loki returned status %d: %s", resp.StatusCode, preview)
	}

	var lokiResp LokiQueryResponse
	if err := json.Unmarshal(body, &lokiResp); err != nil {
		preview := string(body)
		if len(preview) > 200 {
			preview = preview[:200]
		}
		return nil, fmt.Errorf("parsing loki response: %w (body: %s)", err, preview)
	}

	var lines []logLine
	for _, stream := range lokiResp.Data.Result {
		for _, entry := range stream.Values {
			if len(entry) < 2 {
				continue
			}
			var ns int64
			fmt.Sscanf(entry[0], "%d", &ns)
			lines = append(lines, logLine{ts: time.Unix(0, ns), body: entry[1], labels: stream.Stream})
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
