package plugin

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRehydrate_SinglePlaceholder(t *testing.T) {
	result := Rehydrate("User {} logged in", []string{"alice"})
	want := "User alice logged in"
	if result != want {
		t.Errorf("got %q, want %q", result, want)
	}
}

func TestRehydrate_MultiplePlaceholders(t *testing.T) {
	result := Rehydrate("User {} performed {} on {}", []string{"alice", "DELETE", "orders"})
	want := "User alice performed DELETE on orders"
	if result != want {
		t.Errorf("got %q, want %q", result, want)
	}
}

func TestRehydrate_NoPlaceholders(t *testing.T) {
	result := Rehydrate("Server started", []string{})
	if result != "Server started" {
		t.Errorf("got %q, want %q", result, "Server started")
	}
}

func TestRegistry(t *testing.T) {
	registryMu.Lock()
	registry[1] = "User {} logged in"
	registry[2] = "Order {} processed"
	templateIndex["User {} logged in"] = 1
	templateIndex["Order {} processed"] = 2
	registryMu.Unlock()

	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		delete(registry, 2)
		delete(templateIndex, "User {} logged in")
		delete(templateIndex, "Order {} processed")
		registryMu.Unlock()
	}()

	registryMu.RLock()
	tpl, ok := registry[1]
	tid, hasIndex := templateIndex["User {} logged in"]
	registryMu.RUnlock()

	if !ok || tpl != "User {} logged in" {
		t.Errorf("expected template for tid=1, got %q", tpl)
	}
	if !hasIndex || tid != 1 {
		t.Errorf("expected reverse index tid=1 for template, got %d", tid)
	}
}

func TestSearchFilter_MatchesRehydrated(t *testing.T) {
	// Simulate rehydrated log lines and search filtering
	lines := []struct {
		body     string
		severity string
	}{
		{"User alice logged in", "info"},
		{"Order 42 processed", "info"},
		{"User bob logged in", "info"},
		{"Error in module payments", "warning"},
	}

	search := "alice"
	searchLower := strings.ToLower(search)

	var matched []string
	for _, l := range lines {
		if strings.Contains(strings.ToLower(l.body), searchLower) {
			matched = append(matched, l.body)
		}
	}

	if len(matched) != 1 {
		t.Fatalf("expected 1 match, got %d", len(matched))
	}
	if matched[0] != "User alice logged in" {
		t.Errorf("got %q, want %q", matched[0], "User alice logged in")
	}
}

func TestSearchFilter_CaseInsensitive(t *testing.T) {
	body := "User Alice logged in"
	search := "alice"

	if !strings.Contains(strings.ToLower(body), strings.ToLower(search)) {
		t.Error("search should be case-insensitive")
	}
}

func TestSearchFilter_EmptySearchMatchesAll(t *testing.T) {
	lines := []string{"line1", "line2", "line3"}
	search := ""

	var matched []string
	for _, l := range lines {
		if search == "" || strings.Contains(strings.ToLower(l), strings.ToLower(search)) {
			matched = append(matched, l)
		}
	}

	if len(matched) != 3 {
		t.Errorf("empty search should match all, got %d matches", len(matched))
	}
}

func TestSearchFilter_NoMatch(t *testing.T) {
	lines := []string{"User alice logged in", "Order 42 processed"}
	search := "charlie"
	searchLower := strings.ToLower(search)

	var matched []string
	for _, l := range lines {
		if strings.Contains(strings.ToLower(l), searchLower) {
			matched = append(matched, l)
		}
	}

	if len(matched) != 0 {
		t.Errorf("expected 0 matches, got %d", len(matched))
	}
}

func TestLevelFromPrefix(t *testing.T) {
	tests := []struct {
		prefix byte
		want   string
	}{
		{'I', "info"},
		{'W', "warning"},
		{'D', "debug"},
		{'E', "error"},
		{'T', "trace"},
	}
	for _, tt := range tests {
		got, ok := levelFromPrefix[tt.prefix]
		if !ok || got != tt.want {
			t.Errorf("prefix %c: got %q, want %q", tt.prefix, got, tt.want)
		}
	}
}

// --- RehydrateLine tests ---

