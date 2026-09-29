import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { ingest } from "../src/index.js";
import { extractOtp, htmlToText, pickVerificationLink, extractLinks } from "../src/mail.js";
import * as fx from "./fixtures.js";
import { timingSafeEqual } from "node:crypto";

// Workers add timingSafeEqual to crypto.subtle; Node keeps it in node:crypto.
if (!crypto.subtle.timingSafeEqual) {
  crypto.subtle.timingSafeEqual = (a, b) => timingSafeEqual(Buffer.from(a), Buffer.from(b));
}

// In-memory stand-in for the KV binding, with the same get() types.
function fakeKv() {
  const data = new Map();
  return {
    data,
    async get(key, type) {
      if (!data.has(key)) return null;
      const v = data.get(key);
      if (type === "json") return JSON.parse(v);
      if (type === "arrayBuffer") return v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength);
      return v;
    },
    async put(key, value) {
      data.set(key, typeof value === "string" ? value : new Uint8Array(value));
    },
    async delete(key) {
      data.delete(key);
    },
  };
}

const TOKEN = "test-agent-token";
const env = () => ({ AG_MAILBOX: fakeKv(), API_KEY: "admin", DEFAULT_AGENT_TOKEN: TOKEN });
const enc = (s) => new TextEncoder().encode(s);

