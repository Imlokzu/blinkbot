package main

// Reading mail the way a person does: a list of what arrived, then one
// message opened in full, with its links and attachments. The gateway keeps
// every message whole; these tools only ask for what the agent looks at.

import (
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Bodies longer than this are cut in read_email so one newsletter does not
// fill the model's context. max_chars raises it per call.
const defaultReadChars = 20000

type mailSummary struct {
	ID               string       `json:"id"`
	From             string       `json:"from"`
	Subject          string       `json:"subject"`
	Date             string       `json:"date"`
	ReceivedAt       string       `json:"received_at"`
	Snippet          string       `json:"snippet"`
	OtpCode          string       `json:"otp_code"`
	VerificationLink string       `json:"verification_link"`
	Seen             bool         `json:"seen"`
	Attachments      []attachment `json:"attachments"`
}

type attachment struct {
	Index    int    `json:"index"`
	Filename string `json:"filename"`
	MimeType string `json:"mime_type"`
	Size     int    `json:"size"`
	Inline   bool   `json:"inline"`
}

type mailAddress struct {
	Name    string `json:"name"`
	Address string `json:"address"`
}

type mailRecord struct {
	mailSummary
	Recipients struct {
		To      []mailAddress `json:"to"`
		Cc      []mailAddress `json:"cc"`
		ReplyTo []mailAddress `json:"reply_to"`
	} `json:"recipients"`
	Text  string `json:"text"`
	Body  string `json:"body"`
	HTML  string `json:"html"`
	Links []struct {
		URL  string `json:"url"`
		Text string `json:"text"`
	} `json:"links"`
	ParseError string `json:"parse_error"`
}

func stringProp(desc string) map[string]interface{} {
	return map[string]interface{}{"type": "string", "description": desc}
}

func addressProp() map[string]interface{} {
	return stringProp("Mailbox to use instead of the agent's own, e.g. vault@ag.waveio.me. Any *@ag.waveio.me address receives mail.")
}

func inboxTools() []ToolInfo {
	return []ToolInfo{
		{
			Name: "check_inbox",
			Description: "List emails in the agent's mailbox, newest first: id, read/unread, sender, subject, " +
				"date, a short preview, attachments, and any verification code or link. " +
				"Open one with read_email to see the whole message.",
			InputSchema: map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"limit":       map[string]interface{}{"type": "integer", "description": "How many emails to list (default 10)"},
					"unread_only": map[string]interface{}{"type": "boolean", "description": "Only emails not yet opened with read_email"},
					"address":     addressProp(),
				},
			},
		},
		{
			Name: "read_email",
			Description: "Open one email in full, like a mail client: sender, recipients, date, the complete text, " +
				"every link with its label, and the attachment list. Marks it as read.",
			InputSchema: map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"id":        stringProp("Email id from check_inbox"),
					"max_chars": map[string]interface{}{"type": "integer", "description": fmt.Sprintf("Cut the text after this many characters (default %d)", defaultReadChars)},
					"html":      map[string]interface{}{"type": "boolean", "description": "Return the original HTML instead of the text version"},
					"address":   addressProp(),
				},
				"required": []string{"id"},
			},
		},
		{
			Name:        "download_attachment",
			Description: "Save one attachment of an email to a local file and return its path.",
			InputSchema: map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"id":      stringProp("Email id from check_inbox"),
					"index":   map[string]interface{}{"type": "integer", "description": "Attachment index shown by read_email (default 0)"},
					"address": addressProp(),
				},
				"required": []string{"id"},
			},
		},
		{
			Name:        "delete_email",
			Description: "Delete one email, including its original and attachments, from the mailbox.",
			InputSchema: map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"id":      stringProp("Email id from check_inbox"),
					"address": addressProp(),
				},
				"required": []string{"id"},
			},
		},
	}
}

func mailbox(cfg Config, args map[string]interface{}) string {
	if a, _ := args["address"].(string); strings.TrimSpace(a) != "" {
		return strings.ToLower(strings.TrimSpace(a))
	}
	return cfg.AgentEmail
}

func intArg(args map[string]interface{}, key string, def int) int {
	// JSON numbers arrive as float64.
	if v, ok := args[key].(float64); ok {
		return int(v)
	}
	return def
}