func TestRehydrateLine_CompactFormat(t *testing.T) {
	// Set up mock registry
	registryMu.Lock()
	registry[1] = "User {} has logged in"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		registryMu.Unlock()
	}()

	rh := RehydrateLine(`I[1,"alice"]`)

	if rh.body != "User alice has logged in" {
		t.Errorf("got body %q, want %q", rh.body, "User alice has logged in")
	}
	if rh.severity != "info" {
		t.Errorf("got severity %q, want %q", rh.severity, "info")
	}
}

func TestRehydrateLine_MultipleParams(t *testing.T) {
	registryMu.Lock()
	registry[5] = "User {} performed {} on {}"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 5)
		registryMu.Unlock()
	}()

	rh := RehydrateLine(`W[5,"alice","DELETE","orders"]`)

	if rh.body != "User alice performed DELETE on orders" {
		t.Errorf("got body %q, want %q", rh.body, "User alice performed DELETE on orders")
	}
	if rh.severity != "warning" {
		t.Errorf("got severity %q, want %q", rh.severity, "warning")
	}
}

func TestRehydrateLine_UnknownTemplate(t *testing.T) {
	// Don't register template ID 999
	rh := RehydrateLine(`E[999,"alice","orders"]`)

	if !strings.Contains(rh.body, "[UNKNOWN TEMPLATE #999]") {
		t.Errorf("expected unknown template placeholder, got %q", rh.body)
	}
	if !strings.Contains(rh.body, "alice") {
		t.Errorf("expected params in placeholder, got %q", rh.body)
	}
	if !strings.Contains(rh.body, "orders") {
		t.Errorf("expected params in placeholder, got %q", rh.body)
	}
	if rh.severity != "error" {
		t.Errorf("got severity %q, want %q", rh.severity, "error")
	}
}

func TestRehydrateLine_NonCompactLine(t *testing.T) {
	rh := RehydrateLine("Just a plain log line")

	if rh.body != "Just a plain log line" {
		t.Errorf("got %q, want %q", rh.body, "Just a plain log line")
	}
	if rh.severity != "unknown" {
		t.Errorf("got severity %q, want %q", rh.severity, "unknown")
	}
}

func TestRehydrateLine_ShortLine(t *testing.T) {
	rh := RehydrateLine("ab")
	if rh.body != "ab" {
		t.Errorf("got %q, want %q", rh.body, "ab")
	}
}

func TestRehydrateLine_NoParams(t *testing.T) {
	registryMu.Lock()
	registry[10] = "Server started"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 10)
		registryMu.Unlock()
	}()

	rh := RehydrateLine(`I[10]`)

	if rh.body != "Server started" {
		t.Errorf("got %q, want %q", rh.body, "Server started")
	}
}

func TestRehydrateLine_WithMDC(t *testing.T) {
	registryMu.Lock()
	registry[1] = "User {} has logged in"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		registryMu.Unlock()
	}()

	rh := RehydrateLine(`I[1,"alice",{"traceId":"abc-123","userId":"alice"}]`)

	if !strings.Contains(rh.body, "User alice has logged in") {
		t.Errorf("expected rehydrated body, got %q", rh.body)
	}
	if !strings.Contains(rh.body, "traceId") {
		t.Errorf("expected MDC in output, got %q", rh.body)
	}
}

func TestRehydrateLine_AllLevels(t *testing.T) {
	registryMu.Lock()
	registry[1] = "msg {}"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		registryMu.Unlock()
	}()

	tests := []struct {
		input    string
		severity string
	}{
		{`I[1,"x"]`, "info"},
		{`W[1,"x"]`, "warning"},
		{`E[1,"x"]`, "error"},
		{`D[1,"x"]`, "debug"},
		{`T[1,"x"]`, "trace"},
	}

	for _, tt := range tests {
		rh := RehydrateLine(tt.input)
		if rh.severity != tt.severity {
			t.Errorf("input %q: got severity %q, want %q", tt.input, rh.severity, tt.severity)
		}
	}
}

// --- Persistence tests ---

