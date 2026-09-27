package main

import (
	"fmt"
	"strings"
)

// Rendering is plain text on purpose: the same output goes to a terminal and
// into an agent's context, and both read short labelled lines best.

func formatSession(s *Session) string {
	var b strings.Builder
	title := s.Title
	if title == "" {
		title = firstLine(s.Prompt, 80)
	}
	fmt.Fprintf(&b, "session %s — %s\n", s.ID, title)
	state := s.State
	if state == "" {
		state = "STARTING" // the create response carries no state yet
	}
	fmt.Fprintf(&b, "state:   %s%s\n", state, stateHint(state))
	if s.SourceContext.Source != "" {
		fmt.Fprintf(&b, "repo:    %s @ %s\n", strings.TrimPrefix(s.SourceContext.Source, "sources/github/"),
			s.SourceContext.GithubRepoContext.StartingBranch)
	}
	if s.URL != "" {
		fmt.Fprintf(&b, "web:     %s\n", s.URL)
	}
	if s.UpdateTime != "" {
		fmt.Fprintf(&b, "updated: %s\n", s.UpdateTime)
	}
	for _, pr := range s.PullRequests() {
		fmt.Fprintf(&b, "PR:      %s  (%s)\n", pr.URL, pr.Title)
	}
	return b.String()
}

func stateHint(state string) string {
	switch state {
	case "AWAITING_USER_FEEDBACK":
		return "  ← the agent asked something; answer with reply"
	case "AWAITING_PLAN_APPROVAL":
		return "  ← read the plan, then approve or reply"
	case "PAUSED":
		return "  ← paused; reply to resume"
	}
	return ""
}

func formatSessionLine(s *Session) string {
	title := s.Title
	if title == "" {
		title = firstLine(s.Prompt, 60)
	}
	pr := ""
	if prs := s.PullRequests(); len(prs) > 0 {
		pr = "  " + prs[0].URL
	}
	return fmt.Sprintf("%-22s %-24s %s%s", s.ID, s.State, title, pr)
}

// formatActivities renders the last n activities. Bash output is reduced to
// the command and a failing exit code — the full logs are noise here and are
// one click away in the web app.
func formatActivities(acts []Activity, n int) string {
	if n > 0 && len(acts) > n {
		acts = acts[len(acts)-n:]
	}
	var b strings.Builder
	for _, a := range acts {
		ts := a.CreateTime
		if len(ts) >= 19 {
			ts = ts[11:19] // HH:MM:SS, the date is on the session
		}
		who := a.Originator
		switch {
		case a.AgentMessaged != nil:
			fmt.Fprintf(&b, "[%s] %s says:\n%s\n", ts, who, indent(a.AgentMessaged.AgentMessage))
		case a.UserMessaged != nil:
			fmt.Fprintf(&b, "[%s] %s says:\n%s\n", ts, who, indent(a.UserMessaged.UserMessage))
		case a.PlanGenerated != nil:
			fmt.Fprintf(&b, "[%s] plan %s:\n", ts, a.PlanGenerated.Plan.ID)
			for i, st := range a.PlanGenerated.Plan.Steps {
				fmt.Fprintf(&b, "    %d. %s\n", i+1, st.Title)
			}
		case a.PlanApproved != nil:
			fmt.Fprintf(&b, "[%s] plan approved\n", ts)
		case a.ProgressUpdated != nil:
			line := a.ProgressUpdated.Title
			if line == "Ran bash command" {
				line = bashSummary(a)
			}
			fmt.Fprintf(&b, "[%s] %s\n", ts, firstLine(line, 200))
		case a.SessionCompleted != nil:
			fmt.Fprintf(&b, "[%s] session completed\n", ts)
		case a.SessionFailed != nil:
			fmt.Fprintf(&b, "[%s] session FAILED: %s\n", ts, a.SessionFailed.Reason)
		default:
			if a.Description != "" {
				fmt.Fprintf(&b, "[%s] %s\n", ts, firstLine(a.Description, 200))
			}
		}
	}
	return b.String()
}

func bashSummary(a Activity) string {
	for _, art := range a.Artifacts {
		if art.BashOutput != nil {
			cmd := firstLine(strings.TrimSpace(art.BashOutput.Command), 120)
			if art.BashOutput.ExitCode != 0 {
				return fmt.Sprintf("$ %s  (exit %d)", cmd, art.BashOutput.ExitCode)
			}
			return "$ " + cmd
		}
	}
	return "ran a bash command"
}

// lastAgentWords is what the agent said most recently — the "answer" when it
// comes back asking for feedback.
func lastAgentWords(acts []Activity) string {
	for i := len(acts) - 1; i >= 0; i-- {
		if m := acts[i].AgentMessaged; m != nil {
			return m.AgentMessage
		}
	}
	return ""
}

func firstLine(s string, max int) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	if r := []rune(s); len(r) > max {
		s = string(r[:max-1]) + "…"
	}
	return s
}

func indent(s string) string {
	return "    " + strings.ReplaceAll(strings.TrimSpace(s), "\n", "\n    ")
}