async function call(e, path, method = "GET", body) {
  const req = new Request(`https://mail.waveio.me${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  return worker.fetch(req, e, {});
}

test("Bitwarden signup: decoded link, no year taken for a code", async () => {
  const e = env();
  const r = await ingest(e, { raw: enc(fx.bitwardenSignup), envelopeFrom: "bounces@em.bitwarden.com", envelopeTo: "vault@ag.waveio.me" });
  assert.equal(r.otp_code, null);
  assert.equal(r.verification_link, fx.bitwardenLink);
  assert.equal(r.from, "Bitwarden <no-reply@bitwarden.com>");
  assert.equal(r.subject, "Verify Your Email");
  assert.ok(!r.text.includes("=3D"), "quoted-printable must be decoded");
  assert.ok(r.html.includes("<a href="));
  assert.ok(r.links.some((l) => l.url === "https://bitwarden.com/help/"));
  const otp = await e.AG_MAILBOX.get("otp:vault@ag.waveio.me", "json");
  assert.equal(otp.link, fx.bitwardenLink, "a link-only message is indexed too");
  assert.equal(otp.code, null);
});

test("Ukrainian HTML-only base64 message: subject, sender, split code", async () => {
  const r = await ingest(env(), { raw: enc(fx.ukrainianCode), envelopeFrom: "auth@example.ua", envelopeTo: "lokzu@ag.waveio.me" });
  assert.equal(r.subject, "Ваш код підтвердження");
  assert.equal(r.from_name, "Сервіс Приклад");
  assert.equal(r.otp_code, "482913");
  assert.ok(r.text.includes("Діє 10 хвилин."), "text is derived from HTML when there is no text part");
  assert.ok(r.text.includes("\n"), "block elements become line breaks");
});

test("letter with an attachment: recipients, attachment list, download", async () => {
  const e = env();
  const r = await ingest(e, { raw: enc(fx.withAttachment), envelopeFrom: "olena@example.com", envelopeTo: "Lokzu@ag.waveio.me" });
  assert.equal(r.to, "lokzu@ag.waveio.me", "mailbox address is normalized");
  assert.equal(r.otp_code, null, "2026 EUR and 15.10 are not codes");
  assert.deepEqual(r.recipients.cc, [{ name: "", address: "boss@example.com" }]);
  assert.deepEqual(r.recipients.reply_to, [{ name: "", address: "olena.work@example.com" }]);
  assert.equal(r.attachments.length, 1);
  assert.equal(r.attachments[0].filename, "invoice-september.pdf");
  assert.equal(r.attachments[0].mime_type, "application/pdf");
  assert.equal(r.attachments[0].size, fx.pdfBytes.length);

  const res = await call(e, `/api/attachment?to=lokzu@ag.waveio.me&id=${r.id}&index=0`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("Content-Type"), "application/pdf");
  assert.match(res.headers.get("Content-Disposition"), /invoice-september\.pdf/);
  assert.deepEqual(Buffer.from(await res.arrayBuffer()), fx.pdfBytes);

  const missing = await call(e, `/api/attachment?to=lokzu@ag.waveio.me&id=${r.id}&index=5`);
  assert.equal(missing.status, 404);

  const raw = await call(e, `/api/message/raw?to=lokzu@ag.waveio.me&id=${r.id}`);
  assert.equal(raw.headers.get("Content-Type"), "message/rfc822");
  assert.equal(await raw.text(), fx.withAttachment);
});

test("inbox lists summaries; reading marks read; peek does not", async () => {
  const e = env();
  const a = await ingest(e, { raw: enc(fx.withAttachment), envelopeFrom: "x@example.com", envelopeTo: "lokzu@ag.waveio.me" });
  const b = await ingest(e, { raw: enc(fx.codeOnOwnLine), envelopeFrom: "y@example.com", envelopeTo: "lokzu@ag.waveio.me" });
  assert.equal(b.otp_code, "739104");

  let inbox = await (await call(e, "/api/inbox?to=lokzu@ag.waveio.me")).json();
  assert.equal(inbox.total, 2);
  assert.equal(inbox.unread, 2);
  assert.equal(inbox.messages[0].id, b.id, "newest first");
  assert.equal(inbox.messages[0].html, undefined, "no bodies in the list");
  assert.equal(inbox.messages[0].text, undefined);
  assert.equal(inbox.messages[1].attachments[0].filename, "invoice-september.pdf");

  const peek = await (await call(e, `/api/message?to=lokzu@ag.waveio.me&id=${a.id}&peek=1`)).json();
  assert.equal(peek.seen, false);
  const full = await (await call(e, `/api/message?to=lokzu@ag.waveio.me&id=${a.id}`)).json();
  assert.equal(full.seen, true);
  assert.ok(full.text.includes("The invoice is attached"));

  inbox = await (await call(e, "/api/inbox?to=lokzu@ag.waveio.me&unread=1")).json();
  assert.equal(inbox.unread, 1);
  assert.deepEqual(inbox.messages.map((m) => m.id), [b.id]);

  const del = await call(e, `/api/message?to=lokzu@ag.waveio.me&id=${a.id}`, "DELETE");
  assert.equal(del.status, 200);
  inbox = await (await call(e, "/api/inbox?to=lokzu@ag.waveio.me")).json();
  assert.equal(inbox.total, 1);
  assert.equal(e.AG_MAILBOX.data.has(`raw:lokzu@ag.waveio.me:${a.id}`), false);
  assert.equal((await call(e, `/api/message?to=lokzu@ag.waveio.me&id=${a.id}`)).status, 404);
});

test("a message that cannot be parsed is still stored", async () => {
  const e = env();
  const bad = enc("Content-Type: multipart/mixed; boundary=x\r\n\r\n" + "--x\r\nContent-Type: multipart/mixed; boundary=x\r\n\r\n".repeat(300));
  const r = await ingest(e, { raw: bad, envelopeFrom: "z@example.com", envelopeTo: "lokzu@ag.waveio.me" });
  assert.ok(r.parse_error, "nesting limit trips the parser");
  assert.equal(r.raw_stored, true);
  const inbox = await (await call(e, "/api/inbox?to=lokzu@ag.waveio.me")).json();
  assert.equal(inbox.total, 1);
});

test("simulate-receive accepts a raw message and the legacy fields", async () => {
  const e = env();
  let res = await call(e, "/api/simulate-receive", "POST", { to: "lokzu@ag.waveio.me", raw: fx.bitwardenSignup });
  assert.equal((await res.json()).record.verification_link, fx.bitwardenLink);
  res = await call(e, "/api/simulate-receive", "POST", { to: "lokzu@ag.waveio.me", subject: "Код", body: "Ваш код: 5512" });
  const legacy = (await res.json()).record;
  assert.equal(legacy.subject, "Код");
  assert.equal(legacy.otp_code, "5512");
});

test("auth: requests without the token are refused", async () => {
  const req = new Request("https://mail.waveio.me/api/inbox?to=lokzu@ag.waveio.me");
  assert.equal((await worker.fetch(req, env(), {})).status, 401);
});

test("extractOtp: shapes that are and are not codes", () => {
  assert.equal(extractOtp("Your code", "Your verification code is 123456."), "123456");
  assert.equal(extractOtp("", "Код: 4821"), "4821");
  assert.equal(extractOtp("", "Security code: 123-456"), "123456");
  assert.equal(extractOtp("", "© 2026 Example Inc. All rights reserved."), null);
  assert.equal(extractOtp("", "Your code for 2026 is ready"), null);
  assert.equal(extractOtp("", "Order 12345678 shipped to street 42"), null);
  assert.equal(extractOtp("", "**918273**"), "918273");
});

test("htmlToText and links keep what a reader needs", () => {
  const html = `<p>Hello&nbsp;there</p><a href="https://x.com/confirm?a=1&amp;b=2">Confirm account</a><a href="https://x.com/unsubscribe">Unsubscribe</a>`;
  assert.equal(htmlToText(html), "Hello there\nConfirm account [https://x.com/confirm?a=1&b=2]Unsubscribe [https://x.com/unsubscribe]");
  const links = extractLinks(html, "");
  assert.equal(links[0].url, "https://x.com/confirm?a=1&b=2");
  assert.equal(pickVerificationLink(links), "https://x.com/confirm?a=1&b=2");
  assert.equal(pickVerificationLink([{ url: "https://x.com/unsubscribe?token=1", text: "" }]), null);
});