func TestPersistence_SaveAndLoad(t *testing.T) {
	// Use a temp file for the registry
	tmpDir := t.TempDir()
	tmpFile := filepath.Join(tmpDir, "templates.json")

	oldPath := registryPath
	registryPath = tmpFile
	defer func() { registryPath = oldPath }()

	// Snapshot and restore state
	registryMu.Lock()
	origRegistry := make(map[int64]string)
	for k, v := range registry {
		origRegistry[k] = v
	}
	origIndex := make(map[string]int64)
	for k, v := range templateIndex {
		origIndex[k] = v
	}
	origNextID := nextID

	registry[100] = "Persistent template {}"
	registry[101] = "Another template {} {}"
	templateIndex["Persistent template {}"] = 100
	templateIndex["Another template {} {}"] = 101
	nextID = 101
	registryMu.Unlock()

	defer func() {
		registryMu.Lock()
		for k := range registry {
			delete(registry, k)
		}
		for k := range templateIndex {
			delete(templateIndex, k)
		}
		for k, v := range origRegistry {
			registry[k] = v
		}
		for k, v := range origIndex {
			templateIndex[k] = v
		}
		nextID = origNextID
		registryMu.Unlock()
	}()

	saveRegistryToFile()

	// Verify file exists and is valid JSON
	data, err := os.ReadFile(tmpFile)
	if err != nil {
		t.Fatalf("failed to read registry file: %v", err)
	}
	var loaded map[int64]string
	if err := json.Unmarshal(data, &loaded); err != nil {
		t.Fatalf("invalid JSON in registry file: %v", err)
	}
	if loaded[100] != "Persistent template {}" {
		t.Errorf("got %q, want %q", loaded[100], "Persistent template {}")
	}

	// Clear registry and reload from file — should rebuild templateIndex and nextID
	registryMu.Lock()
	for k := range registry {
		delete(registry, k)
	}
	for k := range templateIndex {
		delete(templateIndex, k)
	}
	nextID = 0
	registryMu.Unlock()

	loadRegistryFromFile()

	registryMu.RLock()
	tpl, ok := registry[100]
	tid, hasIdx := templateIndex["Persistent template {}"]
	currentNextID := nextID
	registryMu.RUnlock()

	if !ok || tpl != "Persistent template {}" {
		t.Errorf("after reload: got %q, want %q", tpl, "Persistent template {}")
	}
	if !hasIdx || tid != 100 {
		t.Errorf("after reload: templateIndex should map to 100, got %d", tid)
	}
	if currentNextID != 101 {
		t.Errorf("after reload: nextID should be 101, got %d", currentNextID)
	}
}

func TestPersistence_MissingFileIsNotError(t *testing.T) {
	oldPath := registryPath
	registryPath = "/nonexistent/path/templates.json"
	defer func() { registryPath = oldPath }()

	// Should not panic
	loadRegistryFromFile()
}

func TestServerSideSearchFilter_OnRehydratedLogs(t *testing.T) {
	// Simulates the full server-side search pipeline:
	// 1. Compact logs come from Loki
	// 2. RehydrateLine converts them
	// 3. Search filter is applied on rehydrated text

	registryMu.Lock()
	registry[1] = "User {} has logged in"
	registry[2] = "Order {} processed for {}"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		delete(registry, 2)
		registryMu.Unlock()
	}()

	compactLines := []string{
		`I[1,"alice"]`,
		`I[1,"bob"]`,
		`I[2,"42","alice"]`,
		`W[2,"99","charlie"]`,
	}

	search := "alice"
	searchLower := strings.ToLower(search)

	var matched []string
	for _, line := range compactLines {
		rh := RehydrateLine(line)
		if strings.Contains(strings.ToLower(rh.body), searchLower) {
			matched = append(matched, rh.body)
		}
	}

	if len(matched) != 2 {
		t.Fatalf("expected 2 matches for 'alice', got %d: %v", len(matched), matched)
	}
	if matched[0] != "User alice has logged in" {
		t.Errorf("first match: got %q, want %q", matched[0], "User alice has logged in")
	}
	if matched[1] != "Order 42 processed for alice" {
		t.Errorf("second match: got %q, want %q", matched[1], "Order 42 processed for alice")
	}
}

// --- Severity detection for non-compact (fallback/passthrough) lines ---

