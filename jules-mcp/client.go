package main

// Thin client for the Jules REST API (v1alpha).
//
// Jules is Google's asynchronous coding agent: a session gets a prompt and a
// GitHub repo, the agent plans, works in its own VM and, when asked, opens a
// pull request. Nothing here blocks on the agent except Wait, which polls —
// the API has no push channel, so polling is the only way to learn that the
// agent has come back.

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const defaultBaseURL = "https://jules.googleapis.com/v1alpha"

type Client struct {
	BaseURL string
	APIKey  string
	HTTP    *http.Client
}

// NewClientFromEnv reads JULES_API_KEY from the environment and, failing
// that, from the nearest .env file above the working directory or the
// binary. The .env fallback exists so the key never has to be copied into an
// MCP config: Claude Code starts the server inside the repo, and the repo
// root .env is gitignored.
func NewClientFromEnv() (*Client, error) {
	key := os.Getenv("JULES_API_KEY")
	if key == "" {
		key = lookupDotEnv("JULES_API_KEY")
	}
	if key == "" {
		return nil, errors.New("JULES_API_KEY is not set (env or a .env file above the working directory); " +
			"create one at https://jules.google.com/settings#api")
	}
	base := os.Getenv("JULES_API_URL")
	if base == "" {
		base = defaultBaseURL
	}
	return &Client{
		BaseURL: strings.TrimRight(base, "/"),
		APIKey:  key,
		HTTP:    &http.Client{Timeout: 30 * time.Second},
	}, nil
}

func lookupDotEnv(name string) string {
	var starts []string
	if wd, err := os.Getwd(); err == nil {
		starts = append(starts, wd)
	}
	if exe, err := os.Executable(); err == nil {
		starts = append(starts, filepath.Dir(exe))
	}
	for _, dir := range starts {
		for {
			if v := readDotEnv(filepath.Join(dir, ".env"), name); v != "" {
				return v
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	return ""
}

func readDotEnv(path, name string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		line = strings.TrimPrefix(line, "export ")
		k, v, ok := strings.Cut(line, "=")
		if !ok || strings.TrimSpace(k) != name {
			continue
		}
		return strings.Trim(strings.TrimSpace(v), `"'`)
	}
	return ""
}

// ---- API types (only the fields we use) ----

type Source struct {
	Name       string `json:"name"`
	ID         string `json:"id"`
	GithubRepo struct {
		Owner string `json:"owner"`
		Repo  string `json:"repo"`
	} `json:"githubRepo"`
}

type SourceContext struct {
	Source            string `json:"source"`
	GithubRepoContext struct {
		StartingBranch string `json:"startingBranch,omitempty"`
	} `json:"githubRepoContext"`
}

type PullRequest struct {
	URL         string `json:"url"`
	Title       string `json:"title"`
	Description string `json:"description"`
}

type Session struct {
	Name                string        `json:"name,omitempty"`
	ID                  string        `json:"id,omitempty"`
	Title               string        `json:"title,omitempty"`
	Prompt              string        `json:"prompt"`
	SourceContext       SourceContext `json:"sourceContext"`
	RequirePlanApproval bool          `json:"requirePlanApproval,omitempty"`
	AutomationMode      string        `json:"automationMode,omitempty"`
	CreateTime          string        `json:"createTime,omitempty"`
	UpdateTime          string        `json:"updateTime,omitempty"`
	State               string        `json:"state,omitempty"`
	URL                 string        `json:"url,omitempty"`
	Outputs             []struct {
		PullRequest *PullRequest `json:"pullRequest,omitempty"`
	} `json:"outputs,omitempty"`
}

type GitPatch struct {
	UnidiffPatch           string `json:"unidiffPatch"`
	BaseCommitID           string `json:"baseCommitId"`
	SuggestedCommitMessage string `json:"suggestedCommitMessage"`
}

type Artifact struct {
	ChangeSet *struct {
		Source   string    `json:"source"`
		GitPatch *GitPatch `json:"gitPatch"`
	} `json:"changeSet,omitempty"`
	BashOutput *struct {
		Command  string `json:"command"`
		Output   string `json:"output"`
		ExitCode int    `json:"exitCode"`
	} `json:"bashOutput,omitempty"`
}

type Activity struct {
	ID            string     `json:"id"`
	CreateTime    string     `json:"createTime"`
	Originator    string     `json:"originator"`
	Description   string     `json:"description"`
	Artifacts     []Artifact `json:"artifacts"`
	AgentMessaged *struct {
		AgentMessage string `json:"agentMessage"`
	} `json:"agentMessaged,omitempty"`
	UserMessaged *struct {
		UserMessage string `json:"userMessage"`
	} `json:"userMessaged,omitempty"`
	PlanGenerated *struct {
		Plan struct {
			ID    string `json:"id"`
			Steps []struct {
				Title string `json:"title"`
				Index int    `json:"index"`
			} `json:"steps"`
		} `json:"plan"`
	} `json:"planGenerated,omitempty"`
	PlanApproved *struct {
		PlanID string `json:"planId"`
	} `json:"planApproved,omitempty"`
	ProgressUpdated *struct {
		Title       string `json:"title"`
		Description string `json:"description"`
	} `json:"progressUpdated,omitempty"`
	SessionCompleted *struct{} `json:"sessionCompleted,omitempty"`
	SessionFailed    *struct {
		Reason string `json:"reason"`
	} `json:"sessionFailed,omitempty"`
}

// ---- transport ----

type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("jules api: HTTP %d: %s", e.Status, strings.TrimSpace(e.Body))
}

func (c *Client) do(method, path string, query url.Values, body, out any) error {
	u := c.BaseURL + "/" + strings.TrimLeft(path, "/")
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, u, rd)
	if err != nil {
		return err
	}
	req.Header.Set("X-Goog-Api-Key", c.APIKey)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		return &APIError{Status: resp.StatusCode, Body: string(data)}
	}
	if out == nil || len(bytes.TrimSpace(data)) == 0 {
		return nil
	}
	return json.Unmarshal(data, out)
}

