package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeJules mimics the slice of the API we call. Each GET of the session
// advances it one state along `states`, which is how a real session looks
// to a poller.
type fakeJules struct {
	mu       sync.Mutex
	states   []string
	polls    int
	created  map[string]any
	messages []string
	approved bool
	key      string
}

func (f *fakeJules) handler(t *testing.T) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if r.Header.Get("X-Goog-Api-Key") != f.key {
			http.Error(w, `{"error":{"message":"bad key"}}`, 401)
			return
		}
		p := r.URL.Path
		switch {
		case r.Method == "POST" && p == "/sessions":
			body, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(body, &f.created)
			w.Write([]byte(`{"name":"sessions/42","id":"42","prompt":"x","state":"QUEUED",
				"sourceContext":{"source":"sources/github/o/r","githubRepoContext":{"startingBranch":"main"}}}`))
		case r.Method == "GET" && p == "/sessions/42":
			i := f.polls
			if i >= len(f.states) {
				i = len(f.states) - 1
			}
			f.polls++
			out := `{"name":"sessions/42","id":"42","title":"t","state":"` + f.states[i] + `"`
			if f.states[i] == "COMPLETED" {
				out += `,"outputs":[{"pullRequest":{"url":"https://github.com/o/r/pull/7","title":"Fix"}}]`
			}
			w.Write([]byte(out + "}"))
		case r.Method == "GET" && p == "/sessions/404":
			http.Error(w, `{"error":{"message":"not found"}}`, 404)
		case r.Method == "GET" && p == "/sessions/42/activities":
			// Two pages, so pagination is exercised.
			if r.URL.Query().Get("pageToken") == "" {
				w.Write([]byte(`{"activities":[
					{"id":"a1","createTime":"2026-09-27T10:00:01Z","originator":"agent",
					 "planGenerated":{"plan":{"id":"p1","steps":[{"title":"Read code"},{"title":"Write tests","index":1}]}}},
					{"id":"a2","createTime":"2026-09-27T10:00:05Z","originator":"agent",
					 "progressUpdated":{"title":"Ran bash command"},
					 "artifacts":[{"bashOutput":{"command":"go test ./...","output":"FAIL","exitCode":1}},
					              {"changeSet":{"gitPatch":{"unidiffPatch":"--- a/x\n+++ b/x\n","suggestedCommitMessage":"first"}}}]}
				],"nextPageToken":"p2"}`))
				return
			}
			w.Write([]byte(`{"activities":[
				{"id":"a3","createTime":"2026-09-27T10:05:00Z","originator":"agent",
				 "agentMessaged":{"agentMessage":"Should I also cover the CLI?"},
				 "artifacts":[{"changeSet":{"gitPatch":{"unidiffPatch":"--- a/y\n+++ b/y\n","suggestedCommitMessage":"test: add tests"}}}]}
			]}`))
		case r.Method == "POST" && p == "/sessions/42:sendMessage":
			var m map[string]string
			body, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(body, &m)
			f.messages = append(f.messages, m["prompt"])
			w.Write([]byte(`{}`))
		case r.Method == "POST" && p == "/sessions/42:approvePlan":
			f.approved = true
			w.Write([]byte(`{}`))
		case r.Method == "GET" && p == "/sources":
			w.Write([]byte(`{"sources":[{"name":"sources/github/Imlokzu/claude-bot","id":"github/Imlokzu/claude-bot"}]}`))
		default:
			t.Errorf("unexpected request %s %s", r.Method, r.URL)
			http.Error(w, "no", 500)
		}
	})
}

func setup(t *testing.T, states ...string) *fakeJules {
	t.Helper()
	f := &fakeJules{states: states, key: "test-key"}
	srv := httptest.NewServer(f.handler(t))
	t.Cleanup(srv.Close)
	t.Setenv("JULES_API_URL", srv.URL)
	t.Setenv("JULES_API_KEY", "test-key")
	t.Setenv("JULES_POLL_SECONDS", "1")
	return f
}

