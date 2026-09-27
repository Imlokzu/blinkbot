package main

// MCP server over stdio (JSON-RPC 2.0, protocol 2024-11-05), the same shape
// as agent-mail-mcp and command-ledger-mcp.

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

const mcpVersion = "2024-11-05"

type rpcRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      any             `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcResponse struct {
	JSONRPC string    `json:"jsonrpc"`
	ID      any       `json:"id,omitempty"`
	Result  any       `json:"result,omitempty"`
	Error   *rpcError `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type toolInfo struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	InputSchema any    `json:"inputSchema"`
}

type textContent struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

type toolResult struct {
	Content []textContent `json:"content"`
	IsError bool          `json:"isError,omitempty"`
}

func obj(props map[string]any, required ...string) map[string]any {
	s := map[string]any{"type": "object", "properties": props}
	if len(required) > 0 {
		s["required"] = required
	}
	return s
}

func str(desc string) map[string]any { return map[string]any{"type": "string", "description": desc} }
func boolean(desc string) map[string]any {
	return map[string]any{"type": "boolean", "description": desc}
}
func integer(desc string) map[string]any {
	return map[string]any{"type": "integer", "description": desc}
}

var sessionArg = str("Session id (digits) or sessions/<id>")

func tools() []toolInfo {
	return []toolInfo{
		{
			Name: "jules_start",
			Description: "Hand a coding task to Jules, Google's asynchronous cloud coding agent. It clones the " +
				"GitHub repo into its own VM, plans, edits, runs commands and (by default) opens a pull request. " +
				"Use it for long, self-contained work you do not need to watch: translating comments, adding " +
				"tests, mechanical refactors, dependency bumps. Returns at once with a session id; follow up with " +
				"jules_wait or jules_status. The prompt is all the context Jules gets — name files, the goal, " +
				"how to verify, and what not to touch.",
			InputSchema: obj(map[string]any{
				"prompt":                str("Full task description. Jules sees only this and the repo."),
				"repo":                  str("owner/repo. Defaults to JULES_SOURCE or the origin remote of the working tree."),
				"branch":                str("Starting branch (default main)."),
				"title":                 str("Short session title (optional)."),
				"auto_pr":               boolean("Open a PR when the patch is ready (default true)."),
				"require_plan_approval": boolean("Stop after planning until jules_approve is called (default false)."),
			}, "prompt"),
		},
		{
			Name: "jules_wait",
			Description: "Block until a Jules session hands control back — COMPLETED, FAILED, " +
				"AWAITING_USER_FEEDBACK, AWAITING_PLAN_APPROVAL or PAUSED — or until timeout. Returns the state, " +
				"the PR link if any, and the latest activity including the agent's last message.",
			InputSchema: obj(map[string]any{
				"session":         sessionArg,
				"timeout_seconds": integer("How long to wait (default 480, max 3600). Keep it under your tool-call timeout."),
			}, "session"),
		},
		{
			Name:        "jules_status",
			Description: "Current state of a Jules session, its PR if any, and its most recent activities.",
			InputSchema: obj(map[string]any{
				"session":    sessionArg,
				"activities": integer("How many recent activities to include (default 15, 0 for all)."),
			}, "session"),
		},
		{
			Name:        "jules_list",
			Description: "Recent Jules sessions with state, title and PR link.",
			InputSchema: obj(map[string]any{"limit": integer("How many (default 10).")}),
		},
		{
			Name: "jules_reply",
			Description: "Send a message into a Jules session: answer its question, correct course, or give " +
				"follow-up work. The reply arrives as a later activity — use jules_wait afterwards.",
			InputSchema: obj(map[string]any{"session": sessionArg, "message": str("What to tell the agent.")},
				"session", "message"),
		},
		{
			Name:        "jules_approve",
			Description: "Approve the pending plan of a session started with require_plan_approval.",
			InputSchema: obj(map[string]any{"session": sessionArg}, "session"),
		},
		{
			Name: "jules_patch",
			Description: "The latest patch Jules produced in a session, as a unified diff with its suggested " +
				"commit message. Use it to review the change, or to apply it locally when no PR was opened.",
			InputSchema: obj(map[string]any{"session": sessionArg}, "session"),
		},
		{
			Name:        "jules_sources",
			Description: "GitHub repos connected to Jules. A repo must be connected (Jules GitHub app) before a session can use it.",
			InputSchema: obj(map[string]any{}),
		},
	}
}

