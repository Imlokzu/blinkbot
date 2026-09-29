# agent-mail-mcp

Standalone Go MCP (Model Context Protocol) server for AI agents to securely send and receive emails via `send.waveio.me` and `mail.waveio.me`.

## Features
- **Zero credential leaks**: The agent only holds an `AGENT_TOKEN`. The master Brevo API keys remain securely inside the Cloudflare Worker.
- **Identity resolution**: The gateway automatically maps agent requests to `lokzu@ag.waveio.me` and sets `Reply-To`.
- **Full MCP 2024-11-05 standard**: Works over `stdio` with Claude, Cursor, Antigravity, OpenClaw, and Virtual Bot.
- **Tools provided**:
  - `send_email`: Send emails to external recipients.
  - `check_inbox`: List received emails, newest first: read/unread, sender, subject, preview, attachments, code/link.
  - `read_email`: Open one email in full: recipients, date, the whole text (or HTML), every link, the attachment list. Marks it read.
  - `download_attachment`: Save one attachment to `AGENT_MAIL_DOWNLOAD_DIR` (default `~/agent-mail/attachments/<id>/`).
  - `delete_email`: Delete an email with its original and attachments.
  - `get_verification_code`: Fetch the latest OTP code and/or verification link.
  - `my_email`: Inspect mailbox info.

  Every mailbox tool takes an optional `address`, so an agent can read any `*@ag.waveio.me` box it registered with.

## Building
```bash
go build -o bin/agent-mail-mcp main.go
```

## Running
```bash
AGENT_TOKEN="<your agent token>" AGENT_EMAIL="lokzu@ag.waveio.me" ./bin/agent-mail-mcp
```
