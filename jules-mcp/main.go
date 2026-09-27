package main

// jude — hand long coding tasks to Google's Jules agent and hear back when
// it is done. Two faces over one client:
//
//	jude mcp           MCP server on stdio (Claude Code, Cursor, the bot)
//	jude <command>     CLI for a terminal or a shell-driving agent
//
// The CLI's `wait` exits with a code that says why Jules came back, so an
// agent can run it in the background and branch on the result.

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"
)

const usage = `jude — delegate coding tasks to Google Jules

usage:
  jude start [--repo owner/repo] [--branch main] [--title T] [--no-pr] [--plan] "prompt"
              (prompt "-" reads it from stdin)
  jude wait <session> [--timeout 2h] [--every 30s]
  jude status <session> [-n 15]
  jude list [-n 10]
  jude reply <session> "message"
  jude approve <session>
  jude patch <session>        unified diff on stdout, commit message on stderr
  jude sources
  jude mcp                    run as an MCP server on stdio

wait exit codes: 0 completed · 1 failed or error · 2 timed out · 3 needs you
(asked a question, awaiting plan approval, or paused)

env: JULES_API_KEY (or in a .env above the cwd), JULES_SOURCE, JULES_API_URL
`

const (
	exitOK       = 0
	exitFail     = 1
	exitTimeout  = 2
	exitNeedsYou = 3
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(exitFail)
	}
	cmd, args := os.Args[1], os.Args[2:]
	if cmd == "mcp" {
		serveMCP()
		return
	}
	if cmd == "help" || cmd == "-h" || cmd == "--help" {
		fmt.Print(usage)
		return
	}
	os.Exit(runCLI(cmd, args, os.Stdin, os.Stdout, os.Stderr))
}

func runCLI(cmd string, args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	fail := func(err error) int {
		fmt.Fprintln(stderr, "jude:", err)
		return exitFail
	}
	fs := flag.NewFlagSet(cmd, flag.ContinueOnError)
	fs.SetOutput(stderr)

	switch cmd {
	case "start":
		repo := fs.String("repo", "", "owner/repo (default: origin remote)")
		branch := fs.String("branch", "main", "starting branch")
		title := fs.String("title", "", "session title")
		noPR := fs.Bool("no-pr", false, "do not open a pull request")
		plan := fs.Bool("plan", false, "require plan approval before work starts")
		pos, err := parseInterspersed(fs, args)
		if err != nil {
			return exitFail
		}
		prompt := strings.Join(pos, " ")
		if prompt == "-" {
			b, err := io.ReadAll(stdin)
			if err != nil {
				return fail(err)
			}
			prompt = string(b)
		}
		if strings.TrimSpace(prompt) == "" {
			return fail(errors.New("start needs a prompt"))
		}
		c, err := NewClientFromEnv()
		if err != nil {
			return fail(err)
		}
		s, err := c.CreateSession(StartOptions{
			Prompt: prompt, Source: *repo, Branch: *branch, Title: *title,
			AutoPR: !*noPR, RequirePlanApproval: *plan,
		})
		if err != nil {
			return fail(err)
		}
		fmt.Fprint(stdout, formatSession(s))
		fmt.Fprintf(stdout, "\nnext: jude wait %s\n", s.ID)
		return exitOK

	case "wait":
		timeout := fs.Duration("timeout", 2*time.Hour, "give up after")
		every := fs.Duration("every", pollInterval(), "poll interval")
		pos, err := parseInterspersed(fs, args)
		if err != nil || len(pos) != 1 {
			return fail(errors.New("usage: jude wait <session> [--timeout 2h]"))
		}
		c, err := NewClientFromEnv()
		if err != nil {
			return fail(err)
		}
		s, werr := c.Wait(pos[0], *timeout, *every, func(s *Session) {
			fmt.Fprintf(stderr, "%s  %s\n", time.Now().Format("15:04:05"), s.State)
		})
		if s == nil {
			return fail(werr)
		}
		fmt.Fprint(stdout, report(c, s, werr))
		switch {
		case werr == ErrWaitTimeout:
			return exitTimeout
		case werr != nil, s.State == "FAILED":
			return exitFail
		case s.State == "COMPLETED":
			return exitOK
		default:
			return exitNeedsYou
		}

	case "status":
		n := fs.Int("n", 15, "recent activities to show (0 = all)")
		pos, err := parseInterspersed(fs, args)
		if err != nil || len(pos) != 1 {
			return fail(errors.New("usage: jude status <session> [-n 15]"))
		}
		return printTool(stdout, stderr, "jude_status", map[string]any{"session": pos[0], "activities": float64(*n)})

	case "list":
		n := fs.Int("n", 10, "how many")
		if _, err := parseInterspersed(fs, args); err != nil {
			return exitFail
		}
		return printTool(stdout, stderr, "jude_list", map[string]any{"limit": float64(*n)})

	case "reply":
		pos, err := parseInterspersed(fs, args)
		if err != nil || len(pos) < 2 {
			return fail(errors.New(`usage: jude reply <session> "message"`))
		}
		return printTool(stdout, stderr, "jude_reply", map[string]any{"session": pos[0], "message": strings.Join(pos[1:], " ")})

	case "approve":
		pos, err := parseInterspersed(fs, args)
		if err != nil || len(pos) != 1 {
			return fail(errors.New("usage: jude approve <session>"))
		}
		return printTool(stdout, stderr, "jude_approve", map[string]any{"session": pos[0]})

	case "patch":
		pos, err := parseInterspersed(fs, args)
		if err != nil || len(pos) != 1 {
			return fail(errors.New("usage: jude patch <session>"))
		}
		c, err := NewClientFromEnv()
		if err != nil {
			return fail(err)
		}
		acts, err := c.ListActivities(pos[0])
		if err != nil {
			return fail(err)
		}
		p := LatestPatch(acts)
		if p == nil {
			return fail(errors.New("no patch in this session yet"))
		}
		// Diff alone on stdout so `jude patch ID | git apply` works.
		if p.SuggestedCommitMessage != "" {
			fmt.Fprintln(stderr, p.SuggestedCommitMessage)
		}
		fmt.Fprint(stdout, p.UnidiffPatch)
		return exitOK

	case "sources":
		return printTool(stdout, stderr, "jude_sources", map[string]any{})
	}

	fmt.Fprintf(stderr, "jude: unknown command %q\n\n%s", cmd, usage)
	return exitFail
}

// printTool reuses the MCP handler so the CLI and the server never disagree
// about what a command prints.
func printTool(stdout, stderr io.Writer, name string, a map[string]any) int {
	r := callTool(name, a)
	text := ""
	if len(r.Content) > 0 {
		text = r.Content[0].Text
	}
	if r.IsError {
		fmt.Fprintln(stderr, "jude:", text)
		return exitFail
	}
	fmt.Fprint(stdout, strings.TrimRight(text, "\n")+"\n")
	return exitOK
}

// parseInterspersed lets flags sit after positional args
// (`jude wait 123 --timeout 1h`), which the flag package does not.
func parseInterspersed(fs *flag.FlagSet, args []string) ([]string, error) {
	var pos []string
	for {
		if err := fs.Parse(args); err != nil {
			return nil, err
		}
		rest := fs.Args()
		if len(rest) == 0 {
			return pos, nil
		}
		pos = append(pos, rest[0])
		args = rest[1:]
	}
}
