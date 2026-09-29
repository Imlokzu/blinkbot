/**
 * Cloudflare Email Routing Worker: agent mailboxes on *@ag.waveio.me and the
 * outbound gateway at send.waveio.me.
 *
 * Every message is kept whole, the way a mail client keeps it: the original
 * .eml, plus a parsed record with decoded text, HTML, addresses, links and an
 * attachment list. Attachments are not stored twice; they are cut out of the
 * original on request. Verification codes and links are derived on top.
 *
 * KV layout (per mailbox address):
 *   inbox:<to>          newest-first list of message summaries
 *   msg:<to>:<id>       the full parsed record
 *   raw:<to>:<id>       the original message bytes
 *   otp:<to>            the latest code and/or verification link
 */
import { parseMessage, buildRecord, summarize } from "./mail.js";

const RETENTION_SECONDS = 60 * 60 * 24 * 30;
const OTP_RETENTION_SECONDS = 60 * 60 * 24;
const INBOX_LIMIT = 200;
// Email Routing accepts up to 25 MiB, which is also the KV value limit.
const MAX_RAW_BYTES = 25 * 1024 * 1024 - 1024;

function bearer(request) {
  return (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
}

// Constant-time comparison. Hashing first makes both sides the same length,
// so neither the content nor the length of the secret leaks through timing.
async function secretEquals(provided, expected) {
  if (!provided || !expected) return false;
  const encoder = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided)),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

function newMessageId() {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
}

async function readInbox(env, to) {
  try {
    return (await env.AG_MAILBOX.get(`inbox:${to}`, "json")) || [];
  } catch {
    return [];
  }
}

async function writeInbox(env, to, list) {
  await env.AG_MAILBOX.put(`inbox:${to}`, JSON.stringify(list.slice(0, INBOX_LIMIT)), {
    expirationTtl: RETENTION_SECONDS,
  });
}

/**
 * Parses and stores one message. A message that fails to parse is still
 * stored, raw bytes and all, with the error in its record: losing mail is
 * worse than showing it badly.
 */
export async function ingest(env, { raw, envelopeFrom, envelopeTo }) {
  const to = envelopeTo.toLowerCase().trim();
  const id = newMessageId();
  const receivedAt = new Date().toISOString();
  const bytes = raw instanceof Uint8Array ? raw : new Uint8Array(raw);
  const meta = { id, envelopeFrom, envelopeTo: to, receivedAt, rawSize: bytes.byteLength };

  let record;
  try {
    record = buildRecord(await parseMessage(bytes), meta);
  } catch (err) {
    const fallback = new TextDecoder().decode(bytes.subarray(0, 8000));
    record = buildRecord({ subject: "(unparsed message)", text: fallback }, meta);
    record.parse_error = String(err && err.message ? err.message : err);
  }
  record.raw_stored = bytes.byteLength <= MAX_RAW_BYTES;

  if (record.raw_stored) {
    await env.AG_MAILBOX.put(`raw:${to}:${id}`, bytes, { expirationTtl: RETENTION_SECONDS });
  }
  await env.AG_MAILBOX.put(`msg:${to}:${id}`, JSON.stringify(record), { expirationTtl: RETENTION_SECONDS });

  const inbox = await readInbox(env, to);
  inbox.unshift(summarize(record));
  await writeInbox(env, to, inbox);

  // A link alone is enough to finish many signups, so it is indexed even
  // when the message carries no code.
  if (record.otp_code || record.verification_link) {
    await env.AG_MAILBOX.put(
      `otp:${to}`,
      JSON.stringify({
        code: record.otp_code,
        from: record.from,
        subject: record.subject,
        link: record.verification_link,
        received_at: receivedAt,
        msg_id: id,
      }),
      { expirationTtl: OTP_RETENTION_SECONDS }
    );
  }
  return record;
}

/** Builds a minimal RFC 822 message, for the legacy simulate-receive payload. */
function composeRaw({ from, to, subject, body, html }) {
  const headers = [
    `From: ${from}`,
    `To: ${to}`,
    `Subject: =?UTF-8?B?${btoa(unescape(encodeURIComponent(subject)))}?=`,
    `Date: ${new Date().toUTCString()}`,
    "MIME-Version: 1.0",
    `Content-Type: ${html ? "text/html" : "text/plain"}; charset=utf-8`,
    "Content-Transfer-Encoding: 8bit",
  ];
  return new TextEncoder().encode(`${headers.join("\r\n")}\r\n\r\n${html || body}`);
}

