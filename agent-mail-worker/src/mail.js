/**
 * Turns a raw RFC 822 message into what a person sees in a mail client:
 * decoded text and HTML, addresses, date, links and a list of attachments.
 * Verification codes and links are derived from that decoded content, so they
 * no longer depend on quoted-printable or base64 happening to look like text.
 */
import PostalMime from "postal-mime";

// A stored message record lives in one KV value (25 MiB max) together with
// its metadata, so each body part is capped well below that.
export const MAX_BODY_CHARS = 1_000_000;
const SNIPPET_CHARS = 300;

const NAMED_ENTITIES = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ",
  laquo: "«", raquo: "»", mdash: "—", ndash: "–", hellip: "…", copy: "©",
  reg: "®", trade: "™", rsquo: "’", lsquo: "‘", rdquo: "”", ldquo: "“",
};

export function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (m, code) => {
    if (code[0] === "#") {
      const n = code[1] === "x" || code[1] === "X" ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
      return Number.isFinite(n) && n > 0 && n < 0x110000 ? String.fromCodePoint(n) : m;
    }
    return NAMED_ENTITIES[code.toLowerCase()] ?? m;
  });
}

/** Readable text from HTML: block elements become line breaks, links keep their URL. */
export function htmlToText(html) {
  if (!html) return "";
  let s = html
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<(head|style|script|title)[^>]*>[\s\S]*?<\/\1>/gi, "")
    .replace(/<a\s[^>]*href\s*=\s*(["'])(.*?)\1[^>]*>([\s\S]*?)<\/a>/gi, (_, _q, href, inner) => {
      const label = inner.replace(/<[^>]+>/g, "").trim();
      const url = decodeEntities(href.trim());
      if (!label || label === url || /^mailto:/i.test(url)) return label || url;
      return `${label} [${url}]`;
    })
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|h[1-6]|li|tr|table|blockquote|section|article|header|footer)>/gi, "\n")
    .replace(/<li[^>]*>/gi, "• ")
    .replace(/<[^>]+>/g, "");
  s = decodeEntities(s);
  return s
    .split("\n")
    .map((line) => line.replace(/[ \t ]+/g, " ").trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/** Every distinct http(s) link, from HTML anchors first (they carry a label), then bare URLs in text. */
export function extractLinks(html, text) {
  const seen = new Map();
  const add = (url, label) => {
    url = decodeEntities(url.trim()).replace(/[.,;:!?)\]]+$/, "");
    if (!/^https?:\/\//i.test(url)) return;
    if (!seen.has(url)) seen.set(url, { url, text: label || "" });
    else if (label && !seen.get(url).text) seen.get(url).text = label;
  };
  for (const m of (html || "").matchAll(/<a\s[^>]*href\s*=\s*(["'])(.*?)\1[^>]*>([\s\S]*?)<\/a>/gi)) {
    add(m[2], decodeEntities(m[3].replace(/<[^>]+>/g, "")).replace(/\s+/g, " ").trim());
  }
  for (const m of (text || "").matchAll(/https?:\/\/[^\s"'<>\]]+/gi)) add(m[0], "");
  return [...seen.values()];
}

const CODE_KEYWORDS =
  "code|verification|verify|passcode|security code|one-time|pin|otp|token|" +
  "код|пароль|підтвердження|kod|código|bestätigungscode|sicherheitscode";

/**
 * A one-time code, or null. Only two shapes count: a code near a keyword
 * ("Your code is 123 456") or a code alone on its own line. A bare number
 * elsewhere is a year, a price or a street number far more often than a code,
 * which is how "2026" from a footer used to come back as the OTP.
 */
export function extractOtp(subject, text) {
  const content = `${subject || ""}\n${text || ""}`;
  const near = new RegExp(`(?:${CODE_KEYWORDS})[^\\n\\r0-9]{0,40}?\\b(\\d{3}[ -]?\\d{3}|\\d{4,8})\\b`, "i");
  const m = content.match(near);
  if (m && !isYear(m[1])) return m[1].replace(/[ -]/g, "");
  for (const line of content.split(/\r?\n/)) {
    const own = line.trim().replace(/^[*_`"'«]+|[*_`"'»]+$/g, "");
    if (/^(\d{3}[ -]\d{3}|\d{4,8})$/.test(own) && !isYear(own)) return own.replace(/[ -]/g, "");
  }
  return null;
}

function isYear(code) {
  return /^(19|20)\d\d$/.test(code);
}

const LINK_HINTS =
  /verif|confirm|activat|finish-signup|signup|sign-up|register|magic|login|log-in|sign-?in|reset|token|auth|approve|validate|підтверд|активув|увійти/i;
const LINK_SKIP = /unsubscribe|preferences|privacy|terms|policy|help|support|facebook|twitter|linkedin|instagram|youtube/i;

/** The link a person would click to finish a signup, sign in or reset. */
export function pickVerificationLink(links) {
  const candidates = links.filter((l) => !LINK_SKIP.test(l.url) && !LINK_SKIP.test(l.text));
  const byLabel = candidates.find((l) => LINK_HINTS.test(l.text));
  if (byLabel) return byLabel.url;
  const byUrl = candidates.find((l) => LINK_HINTS.test(l.url));
  return byUrl ? byUrl.url : null;
}

function formatAddress(a) {
  if (!a) return "";
  if (a.group) return a.group.map(formatAddress).join(", ");
  return a.name ? `${a.name} <${a.address}>` : a.address || "";
}

function addressList(list) {
  return (list || []).flatMap((a) => (a.group ? a.group : [a])).map((a) => ({ name: a.name || "", address: a.address || "" }));
}

function cap(s) {
  if (!s) return { value: "", truncated: false };
  return s.length > MAX_BODY_CHARS ? { value: s.slice(0, MAX_BODY_CHARS), truncated: true } : { value: s, truncated: false };
}

function byteLength(content) {
  if (!content) return 0;
  if (typeof content === "string") return new TextEncoder().encode(content).length;
  return content.byteLength ?? 0;
}

export async function parseMessage(raw) {
  return PostalMime.parse(raw, { attachmentEncoding: "arraybuffer" });
}

/**
 * The stored record for one message. `envelope` is what Email Routing saw
 * (the address actually delivered to, which is how catch-all mailboxes are
 * told apart); header fields describe what the sender wrote.
 */
export function buildRecord(email, { id, envelopeFrom, envelopeTo, receivedAt, rawSize }) {
  const html = cap(email.html || "");
  const text = cap(email.text || htmlToText(email.html || ""));
  const links = extractLinks(email.html, text.value);
  const subject = email.subject || "(no subject)";
  const readable = text.value || htmlToText(html.value);
  const from = email.from ? formatAddress(email.from) : envelopeFrom;

  return {
    id,
    to: envelopeTo,
    envelope_from: envelopeFrom,
    from,
    from_name: email.from?.name || "",
    from_address: email.from?.address || envelopeFrom,
    recipients: {
      to: addressList(email.to),
      cc: addressList(email.cc),
      reply_to: addressList(email.replyTo),
    },
    subject,
    date: email.date || null,
    received_at: receivedAt,
    message_id: email.messageId || null,
    in_reply_to: email.inReplyTo || null,
    references: email.references || null,
    snippet: readable.replace(/\s+/g, " ").trim().slice(0, SNIPPET_CHARS),
    // `body` is kept for clients written against the old record shape.
    body: text.value,
    text: text.value,
    html: html.value,
    truncated: text.truncated || html.truncated,
    links,
    attachments: (email.attachments || []).map((a, index) => ({
      index,
      filename: a.filename || `attachment-${index + 1}`,
      mime_type: a.mimeType || "application/octet-stream",
      size: byteLength(a.content),
      disposition: a.disposition || null,
      content_id: a.contentId || null,
      inline: Boolean(a.related) || a.disposition === "inline",
    })),
    otp_code: extractOtp(subject, readable),
    verification_link: pickVerificationLink(links),
    size: rawSize,
    seen: false,
  };
}

/** The part of a record that goes into the inbox list: enough to scan, not to read. */
export function summarize(record) {
  const { text, html, body, links, recipients, references, ...rest } = record;
  return {
    ...rest,
    has_html: Boolean(html),
    link_count: (links || []).length,
    attachments: (record.attachments || []).map(({ index, filename, mime_type, size, inline }) => ({
      index, filename, mime_type, size, inline,
    })),
  };
}