func TestDetectSeverityFromText(t *testing.T) {
	tests := []struct {
		line string
		want string
	}{
		{"WARN Error in module payments", "warning"},
		{"ERROR Database error for alice", "error"},
		{"INFO User alice has logged in", "info"},
		{"DEBUG some debug message", "debug"},
		{"TRACE detailed trace", "trace"},
		{"Error in module payments", "error"},       // level passthrough (no brackets)
		{"Warning something wrong", "warning"},       // WARNING prefix
		{"Just a plain line", "unknown"},
		{"", "unknown"},
	}
	for _, tt := range tests {
		rh := RehydrateLine(tt.line)
		if rh.severity != tt.want {
			t.Errorf("line %q: got severity %q, want %q", tt.line, rh.severity, tt.want)
		}
	}
}

func TestRehydrateLine_FallbackLinePreservesSeverity(t *testing.T) {
	// Fallback output: "WARN Error in module payments"
	// Go should detect severity "warning" even though it's not compact format
	rh := RehydrateLine("WARN Error in module payments")
	if rh.severity != "warning" {
		t.Errorf("got severity %q, want %q", rh.severity, "warning")
	}
	if rh.body != "WARN Error in module payments" {
		t.Errorf("body should be unchanged, got %q", rh.body)
	}
}

// --- Stack trace vs multiline param detection ---

func TestLooksLikeStackTrace(t *testing.T) {
	// Real Java stack trace
	if !looksLikeStackTrace("java.lang.RuntimeException: broke\n\tat com.example.Foo.bar(Foo.java:42)") {
		t.Error("should detect Java stack trace")
	}
	// With truncated frames
	if !looksLikeStackTrace("java.lang.RuntimeException: broke\n\tat Foo.bar(Foo.java:42)\n\t... 5 more") {
		t.Error("should detect truncated stack trace")
	}
	// Just a multiline string (NOT a stack trace)
	if looksLikeStackTrace("line one\nline two\nline three") {
		t.Error("should NOT treat plain multiline string as stack trace")
	}
	// Empty
	if looksLikeStackTrace("") {
		t.Error("empty string is not a stack trace")
	}
}

// --- Search push-down tests ---

func withTestRegistry(t *testing.T, templates map[int64]string, fn func()) {
	t.Helper()
	registryMu.Lock()
	origRegistry := make(map[int64]string)
	for k, v := range registry {
		origRegistry[k] = v
	}
	origIndex := make(map[string]int64)
	for k, v := range templateIndex {
		origIndex[k] = v
	}
	// Clear and set test data
	for k := range registry {
		delete(registry, k)
	}
	for k := range templateIndex {
		delete(templateIndex, k)
	}
	for k, v := range templates {
		registry[k] = v
		templateIndex[v] = k
	}
	registryMu.Unlock()

	defer func() {
		registryMu.Lock()
		for k := range registry {
			delete(registry, k)
		}
		for k := range templateIndex {
			delete(templateIndex, k)
		}
		for k, v := range origRegistry {
			registry[k] = v
		}
		for k, v := range origIndex {
			templateIndex[k] = v
		}
		registryMu.Unlock()
	}()

	fn()
}

func TestFindTemplatesContaining(t *testing.T) {
	templates := map[int64]string{
		1: "User {} authenticated via {}",
		2: "Order {} processed",
		3: "User {} created order {}",
		4: "@mdc:traceId,userId",
	}

	withTestRegistry(t, templates, func() {
		// "authenticated" matches only template 1
		tids := findTemplatesContaining("authenticated")
		if len(tids) != 1 || tids[0] != 1 {
			t.Errorf("expected [1], got %v", tids)
		}

		// "User" matches templates 1 and 3
		tids = findTemplatesContaining("User")
		if len(tids) != 2 {
			t.Errorf("expected 2 matches for 'User', got %v", tids)
		}

		// "alice" matches nothing (it's a parameter value)
		tids = findTemplatesContaining("alice")
		if len(tids) != 0 {
			t.Errorf("expected 0 matches for 'alice', got %v", tids)
		}

		// MDC templates should be skipped
		tids = findTemplatesContaining("traceId")
		if len(tids) != 0 {
			t.Errorf("expected 0 matches (MDC skipped), got %v", tids)
		}

		// Case insensitive
		tids = findTemplatesContaining("AUTHENTICATED")
		if len(tids) != 1 {
			t.Errorf("expected 1 match (case insensitive), got %v", tids)
		}
	})
}

