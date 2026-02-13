package plugin

import (
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
	registryMu.Unlock()

	defer func() {
		registryMu.Lock()
		delete(registry, 1)
		delete(registry, 2)
		registryMu.Unlock()
	}()

	registryMu.RLock()
	tpl, ok := registry[1]
	registryMu.RUnlock()

	if !ok || tpl != "User {} logged in" {
		t.Errorf("expected template for tid=1, got %q", tpl)
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