// gatewayDo calls the mail API, trying the configured gateway first and
// mail.waveio.me second, the same fallback send_email uses.
func gatewayDo(cfg Config, method, path string, query url.Values) ([]byte, http.Header, error) {
	gateways := []string{cfg.GatewayURL}
	if cfg.GatewayURL != "https://mail.waveio.me" {
		gateways = append(gateways, "https://mail.waveio.me")
	}
	client := &http.Client{Timeout: 30 * time.Second}
	var lastErr error
	for _, gw := range gateways {
		req, err := http.NewRequest(method, gw+path+"?"+query.Encode(), nil)
		if err != nil {
			lastErr = err
			continue
		}
		req.Header.Set("Authorization", "Bearer "+cfg.AgentToken)
		resp, err := client.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		body, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode == http.StatusOK {
			return body, resp.Header, nil
		}
		lastErr = fmt.Errorf("status %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
		// A 4xx is the same answer from every gateway; do not ask twice.
		if resp.StatusCode < 500 {
			break
		}
	}
	return nil, nil, lastErr
}

func textResult(s string) CallToolResult {
	return CallToolResult{Content: []TextContent{{Type: "text", Text: s}}}
}

func errorResult(format string, a ...interface{}) CallToolResult {
	return CallToolResult{Content: []TextContent{{Type: "text", Text: fmt.Sprintf(format, a...)}}, IsError: true}
}

func humanSize(n int) string {
	switch {
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.0f KB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d B", n)
}

func formatAttachments(atts []attachment) string {
	var b strings.Builder
	for _, a := range atts {
		inline := ""
		if a.Inline {
			inline = ", inline"
		}
		fmt.Fprintf(&b, "  [%d] %s (%s, %s%s)\n", a.Index, a.Filename, a.MimeType, humanSize(a.Size), inline)
	}
	return b.String()
}

func handleCheckInbox(cfg Config, args map[string]interface{}) CallToolResult {
	box := mailbox(cfg, args)
	q := url.Values{"to": {box}, "limit": {fmt.Sprint(intArg(args, "limit", 10))}}
	if unread, _ := args["unread_only"].(bool); unread {
		q.Set("unread", "1")
	}
	body, _, err := gatewayDo(cfg, "GET", "/api/inbox", q)
	if err != nil {
		return errorResult("Failed to check inbox: %v", err)
	}
	var data struct {
		Total    int           `json:"total"`
		Unread   int           `json:"unread"`
		Messages []mailSummary `json:"messages"`
	}
	if err := json.Unmarshal(body, &data); err != nil {
		return errorResult("Unexpected inbox response: %v", err)
	}

	var b strings.Builder
	fmt.Fprintf(&b, "Mailbox %s: %d emails, %d unread. Showing %d.\n", box, data.Total, data.Unread, len(data.Messages))
	for _, m := range data.Messages {
		state := "read"
		if !m.Seen {
			state = "UNREAD"
		}
		when := m.Date
		if when == "" {
			when = m.ReceivedAt
		}
		fmt.Fprintf(&b, "\n[%s] %s · %s\nFrom: %s\nSubject: %s\n", m.ID, state, when, m.From, m.Subject)
		if m.Snippet != "" {
			fmt.Fprintf(&b, "Preview: %s\n", m.Snippet)
		}
		if m.OtpCode != "" {
			fmt.Fprintf(&b, "Code: %s\n", m.OtpCode)
		}
		if m.VerificationLink != "" {
			fmt.Fprintf(&b, "Verification link: %s\n", m.VerificationLink)
		}
		if len(m.Attachments) > 0 {
			fmt.Fprintf(&b, "Attachments:\n%s", formatAttachments(m.Attachments))
		}
	}
	return textResult(b.String())
}

func joinAddresses(list []mailAddress) string {
	parts := make([]string, 0, len(list))
	for _, a := range list {
		if a.Name != "" {
			parts = append(parts, fmt.Sprintf("%s <%s>", a.Name, a.Address))
		} else {
			parts = append(parts, a.Address)
		}
	}
	return strings.Join(parts, ", ")
}

func handleReadEmail(cfg Config, args map[string]interface{}) CallToolResult {
	id, _ := args["id"].(string)
	if id == "" {
		return errorResult("Error: 'id' is required (take it from check_inbox)")
	}
	body, _, err := gatewayDo(cfg, "GET", "/api/message", url.Values{"to": {mailbox(cfg, args)}, "id": {id}})
	if err != nil {
		return errorResult("Failed to read email: %v", err)
	}
	var m mailRecord
	if err := json.Unmarshal(body, &m); err != nil {
		return errorResult("Unexpected message response: %v", err)
	}

	content := strings.TrimSpace(m.Text)
	if content == "" {
		// Records stored before the gateway kept full messages only have `body`.
		content = strings.TrimSpace(m.Body)
	}
	if wantHTML, _ := args["html"].(bool); wantHTML && m.HTML != "" {
		content = m.HTML
	}
	limit := intArg(args, "max_chars", defaultReadChars)
	if r := []rune(content); limit > 0 && len(r) > limit {
		content = string(r[:limit]) + fmt.Sprintf("\n\n[... cut at %d of %d characters; call again with a larger max_chars]", limit, len(r))
	}

	var b strings.Builder
	fmt.Fprintf(&b, "From: %s\n", m.From)
	if to := joinAddresses(m.Recipients.To); to != "" {
		fmt.Fprintf(&b, "To: %s\n", to)
	}
	if cc := joinAddresses(m.Recipients.Cc); cc != "" {
		fmt.Fprintf(&b, "Cc: %s\n", cc)
	}
	if rt := joinAddresses(m.Recipients.ReplyTo); rt != "" {
		fmt.Fprintf(&b, "Reply-To: %s\n", rt)
	}
	fmt.Fprintf(&b, "Date: %s\nSubject: %s\n", firstNonEmpty(m.Date, m.ReceivedAt), m.Subject)
	if m.OtpCode != "" {
		fmt.Fprintf(&b, "Code: %s\n", m.OtpCode)
	}
	if m.VerificationLink != "" {
		fmt.Fprintf(&b, "Verification link: %s\n", m.VerificationLink)
	}
	if m.ParseError != "" {
		fmt.Fprintf(&b, "Note: the message could not be fully parsed (%s); showing raw text.\n", m.ParseError)
	}
	fmt.Fprintf(&b, "\n%s\n", content)
	if len(m.Links) > 0 {
		b.WriteString("\nLinks:\n")
		for i, l := range m.Links {
			label := l.Text
			if label == "" {
				label = "(no label)"
			}
			fmt.Fprintf(&b, "  %d. %s — %s\n", i+1, label, l.URL)
		}
	}
	if len(m.Attachments) > 0 {
		fmt.Fprintf(&b, "\nAttachments (save one with download_attachment):\n%s", formatAttachments(m.Attachments))
	}
	return textResult(b.String())
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// downloadDir is where attachments are saved: AGENT_MAIL_DOWNLOAD_DIR, or
// ~/agent-mail/attachments.
func downloadDir() string {
	if d := os.Getenv("AGENT_MAIL_DOWNLOAD_DIR"); d != "" {
		return d
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(os.TempDir(), "agent-mail")
	}
	return filepath.Join(home, "agent-mail", "attachments")
}

func handleDownloadAttachment(cfg Config, args map[string]interface{}) CallToolResult {
	id, _ := args["id"].(string)
	if id == "" {
		return errorResult("Error: 'id' is required (take it from check_inbox)")
	}
	index := intArg(args, "index", 0)
	q := url.Values{"to": {mailbox(cfg, args)}, "id": {id}, "index": {fmt.Sprint(index)}}
	data, headers, err := gatewayDo(cfg, "GET", "/api/attachment", q)
	if err != nil {
		return errorResult("Failed to download attachment: %v", err)
	}

	name := fmt.Sprintf("attachment-%d", index+1)
	if _, params, err := mime.ParseMediaType(headers.Get("Content-Disposition")); err == nil && params["filename"] != "" {
		name = params["filename"]
	}
	// The name comes from the sender; keep only its last path element so it
	// cannot climb out of the download directory.
	name = filepath.Base(filepath.Clean("/" + name))
	if name == "/" || name == "." {
		name = fmt.Sprintf("attachment-%d", index+1)
	}

	dir := filepath.Join(downloadDir(), filepath.Base(filepath.Clean("/"+id)))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return errorResult("Failed to create %s: %v", dir, err)
	}
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return errorResult("Failed to save %s: %v", path, err)
	}
	return textResult(fmt.Sprintf("Saved %s (%s, %s) to %s", name, headers.Get("Content-Type"), humanSize(len(data)), path))
}

func handleDeleteEmail(cfg Config, args map[string]interface{}) CallToolResult {
	id, _ := args["id"].(string)
	if id == "" {
		return errorResult("Error: 'id' is required (take it from check_inbox)")
	}
	if _, _, err := gatewayDo(cfg, "DELETE", "/api/message", url.Values{"to": {mailbox(cfg, args)}, "id": {id}}); err != nil {
		return errorResult("Failed to delete email: %v", err)
	}
	return textResult("Deleted email " + id)
}

func handleGetVerificationCode(cfg Config, args map[string]interface{}) CallToolResult {
	body, _, err := gatewayDo(cfg, "GET", "/api/latest-otp", url.Values{"to": {mailbox(cfg, args)}})
	if err != nil {
		return errorResult("Failed to get verification code: %v", err)
	}
	return textResult(string(body))
}