func TestBuildSearchFilters_EmptySearch(t *testing.T) {
	result := buildSearchFilters("")
	if result != "" {
		t.Errorf("expected empty, got %q", result)
	}
}

func TestBuildSearchFilters_ShortWordsSkipped(t *testing.T) {
	// Words shorter than 3 chars should be skipped
	result := buildSearchFilters("in a to")
	if result != "" {
		t.Errorf("short words should be skipped, got %q", result)
	}
}

func TestBuildSearchFilters_ParameterWord(t *testing.T) {
	templates := map[int64]string{
		1: "User {} authenticated via {}",
	}

	withTestRegistry(t, templates, func() {
		// "alice" is not in any template — pure parameter word
		result := buildSearchFilters("alice")
		if !strings.Contains(result, `(?i)alice`) {
			t.Errorf("expected literal filter for param word, got %q", result)
		}
		if strings.Contains(result, `\[`) {
			t.Errorf("should not contain TID filter for param word, got %q", result)
		}
	})
}

func TestBuildSearchFilters_TemplateWord(t *testing.T) {
	templates := map[int64]string{
		1: "User {} authenticated via {}",
		2: "Order {} processed",
	}

	withTestRegistry(t, templates, func() {
		// "authenticated" is in template 1
		result := buildSearchFilters("authenticated")
		if !strings.Contains(result, "1") {
			t.Errorf("expected TID 1 in filter, got %q", result)
		}
		// Should also have literal fallback
		if !strings.Contains(result, `(?i)authenticated`) {
			t.Errorf("expected literal fallback, got %q", result)
		}
	})
}

func TestBuildSearchFilters_MixedWords(t *testing.T) {
	templates := map[int64]string{
		1: "User {} authenticated via {}",
	}

	withTestRegistry(t, templates, func() {
		// "authenticated" = template word, "alice" = parameter word
		result := buildSearchFilters("authenticated alice")
		if !strings.Contains(result, "1") {
			t.Errorf("expected TID filter, got %q", result)
		}
		if !strings.Contains(result, `(?i)alice`) {
			t.Errorf("expected literal filter for 'alice', got %q", result)
		}
	})
}

func TestBuildSearchFilters_SpecialRegexChars(t *testing.T) {
	templates := map[int64]string{
		1: "User {} logged in",
	}

	withTestRegistry(t, templates, func() {
		// Dot should be escaped
		result := buildSearchFilters("user.name")
		if !strings.Contains(result, `user\.name`) {
			t.Errorf("expected escaped dot, got %q", result)
		}
	})
}

func TestBuildSeverityFilter_Empty(t *testing.T) {
	result := buildSeverityFilter(map[string]bool{})
	if result != "" {
		t.Errorf("expected empty, got %q", result)
	}
}

func TestBuildSeverityFilter_SingleLevel(t *testing.T) {
	result := buildSeverityFilter(map[string]bool{"error": true})
	if !strings.Contains(result, "E") {
		t.Errorf("expected compact prefix E, got %q", result)
	}
	if !strings.Contains(result, "ERROR") {
		t.Errorf("expected fallback prefix ERROR, got %q", result)
	}
}

func TestBuildSeverityFilter_MultipleLevels(t *testing.T) {
	result := buildSeverityFilter(map[string]bool{"info": true, "error": true})
	// Should contain both prefixes
	if !strings.Contains(result, "I") || !strings.Contains(result, "E") {
		t.Errorf("expected both compact prefixes, got %q", result)
	}
}

func TestRehydrateLine_MultilineParamNotConfusedWithStackTrace(t *testing.T) {
	registryMu.Lock()
	registry[1] = "Content: {}"
	registryMu.Unlock()
	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		registryMu.Unlock()
	}()

	// A param containing newlines that is NOT a stack trace
	// In JSON: the \n inside the string is an escaped newline
	rh := RehydrateLine(`I[1,"line1\nline2"]`)

	if !strings.Contains(rh.body, "line1") {
		t.Errorf("multiline param should be a template parameter, got body %q", rh.body)
	}
	if !strings.Contains(rh.body, "Content:") {
		t.Errorf("template should be rehydrated, got body %q", rh.body)
	}
}