func textOK(s string) toolResult { return toolResult{Content: []textContent{{Type: "text", Text: s}}} }
func textErr(s string) toolResult {
	return toolResult{Content: []textContent{{Type: "text", Text: s}}, IsError: true}
}

func argStr(a map[string]any, k string) string {
	v, _ := a[k].(string)
	return strings.TrimSpace(v)
}

func argInt(a map[string]any, k string, def int) int {
	if v, ok := a[k].(float64); ok {
		return int(v)
	}
	return def
}

func argBool(a map[string]any, k string, def bool) bool {
	if v, ok := a[k].(bool); ok {
		return v
	}
	return def
}

func callTool(name string, a map[string]any) toolResult {
	c, err := NewClientFromEnv()
	if err != nil {
		return textErr(err.Error())
	}
	sid := argStr(a, "session")
	needSession := func() (toolResult, bool) {
		if sid == "" {
			return textErr("'session' is required"), false
		}
		return toolResult{}, true
	}

	switch name {
	case "jules_start":
		prompt := argStr(a, "prompt")
		if prompt == "" {
			return textErr("'prompt' is required")
		}
		s, err := c.CreateSession(StartOptions{
			Prompt:              prompt,
			Source:              argStr(a, "repo"),
			Branch:              argStr(a, "branch"),
			Title:               argStr(a, "title"),
			AutoPR:              argBool(a, "auto_pr", true),
			RequirePlanApproval: argBool(a, "require_plan_approval", false),
		})
		if err != nil {
			return textErr(err.Error())
		}
		return textOK(formatSession(s) + "\nStarted. Check back with jules_wait session=" + s.ID)

	case "jules_wait":
		if r, ok := needSession(); !ok {
			return r
		}
		secs := argInt(a, "timeout_seconds", 480)
		if secs < 10 {
			secs = 10
		}
		if secs > 3600 {
			secs = 3600
		}
		s, err := c.Wait(sid, time.Duration(secs)*time.Second, pollInterval(), nil)
		if err != nil && s == nil {
			return textErr(err.Error())
		}
		return textOK(report(c, s, err))

	case "jules_status":
		if r, ok := needSession(); !ok {
			return r
		}
		s, err := c.GetSession(sid)
		if err != nil {
			return textErr(err.Error())
		}
		acts, err := c.ListActivities(sid)
		if err != nil {
			return textOK(formatSession(s) + "\n(activities unavailable: " + err.Error() + ")")
		}
		return textOK(formatSession(s) + "\n" + formatActivities(acts, argInt(a, "activities", 15)))

	case "jules_list":
		ss, err := c.ListSessions(argInt(a, "limit", 10))
		if err != nil {
			return textErr(err.Error())
		}
		if len(ss) == 0 {
			return textOK("no sessions")
		}
		var b strings.Builder
		for i := range ss {
			b.WriteString(formatSessionLine(&ss[i]) + "\n")
		}
		return textOK(b.String())

	case "jules_reply":
		if r, ok := needSession(); !ok {
			return r
		}
		msg := argStr(a, "message")
		if msg == "" {
			return textErr("'message' is required")
		}
		if err := c.SendMessage(sid, msg); err != nil {
			return textErr(err.Error())
		}
		return textOK("sent. The answer comes as a new activity — jules_wait session=" + sid)

	case "jules_approve":
		if r, ok := needSession(); !ok {
			return r
		}
		if err := c.ApprovePlan(sid); err != nil {
			return textErr(err.Error())
		}
		return textOK("plan approved")

	case "jules_patch":
		if r, ok := needSession(); !ok {
			return r
		}
		acts, err := c.ListActivities(sid)
		if err != nil {
			return textErr(err.Error())
		}
		p := LatestPatch(acts)
		if p == nil {
			return textOK("no patch in this session yet")
		}
		return textOK(formatPatch(p))

	case "jules_sources":
		srcs, err := c.ListSources()
		if err != nil {
			return textErr(err.Error())
		}
		if len(srcs) == 0 {
			return textOK("no repos connected — install the Jules GitHub app at https://jules.google.com")
		}
		var b strings.Builder
		for _, s := range srcs {
			b.WriteString(strings.TrimPrefix(s.Name, "sources/github/") + "\n")
		}
		return textOK(b.String())
	}
	return textErr("unknown tool: " + name)
}

