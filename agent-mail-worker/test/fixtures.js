// Raw messages shaped like the ones agents actually receive. CRLF line
// endings, as on the wire.
const crlf = (s) => s.replace(/\r?\n/g, "\r\n");

// Bitwarden's signup mail: quoted-printable with soft line breaks inside the
// link and `=3D` for every `=`, a year in the footer and no code at all.
export const bitwardenSignup = crlf(`From: Bitwarden <no-reply@bitwarden.com>
To: vault@ag.waveio.me
Subject: Verify Your Email
Date: Mon, 29 Sep 2026 15:16:10 +0000
Message-ID: <abc123@bitwarden.com>
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="b1"

--b1
Content-Type: text/plain; charset=us-ascii
Content-Transfer-Encoding: quoted-printable

Verify your email address below to finish creating your account.

Verify email: https://vault.bitwarden.com/redirect-connector.html#finish-si=
gnup?token=3DBwRegistrationEmailVerificationToken_CfDJ8Dv1Rm0WJ1dGoELh9ccg=
0G7jyfGmwF9&email=3Dvault%40ag.waveio.me&fromEmail=3Dtrue

If you did not request this email from Bitwarden, you can safely ignore it.

=C2=A9 2026 Bitwarden Inc.
--b1
Content-Type: text/html; charset=utf-8
Content-Transfer-Encoding: quoted-printable

<html><head><style>p{color:red}</style></head><body>
<p>Verify your email address below to finish creating your account.</p>
<a href=3D"https://vault.bitwarden.com/redirect-connector.html#finish-signup?=
token=3DBwRegistrationEmailVerificationToken_CfDJ8Dv1Rm0WJ1dGoELh9ccg0G7jyfGm=
wF9&amp;email=3Dvault%40ag.waveio.me&amp;fromEmail=3Dtrue">Verify email</a>
<p><a href=3D"https://bitwarden.com/help/">Help</a></p>
<p>&copy; 2026 Bitwarden Inc.</p>
</body></html>
--b1--
`);

export const bitwardenLink =
  "https://vault.bitwarden.com/redirect-connector.html#finish-signup?token=BwRegistrationEmailVerificationToken_CfDJ8Dv1Rm0WJ1dGoELh9ccg0G7jyfGmwF9&email=vault%40ag.waveio.me&fromEmail=true";

const b64 = (s) => Buffer.from(s, "utf8").toString("base64").replace(/(.{76})/g, "$1\r\n");

// HTML-only, base64, a Ukrainian subject as an encoded word, a split code.
export const ukrainianCode = crlf(`From: =?UTF-8?B?${Buffer.from("Сервіс Приклад").toString("base64")}?= <auth@example.ua>
To: lokzu@ag.waveio.me
Subject: =?UTF-8?B?${Buffer.from("Ваш код підтвердження").toString("base64")}?=
Date: Mon, 29 Sep 2026 10:00:00 +0300
MIME-Version: 1.0
Content-Type: text/html; charset=utf-8
Content-Transfer-Encoding: base64

${b64(`<div>Вітаємо!<br>Ваш код підтвердження: <b>482 913</b><br>Діє 10 хвилин.</div><div>Київ, вул. Хрещатик 2026</div>`)}
`);

// A normal letter with a PDF attached, the way a person sends a document.
export const pdfBytes = Buffer.from("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n", "latin1");
export const withAttachment = crlf(`From: Olena <olena@example.com>
To: lokzu@ag.waveio.me
Cc: boss@example.com
Reply-To: olena.work@example.com
Subject: Invoice for September
Date: Tue, 29 Sep 2026 09:00:00 +0200
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="m1"

--m1
Content-Type: text/plain; charset=utf-8
Content-Transfer-Encoding: 8bit

Hi! The invoice is attached. Total is 2026 EUR, due 15.10.
See https://example.com/invoices/42.

--m1
Content-Type: application/pdf; name="invoice-september.pdf"
Content-Disposition: attachment; filename="invoice-september.pdf"
Content-Transfer-Encoding: base64

${pdfBytes.toString("base64")}
--m1--
`);

// A code on its own line with no keyword next to it.
export const codeOnOwnLine = crlf(`From: noreply@example.com
To: lokzu@ag.waveio.me
Subject: Sign in to Example
Content-Type: text/plain; charset=utf-8

Use this to sign in:

  739104

It expires in 5 minutes.
`);