function base64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function contentDisposition(filename) {
  const ascii = filename.replace(/[^\x20-\x7e]/g, "_").replace(/["\\]/g, "_");
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(filename)}`;
}

export default {
  // 1. Email Handler — triggered when Cloudflare Email Routing catches a message
  async email(message, env, ctx) {
    const normalizedTo = message.to.toLowerCase().trim();

    if (env.AG_MAILBOX) {
      let raw;
      try {
        raw = new Uint8Array(await new Response(message.raw).arrayBuffer());
      } catch (err) {
        raw = new TextEncoder().encode(`Subject: (unreadable message)\r\n\r\nError reading raw stream: ${err.message}`);
      }
      await ingest(env, { raw, envelopeFrom: message.from, envelopeTo: normalizedTo });
    }

    // Agent emails (*@ag.waveio.me) are strictly isolated and NEVER forwarded to personal email.
    if (normalizedTo.endsWith("@ag.waveio.me")) {
      // Kept exclusively in AG_MAILBOX for agents and dashboard.
      return;
    }

    // Forward non-agent main domain emails (*@waveio.me) to owner's inbox if configured
    if (env.FORWARD_MAIN_DOMAIN_TO && env.FORWARD_MAIN_DOMAIN_TO !== normalizedTo) {
      try {
        await message.forward(env.FORWARD_MAIN_DOMAIN_TO);
      } catch (fwdErr) {
        console.error("Failed to forward main domain email to", env.FORWARD_MAIN_DOMAIN_TO, fwdErr);
      }
    }
  },

  // 2. Fetch Handler — REST API for Agent / Bot Dashboard to fetch emails and OTPs
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Standard CORS
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-API-Key",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    // Health check
    if (url.pathname === "/api/health") {
      return Response.json(
        { status: "ok", service: "agent-mail-worker", time: new Date().toISOString() },
        { headers: corsHeaders }
      );
    }

    // If this is an agent send endpoint, let its dedicated handler perform agent token validation
    const isSendEndpoint = url.pathname === "/api/send" || url.pathname === "/v1/send";

    // General auth check for inbox / OTP management endpoints.
    // Fails closed: a Worker deployed without its secrets answers 503 instead
    // of silently letting everyone read every mailbox. The key is accepted
    // only in headers; a key in the query string ends up in access logs.
    if (!isSendEndpoint) {
      if (!env.API_KEY || !env.DEFAULT_AGENT_TOKEN) {
        return Response.json(
          { error: "Mail gateway secrets are not configured" },
          { status: 503, headers: corsHeaders }
        );
      }
      const providedKey = bearer(request) || request.headers.get("X-API-Key") || "";
      const allowed =
        (await secretEquals(providedKey, env.API_KEY)) ||
        (await secretEquals(providedKey, env.DEFAULT_AGENT_TOKEN));
      if (!allowed) {
        return Response.json(
          { error: "Unauthorized: invalid API key" },
          { status: 401, headers: corsHeaders }
        );
      }
    }

    const to = (url.searchParams.get("to") || "").toLowerCase().trim();
    const messageId = url.searchParams.get("id") || "";
    const needsTo = ["/api/inbox", "/api/latest-otp", "/api/message", "/api/message/raw", "/api/attachment"];
    if (needsTo.includes(url.pathname) && !to) {
      return Response.json({ error: "Missing ?to= parameter" }, { status: 400, headers: corsHeaders });
    }
    const isMessageRoute = ["/api/message", "/api/message/raw", "/api/attachment"].includes(url.pathname);
    if (isMessageRoute && !messageId) {
      return Response.json({ error: "Missing ?id= parameter" }, { status: 400, headers: corsHeaders });
    }

    // GET /api/inbox?to=lokzu@ag.waveio.me[&unread=1][&limit=20]
    // Summaries only: sender, subject, snippet, attachment list, code and
    // link. The full message is one GET /api/message away.
    if (url.pathname === "/api/inbox" && request.method === "GET") {
      let messages = await readInbox(env, to);
      const total = messages.length;
      const unread = messages.filter((m) => !m.seen).length;
      if (url.searchParams.get("unread") === "1") messages = messages.filter((m) => !m.seen);
      const limit = parseInt(url.searchParams.get("limit") || "", 10);
      if (limit > 0) messages = messages.slice(0, limit);
      return Response.json({ to, count: messages.length, total, unread, messages }, { headers: corsHeaders });
    }

    // GET /api/message?to=...&id=...[&peek=1] — the whole message; marks it read unless peek=1
    if (url.pathname === "/api/message" && request.method === "GET") {
      const record = await env.AG_MAILBOX.get(`msg:${to}:${messageId}`, "json");
      if (!record) {
        return Response.json({ error: "Message not found or expired" }, { status: 404, headers: corsHeaders });
      }
      if (url.searchParams.get("peek") !== "1" && !record.seen) {
        const inbox = await readInbox(env, to);
        const entry = inbox.find((m) => m.id === messageId);
        if (entry) {
          entry.seen = true;
          await writeInbox(env, to, inbox);
        }
        record.seen = true;
      }
      return Response.json(record, { headers: corsHeaders });
    }

    // GET /api/message/raw?to=...&id=... — the original .eml
    if (url.pathname === "/api/message/raw" && request.method === "GET") {
      const raw = await env.AG_MAILBOX.get(`raw:${to}:${messageId}`, "arrayBuffer");
      if (!raw) {
        return Response.json({ error: "Original not stored or expired" }, { status: 404, headers: corsHeaders });
      }
      return new Response(raw, {
        headers: { ...corsHeaders, "Content-Type": "message/rfc822", "Content-Disposition": contentDisposition(`${messageId}.eml`) },
      });
    }

    // GET /api/attachment?to=...&id=...&index=0 — one attachment, cut out of the original
    if (url.pathname === "/api/attachment" && request.method === "GET") {
      const index = parseInt(url.searchParams.get("index") || "0", 10);
      const raw = await env.AG_MAILBOX.get(`raw:${to}:${messageId}`, "arrayBuffer");
      if (!raw) {
        return Response.json({ error: "Original not stored or expired" }, { status: 404, headers: corsHeaders });
      }
      const parsed = await parseMessage(new Uint8Array(raw));
      const att = (parsed.attachments || [])[index];
      if (!att) {
        return Response.json({ error: `No attachment at index ${index}` }, { status: 404, headers: corsHeaders });
      }
      return new Response(att.content, {
        headers: {
          ...corsHeaders,
          "Content-Type": att.mimeType || "application/octet-stream",
          "Content-Disposition": contentDisposition(att.filename || `attachment-${index + 1}`),
        },
      });
    }

    // DELETE /api/message?to=...&id=...
    if (url.pathname === "/api/message" && request.method === "DELETE") {
      const inbox = await readInbox(env, to);
      await writeInbox(env, to, inbox.filter((m) => m.id !== messageId));
      await env.AG_MAILBOX.delete(`msg:${to}:${messageId}`);
      await env.AG_MAILBOX.delete(`raw:${to}:${messageId}`);
      return Response.json({ success: true, deleted: messageId }, { headers: corsHeaders });
    }

    // GET /api/latest-otp?to=lokzu@ag.waveio.me
    if (url.pathname === "/api/latest-otp" && request.method === "GET") {
      const otpData = await env.AG_MAILBOX.get(`otp:${to}`, "json");
      return Response.json({ to, otp: otpData || null }, { headers: corsHeaders });
    }

    // POST /api/simulate-receive — test injection through the same pipeline
    // as real mail. Body: {to, raw} with raw the whole message (or raw_base64),
    // or the legacy {from, to, subject, body, html}.
    if (url.pathname === "/api/simulate-receive" && request.method === "POST") {
      try {
        const payload = await request.json();
        const target = (payload.to || "lokzu@ag.waveio.me").toLowerCase().trim();
        const from = payload.from || "service@example.com";
        let raw;
        if (payload.raw_base64) raw = base64ToBytes(payload.raw_base64);
        else if (payload.raw) raw = new TextEncoder().encode(payload.raw);
        else
          raw = composeRaw({
            from,
            to: target,
            subject: payload.subject || "Verification code",
            body: payload.body || "Your security code is 749201. Use it to complete registration.",
            html: payload.html,
          });
        const record = await ingest(env, { raw, envelopeFrom: from, envelopeTo: target });
        return Response.json(
          { success: true, message: "Simulated email received and saved", record: summarize(record) },
          { headers: corsHeaders }
        );
      } catch (err) {
        return Response.json({ error: err.message }, { status: 500, headers: corsHeaders });
      }
    }

    // DELETE /api/inbox?to=lokzu@ag.waveio.me
    if (url.pathname === "/api/inbox" && request.method === "DELETE") {
      const inbox = await readInbox(env, to);
      for (const m of inbox) {
        await env.AG_MAILBOX.delete(`msg:${to}:${m.id}`);
        await env.AG_MAILBOX.delete(`raw:${to}:${m.id}`);
      }
      await env.AG_MAILBOX.delete(`inbox:${to}`);
      await env.AG_MAILBOX.delete(`otp:${to}`);
      return Response.json({ success: true, message: "Inbox cleared" }, { headers: corsHeaders });
    }


    // POST /api/send or /v1/send — Secure outbound email gateway for AI agents
    if ((url.pathname === "/api/send" || url.pathname === "/v1/send") && request.method === "POST") {
      // 1. Validate agent token
      if (!env.DEFAULT_AGENT_TOKEN) {
        return Response.json(
          { error: "Mail gateway secrets are not configured" },
          { status: 503, headers: corsHeaders }
        );
      }
      const agentToken = bearer(request) || request.headers.get("X-Agent-Token") || "";
      
      if (!(await secretEquals(agentToken, env.DEFAULT_AGENT_TOKEN))) {
        return Response.json(
          { error: "Unauthorized: invalid agent token. The agent does not have permission to send emails." },
          { status: 401, headers: corsHeaders }
        );
      }

      // 2. Parse request payload from agent
      let payload = {};
      try {
        payload = await request.json();
      } catch (err) {
        return Response.json({ error: "Invalid JSON body" }, { status: 400, headers: corsHeaders });
      }

      const to = (payload.to || "").trim();
      const subject = (payload.subject || "Message from AI Agent").trim();
      const body = payload.body || payload.text || "";
      const htmlBody = payload.html || "";

      if (!to || !to.includes("@")) {
        return Response.json({ error: "Missing or invalid recipient email ('to')" }, { status: 400, headers: corsHeaders });
      }
      if (!body && !htmlBody) {
        return Response.json({ error: "Email body or text cannot be empty" }, { status: 400, headers: corsHeaders });
      }

      // 3. Resolve agent identity
      const agentName = payload.sender_name || "Lokzu (AI Agent)";
      const agentEmail = (payload.from_agent || "lokzu@ag.waveio.me").toLowerCase().trim();
      const brevoApiKey = env.BREVO_API_KEY;
      const verifiedSender = env.DEFAULT_SENDER_EMAIL || "noreply@waveio.me";

      if (!brevoApiKey) {
        return Response.json({ error: "BREVO_API_KEY secret is not configured in worker" }, { status: 500, headers: corsHeaders });
      }

      // 4. Construct rich HTML if not explicitly provided
      const finalHtml = htmlBody || `
        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 24px; background: #0f172a; color: #f8fafc; border-radius: 14px; max-width: 600px; margin: 0 auto; border: 1px solid #1e293b;">
          <div style="margin-bottom: 16px; border-bottom: 1px solid #334155; padding-bottom: 12px;">
            <span style="font-size: 12px; color: #38bdf8; font-weight: 600; text-transform: uppercase;">Лист від ШІ-агента • ${agentEmail}</span>
            <h2 style="margin: 8px 0 0 0; color: #ffffff; font-size: 20px;">${subject}</h2>
          </div>
          <div style="font-size: 15px; line-height: 1.6; color: #cbd5e1; white-space: pre-wrap;">${body}</div>
          <div style="margin-top: 24px; padding-top: 14px; border-top: 1px solid #1e293b; font-size: 12px; color: #64748b;">
            Відправлено агентом <b>${agentName}</b> через шлюз send.waveio.me. Відповісти: <a href="mailto:${agentEmail}" style="color: #38bdf8;">${agentEmail}</a>
          </div>
        </div>
      `.trim();

      // 5. Send via Brevo API
      const brevoPayload = {
        sender: { name: agentName, email: verifiedSender },
        replyTo: { name: agentName, email: agentEmail },
        to: [{ email: to }],
        subject: subject,
        htmlContent: finalHtml,
      };

      try {
        const brevoResp = await fetch("https://api.brevo.com/v3/smtp/email", {
          method: "POST",
          headers: {
            "api-key": brevoApiKey,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(brevoPayload),
        });

        const brevoData = await brevoResp.json();
        if (!brevoResp.ok) {
          return Response.json(
            { error: "Brevo delivery failed", status: brevoResp.status, details: brevoData },
            { status: 502, headers: corsHeaders }
          );
        }

        // Store in sent history in KV
        const sentRecord = {
          message_id: brevoData.messageId,
          agent_email: agentEmail,
          to,
          subject,
          snippet: body.substring(0, 200),
          sent_at: new Date().toISOString(),
        };

        await env.AG_MAILBOX.put(
          `sent:${agentEmail}:${Date.now()}`,
          JSON.stringify(sentRecord),
          { expirationTtl: 60 * 60 * 24 * 30 }
        );

        return Response.json(
          {
            success: true,
            message_id: brevoData.messageId,
            from: agentEmail,
            to,
            subject,
          },
          { headers: corsHeaders }
        );
      } catch (err) {
        return Response.json({ error: err.message }, { status: 500, headers: corsHeaders });
      }
    }

    return Response.json({ error: "Not found" }, { status: 404, headers: corsHeaders });

  },
};