// report is what an agent gets when Jules comes back: where it stopped and
// what it last said, so the next step is obvious without another call.
func report(c *Client, s *Session, waitErr error) string {
	var b strings.Builder
	if waitErr == ErrWaitTimeout {
		b.WriteString("Still working (wait timed out) — call jules_wait again.\n\n")
	} else if waitErr != nil {
		b.WriteString("Polling stopped: " + waitErr.Error() + "\n\n")
	}
	b.WriteString(formatSession(s))
	if acts, err := c.ListActivities(s.ID); err == nil {
		if IsStopState(s.State) {
			if w := lastAgentWords(acts); w != "" {
				b.WriteString("\nLast message from Jules:\n" + indent(w) + "\n")
			}
		}
		b.WriteString("\nRecent activity:\n" + formatActivities(acts, 8))
	}
	return b.String()
}

func formatPatch(p *GitPatch) string {
	var b strings.Builder
	if p.SuggestedCommitMessage != "" {
		b.WriteString("suggested commit message:\n" + indent(p.SuggestedCommitMessage) + "\n\n")
	}
	if p.BaseCommitID != "" {
		b.WriteString("base commit: " + p.BaseCommitID + "\n\n")
	}
	b.WriteString(p.UnidiffPatch)
	return b.String()
}

func pollInterval() time.Duration {
	if v := os.Getenv("JULES_POLL_SECONDS"); v != "" {
		var n int
		if _, err := fmt.Sscan(v, &n); err == nil && n > 0 {
			return time.Duration(n) * time.Second
		}
	}
	return 30 * time.Second
}

func serveMCP() {
	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 1024*1024), 16*1024*1024)
	enc := json.NewEncoder(os.Stdout)
	send := func(id any, result any, e *rpcError) {
		_ = enc.Encode(rpcResponse{JSONRPC: "2.0", ID: id, Result: result, Error: e})
	}
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var req rpcRequest
		if err := json.Unmarshal([]byte(line), &req); err != nil {
			send(nil, nil, &rpcError{Code: -32700, Message: "Parse error"})
			continue
		}
		switch req.Method {
		case "initialize":
			send(req.ID, map[string]any{
				"protocolVersion": mcpVersion,
				"capabilities":    map[string]any{"tools": map[string]any{}},
				"serverInfo":      map[string]any{"name": "jules-mcp", "version": "1.0.0"},
			}, nil)
		case "ping":
			send(req.ID, map[string]any{}, nil)
		case "tools/list":
			send(req.ID, map[string]any{"tools": tools()}, nil)
		case "tools/call":
			var p struct {
				Name      string         `json:"name"`
				Arguments map[string]any `json:"arguments"`
			}
			if err := json.Unmarshal(req.Params, &p); err != nil {
				send(req.ID, nil, &rpcError{Code: -32602, Message: "Invalid params"})
				continue
			}
			if p.Arguments == nil {
				p.Arguments = map[string]any{}
			}
			send(req.ID, callTool(p.Name, p.Arguments), nil)
		default:
			// Notifications carry no id and need no answer.
			if req.ID != nil {
				send(req.ID, nil, &rpcError{Code: -32601, Message: "Method not found: " + req.Method})
			}
		}
	}
}