func client(t *testing.T) *Client {
	t.Helper()
	c, err := NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestNormalizeSource(t *testing.T) {
	want := "sources/github/Imlokzu/claude-bot"
	for _, in := range []string{
		"Imlokzu/claude-bot",
		"github/Imlokzu/claude-bot",
		"sources/github/Imlokzu/claude-bot",
		"https://github.com/Imlokzu/claude-bot.git",
		"https://github.com/Imlokzu/claude-bot/",
		"git@github.com:Imlokzu/claude-bot.git",
	} {
		got, err := NormalizeSource(in)
		if err != nil || got != want {
			t.Errorf("NormalizeSource(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	if _, err := NormalizeSource("just-a-name"); err == nil {
		t.Error("expected an error for a bare name")
	}
	t.Setenv("JULES_SOURCE", "o/r")
	if got, _ := NormalizeSource(""); got != "sources/github/o/r" {
		t.Errorf("JULES_SOURCE fallback gave %q", got)
	}
}

func TestCreateSessionBody(t *testing.T) {
	f := setup(t, "QUEUED")
	s, err := client(t).CreateSession(StartOptions{Prompt: "add tests", Source: "o/r", AutoPR: true})
	if err != nil {
		t.Fatal(err)
	}
	if s.ID != "42" {
		t.Errorf("id = %q", s.ID)
	}
	if f.created["automationMode"] != "AUTO_CREATE_PR" {
		t.Errorf("automationMode = %v", f.created["automationMode"])
	}
	sc := f.created["sourceContext"].(map[string]any)
	if sc["source"] != "sources/github/o/r" {
		t.Errorf("source = %v", sc["source"])
	}
	if sc["githubRepoContext"].(map[string]any)["startingBranch"] != "main" {
		t.Error("branch should default to main")
	}
	if _, ok := f.created["requirePlanApproval"]; ok {
		t.Error("requirePlanApproval should be omitted when false")
	}
}

func TestCreateSessionWithoutPR(t *testing.T) {
	f := setup(t, "QUEUED")
	if _, err := client(t).CreateSession(StartOptions{Prompt: "x", Source: "o/r"}); err != nil {
		t.Fatal(err)
	}
	if _, ok := f.created["automationMode"]; ok {
		t.Error("automationMode must be omitted when AutoPR is false")
	}
}

func TestWaitStopsWhenAgentNeedsYou(t *testing.T) {
	setup(t, "QUEUED", "PLANNING", "IN_PROGRESS", "AWAITING_USER_FEEDBACK")
	var seen []string
	s, err := client(t).Wait("42", time.Minute, 10*time.Millisecond, func(s *Session) { seen = append(seen, s.State) })
	if err != nil {
		t.Fatal(err)
	}
	if s.State != "AWAITING_USER_FEEDBACK" {
		t.Errorf("state = %s", s.State)
	}
	if strings.Join(seen, ",") != "QUEUED,PLANNING,IN_PROGRESS,AWAITING_USER_FEEDBACK" {
		t.Errorf("ticks = %v", seen)
	}
}

func TestWaitTimesOut(t *testing.T) {
	setup(t, "IN_PROGRESS")
	s, err := client(t).Wait("42", 50*time.Millisecond, 20*time.Millisecond, nil)
	if err != ErrWaitTimeout {
		t.Fatalf("err = %v", err)
	}
	if s == nil || s.State != "IN_PROGRESS" {
		t.Errorf("should return the last seen session, got %+v", s)
	}
}

func TestWaitGivesUpOnBadSession(t *testing.T) {
	setup(t, "IN_PROGRESS")
	start := time.Now()
	_, err := client(t).Wait("404", time.Minute, time.Second, nil)
	if err == nil || !strings.Contains(err.Error(), "404") {
		t.Fatalf("err = %v", err)
	}
	if time.Since(start) > 500*time.Millisecond {
		t.Error("a 404 must not be retried")
	}
}

func TestActivitiesPaginateAndLatestPatch(t *testing.T) {
	setup(t, "COMPLETED")
	acts, err := client(t).ListActivities("sessions/42")
	if err != nil {
		t.Fatal(err)
	}
	if len(acts) != 3 {
		t.Fatalf("got %d activities, want 3 across two pages", len(acts))
	}
	p := LatestPatch(acts)
	if p == nil || p.SuggestedCommitMessage != "test: add tests" {
		t.Errorf("latest patch = %+v", p)
	}
	out := formatActivities(acts, 0)
	for _, want := range []string{"1. Read code", "$ go test ./...  (exit 1)", "Should I also cover the CLI?"} {
		if !strings.Contains(out, want) {
			t.Errorf("activities missing %q:\n%s", want, out)
		}
	}
	if w := lastAgentWords(acts); w != "Should I also cover the CLI?" {
		t.Errorf("lastAgentWords = %q", w)
	}
}

func TestCLIWaitExitCodes(t *testing.T) {
	cases := []struct {
		states []string
		code   int
		want   string
	}{
		{[]string{"IN_PROGRESS", "COMPLETED"}, exitOK, "pull/7"},
		{[]string{"IN_PROGRESS", "FAILED"}, exitFail, "FAILED"},
		{[]string{"AWAITING_PLAN_APPROVAL"}, exitNeedsYou, "approve"},
		{[]string{"AWAITING_USER_FEEDBACK"}, exitNeedsYou, "Should I also cover the CLI?"},
	}
	for _, tc := range cases {
		setup(t, tc.states...)
		var out, errb bytes.Buffer
		code := runCLI("wait", []string{"42", "--every", "10ms"}, nil, &out, &errb)
		if code != tc.code {
			t.Errorf("%v: exit %d, want %d (stderr %s)", tc.states, code, tc.code, errb.String())
		}
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%v: output lacks %q:\n%s", tc.states, tc.want, out.String())
		}
	}
	setup(t, "IN_PROGRESS")
	var out, errb bytes.Buffer
	if code := runCLI("wait", []string{"--timeout", "30ms", "42", "--every", "10ms"}, nil, &out, &errb); code != exitTimeout {
		t.Errorf("timeout exit = %d", code)
	}
}

func TestCLIReplyApprovePatchSources(t *testing.T) {
	f := setup(t, "COMPLETED")
	var out, errb bytes.Buffer
	if code := runCLI("reply", []string{"42", "yes,", "cover", "it"}, nil, &out, &errb); code != 0 {
		t.Fatalf("reply exit %d: %s", code, errb.String())
	}
	if len(f.messages) != 1 || f.messages[0] != "yes, cover it" {
		t.Errorf("messages = %v", f.messages)
	}
	if code := runCLI("approve", []string{"42"}, nil, &out, &errb); code != 0 || !f.approved {
		t.Errorf("approve exit %d approved=%v", code, f.approved)
	}
	out.Reset()
	errb.Reset()
	if code := runCLI("patch", []string{"42"}, nil, &out, &errb); code != 0 {
		t.Fatalf("patch exit %d", code)
	}
	if out.String() != "--- a/y\n+++ b/y\n" {
		t.Errorf("patch stdout must be the bare diff, got %q", out.String())
	}
	if !strings.Contains(errb.String(), "test: add tests") {
		t.Errorf("commit message should go to stderr, got %q", errb.String())
	}
	out.Reset()
	if code := runCLI("sources", nil, nil, &out, &errb); code != 0 || !strings.Contains(out.String(), "Imlokzu/claude-bot") {
		t.Errorf("sources: %d %q", code, out.String())
	}
}

func TestCLIStartReadsPromptFromStdin(t *testing.T) {
	f := setup(t, "QUEUED")
	var out, errb bytes.Buffer
	code := runCLI("start", []string{"--repo", "o/r", "-", "--no-pr"}, strings.NewReader("long\nprompt"), &out, &errb)
	if code != 0 {
		t.Fatalf("exit %d: %s", code, errb.String())
	}
	if f.created["prompt"] != "long\nprompt" {
		t.Errorf("prompt = %q", f.created["prompt"])
	}
	if _, ok := f.created["automationMode"]; ok {
		t.Error("--no-pr must drop automationMode")
	}
	if !strings.Contains(out.String(), "jules wait 42") {
		t.Errorf("start should say how to wait:\n%s", out.String())
	}
}

func TestBadKeyIsReported(t *testing.T) {
	setup(t, "QUEUED")
	t.Setenv("JULES_API_KEY", "wrong")
	r := callTool("jules_list", map[string]any{})
	if !r.IsError || !strings.Contains(r.Content[0].Text, "401") {
		t.Errorf("want a 401 error, got %+v", r)
	}
}

func TestKeyFromDotEnv(t *testing.T) {
	dir := t.TempDir()
	sub := filepath.Join(dir, "a", "b")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	env := "OTHER=1\nexport JULES_API_KEY=\"from-dotenv\"\n"
	if err := os.WriteFile(filepath.Join(dir, ".env"), []byte(env), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Chdir(sub)
	t.Setenv("JULES_API_KEY", "")
	c, err := NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if c.APIKey != "from-dotenv" {
		t.Errorf("key = %q", c.APIKey)
	}
}

func TestToolsListIsValid(t *testing.T) {
	names := map[string]bool{}
	for _, tl := range tools() {
		if tl.Description == "" || tl.InputSchema == nil {
			t.Errorf("%s is missing a description or schema", tl.Name)
		}
		names[tl.Name] = true
	}
	for _, n := range []string{"jules_start", "jules_wait", "jules_status", "jules_list", "jules_reply", "jules_approve", "jules_patch", "jules_sources"} {
		if !names[n] {
			t.Errorf("tool %s missing", n)
		}
	}
	if r := callTool("jules_nope", map[string]any{}); !r.IsError {
		t.Error("unknown tool must be an error")
	}
}
