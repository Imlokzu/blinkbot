package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeGateway answers the mail API the way the worker does.
func fakeGateway(t *testing.T) Config {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/inbox", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("to") != "vault@ag.waveio.me" || r.URL.Query().Get("unread") != "1" {
			t.Errorf("unexpected inbox query %q", r.URL.RawQuery)
		}
		w.Write([]byte(`{"total":3,"unread":1,"messages":[{"id":"m1","from":"Olena <o@example.com>","subject":"Invoice",
			"date":"2026-09-29T07:00:00.000Z","snippet":"Hi! The invoice is attached.","seen":false,"otp_code":null,
			"attachments":[{"index":0,"filename":"invoice.pdf","mime_type":"application/pdf","size":48213}]}]}`))
	})
	mux.HandleFunc("/api/message", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "DELETE" {
			w.Write([]byte(`{"success":true}`))
			return
		}
		if r.URL.Query().Get("id") != "m1" {
			w.WriteHeader(404)
			w.Write([]byte(`{"error":"Message not found or expired"}`))
			return
		}
		w.Write([]byte(`{"id":"m1","from":"Olena <o@example.com>","subject":"Invoice","date":"2026-09-29T07:00:00.000Z",
			"recipients":{"to":[{"name":"","address":"vault@ag.waveio.me"}],"cc":[{"name":"Boss","address":"b@example.com"}],"reply_to":[]},
			"text":"Hi! The invoice is attached. ` + strings.Repeat("x", 100) + `","html":"<p>Hi!</p>",
			"links":[{"url":"https://example.com/i/42","text":"Open invoice"}],
			"attachments":[{"index":0,"filename":"invoice.pdf","mime_type":"application/pdf","size":48213}]}`))
	})
	mux.HandleFunc("/api/attachment", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/pdf")
		// A hostile sender-chosen name must not escape the download directory.
		w.Header().Set("Content-Disposition", `attachment; filename="../../evil.pdf"`)
		w.Write([]byte("%PDF-1.4 test"))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return Config{GatewayURL: srv.URL, AgentToken: "t", AgentEmail: "lokzu@ag.waveio.me"}
}

func text(r CallToolResult) string { return r.Content[0].Text }

func TestCheckInboxFormatsSummaries(t *testing.T) {
	cfg := fakeGateway(t)
	r := handleCheckInbox(cfg, map[string]interface{}{"unread_only": true, "address": "Vault@ag.waveio.me"})
	out := text(r)
	for _, want := range []string{"Mailbox vault@ag.waveio.me: 3 emails, 1 unread", "[m1] UNREAD", "Subject: Invoice", "[0] invoice.pdf (application/pdf, 47 KB)"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
}

func TestReadEmailShowsWholeMessage(t *testing.T) {
	cfg := fakeGateway(t)
	out := text(handleReadEmail(cfg, map[string]interface{}{"id": "m1"}))
	for _, want := range []string{"From: Olena <o@example.com>", "Cc: Boss <b@example.com>", "The invoice is attached", "1. Open invoice — https://example.com/i/42", "download_attachment"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	cut := text(handleReadEmail(cfg, map[string]interface{}{"id": "m1", "max_chars": float64(10)}))
	if !strings.Contains(cut, "cut at 10 of") {
		t.Errorf("max_chars not applied:\n%s", cut)
	}
	if h := text(handleReadEmail(cfg, map[string]interface{}{"id": "m1", "html": true})); !strings.Contains(h, "<p>Hi!</p>") {
		t.Errorf("html not returned:\n%s", h)
	}
	if r := handleReadEmail(cfg, map[string]interface{}{"id": "nope"}); !r.IsError || !strings.Contains(text(r), "404") {
		t.Errorf("missing message should be an error, got %q", text(r))
	}
}

func TestDownloadAttachmentStaysInDownloadDir(t *testing.T) {
	cfg := fakeGateway(t)
	dir := t.TempDir()
	t.Setenv("AGENT_MAIL_DOWNLOAD_DIR", dir)
	r := handleDownloadAttachment(cfg, map[string]interface{}{"id": "../m1"})
	if r.IsError {
		t.Fatal(text(r))
	}
	want := filepath.Join(dir, "m1", "evil.pdf")
	got, err := os.ReadFile(want)
	if err != nil {
		t.Fatalf("expected file at %s: %v (%s)", want, err, text(r))
	}
	if string(got) != "%PDF-1.4 test" {
		t.Errorf("content %q", got)
	}
}

func TestDeleteEmail(t *testing.T) {
	cfg := fakeGateway(t)
	if r := handleDeleteEmail(cfg, map[string]interface{}{"id": "m1"}); r.IsError {
		t.Fatal(text(r))
	}
	if r := handleDeleteEmail(cfg, map[string]interface{}{}); !r.IsError {
		t.Fatal("id must be required")
	}
}

func TestToolListHasMailClientTools(t *testing.T) {
	names := map[string]bool{}
	for _, tool := range getTools() {
		if names[tool.Name] {
			t.Errorf("duplicate tool %s", tool.Name)
		}
		names[tool.Name] = true
	}
	for _, want := range []string{"send_email", "check_inbox", "read_email", "download_attachment", "delete_email", "get_verification_code", "my_email"} {
		if !names[want] {
			t.Errorf("missing tool %s", want)
		}
	}
}