// ---- endpoints ----

func (c *Client) ListSources() ([]Source, error) {
	var all []Source
	token := ""
	for page := 0; page < 20; page++ {
		q := url.Values{"pageSize": {"100"}}
		if token != "" {
			q.Set("pageToken", token)
		}
		var resp struct {
			Sources       []Source `json:"sources"`
			NextPageToken string   `json:"nextPageToken"`
		}
		if err := c.do("GET", "sources", q, nil, &resp); err != nil {
			return nil, err
		}
		all = append(all, resp.Sources...)
		if resp.NextPageToken == "" {
			break
		}
		token = resp.NextPageToken
	}
	return all, nil
}

type StartOptions struct {
	Prompt              string
	Source              string // any form NormalizeSource accepts
	Branch              string
	Title               string
	AutoPR              bool
	RequirePlanApproval bool
}

func (c *Client) CreateSession(o StartOptions) (*Session, error) {
	src, err := NormalizeSource(o.Source)
	if err != nil {
		return nil, err
	}
	branch := o.Branch
	if branch == "" {
		branch = "main"
	}
	s := Session{
		Prompt:              o.Prompt,
		Title:               o.Title,
		RequirePlanApproval: o.RequirePlanApproval,
	}
	s.SourceContext.Source = src
	s.SourceContext.GithubRepoContext.StartingBranch = branch
	if o.AutoPR {
		s.AutomationMode = "AUTO_CREATE_PR"
	}
	var out Session
	if err := c.do("POST", "sessions", nil, s, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetSession(id string) (*Session, error) {
	var out Session
	if err := c.do("GET", sessionPath(id), nil, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListSessions(n int) ([]Session, error) {
	if n <= 0 || n > 100 {
		n = 10
	}
	var resp struct {
		Sessions []Session `json:"sessions"`
	}
	err := c.do("GET", "sessions", url.Values{"pageSize": {fmt.Sprint(n)}}, nil, &resp)
	return resp.Sessions, err
}

// ListActivities returns every activity of a session in API order (oldest
// first). Sessions that run for hours can hold hundreds of activities, and
// "what did it say last" is the question we ask most, so all pages are read.
func (c *Client) ListActivities(id string) ([]Activity, error) {
	var all []Activity
	token := ""
	for page := 0; page < 50; page++ {
		q := url.Values{"pageSize": {"100"}}
		if token != "" {
			q.Set("pageToken", token)
		}
		var resp struct {
			Activities    []Activity `json:"activities"`
			NextPageToken string     `json:"nextPageToken"`
		}
		if err := c.do("GET", sessionPath(id)+"/activities", q, nil, &resp); err != nil {
			return nil, err
		}
		all = append(all, resp.Activities...)
		if resp.NextPageToken == "" {
			break
		}
		token = resp.NextPageToken
	}
	return all, nil
}

func (c *Client) SendMessage(id, prompt string) error {
	return c.do("POST", sessionPath(id)+":sendMessage", nil, map[string]string{"prompt": prompt}, nil)
}

func (c *Client) ApprovePlan(id string) error {
	return c.do("POST", sessionPath(id)+":approvePlan", nil, map[string]string{}, nil)
}

// ---- waiting ----

// Stop states are the moments the agent hands control back: it finished,
// failed, or needs a human (feedback, plan approval, paused).
var stopStates = map[string]bool{
	"COMPLETED":              true,
	"FAILED":                 true,
	"AWAITING_USER_FEEDBACK": true,
	"AWAITING_PLAN_APPROVAL": true,
	"PAUSED":                 true,
}

func IsStopState(state string) bool { return stopStates[state] }

var ErrWaitTimeout = errors.New("timed out waiting for the session")

// Wait polls the session until it reaches a stop state or the timeout runs
// out. Transient network errors are tolerated: a multi-hour wait should not
// die on one dropped request. onTick, if set, sees every state change.
func (c *Client) Wait(id string, timeout, every time.Duration, onTick func(*Session)) (*Session, error) {
	if every <= 0 {
		every = 30 * time.Second
	}
	deadline := time.Now().Add(timeout)
	lastState := ""
	var last *Session
	failures := 0
	for {
		s, err := c.GetSession(id)
		if err != nil {
			var apiErr *APIError
			if errors.As(err, &apiErr) && apiErr.Status/100 == 4 && apiErr.Status != 429 {
				return last, err // bad id or bad key: waiting will not fix it
			}
			failures++
			if failures >= 10 {
				return last, err
			}
		} else {
			failures = 0
			last = s
			if s.State != lastState && onTick != nil {
				onTick(s)
			}
			lastState = s.State
			if IsStopState(s.State) {
				return s, nil
			}
		}
		if time.Now().Add(every).After(deadline) {
			return last, ErrWaitTimeout
		}
		time.Sleep(every)
	}
}

// ---- helpers ----

func sessionPath(id string) string {
	id = strings.TrimPrefix(strings.TrimSpace(id), "sessions/")
	return "sessions/" + url.PathEscape(id)
}

// NormalizeSource accepts "owner/repo", "github/owner/repo",
// "sources/github/owner/repo" or a GitHub URL. Empty means "the repo we are
// standing in": JULES_SOURCE, else the origin remote of the working tree.
func NormalizeSource(s string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		s = os.Getenv("JULES_SOURCE")
	}
	if s == "" {
		out, err := exec.Command("git", "remote", "get-url", "origin").Output()
		if err != nil {
			return "", errors.New("no repo given and no git origin here; pass owner/repo")
		}
		s = strings.TrimSpace(string(out))
	}
	switch {
	case strings.HasPrefix(s, "sources/"):
		return s, nil
	case strings.HasPrefix(s, "github/"):
		return "sources/" + s, nil
	}
	// git@github.com:owner/repo.git, https://github.com/owner/repo(.git)
	if i := strings.Index(s, "github.com"); i >= 0 {
		s = strings.TrimLeft(s[i+len("github.com"):], ":/")
	}
	s = strings.TrimSuffix(strings.TrimSuffix(s, "/"), ".git")
	parts := strings.Split(s, "/")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", fmt.Errorf("cannot read %q as owner/repo", s)
	}
	return "sources/github/" + parts[0] + "/" + parts[1], nil
}

func (s *Session) PullRequests() []PullRequest {
	var prs []PullRequest
	for _, o := range s.Outputs {
		if o.PullRequest != nil {
			prs = append(prs, *o.PullRequest)
		}
	}
	return prs
}

// LatestPatch returns the newest non-empty patch the agent produced. It is
// what to apply locally when the session ran without AUTO_CREATE_PR.
func LatestPatch(acts []Activity) *GitPatch {
	for i := len(acts) - 1; i >= 0; i-- {
		for _, a := range acts[i].Artifacts {
			if a.ChangeSet != nil && a.ChangeSet.GitPatch != nil && a.ChangeSet.GitPatch.UnidiffPatch != "" {
				return a.ChangeSet.GitPatch
			}
		}
	}
	return nil
}
