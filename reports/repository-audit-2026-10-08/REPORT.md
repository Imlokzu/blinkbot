# Repository audit — 2026-10-08

Five new findings in package handling and document extraction, with three
product ideas and concrete repair examples. This folder sits beside the earlier
agent audits in `reports/`. It adds reports and isolated observations; product
fixes below are proposals, not shipped changes.

Starting revision: `793beed2`. The checkout contains other agents' unfinished
work. Their files, personal profiles, real packages, conversations, credentials,
and running services were left alone. See [validation](VALIDATION.md) and the
reproducible [probes](probes.py).

## Scope and priority

I checked the latest Blink rename/publication notes, current API contracts,
previous audit findings, and the screen-store/document paths. This is a bounded
review, not a claim that every repository module has been audited. Previously
reported display broadcast/alarm issues and the destructive installed-app
replacement from the October 1 review are not counted again.

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| S-01 | High when imported HTML is accessible | Orphaned imported apps lose their sandbox policy | Reproduced type-change/removal and policy lookup; HTTP check in validation |
| S-02 | Medium | A failed source promotion hides the previous imported package | Reproduced with a single synthetic rename failure |
| S-03 | Medium | A corrupt ZIP member escapes the store's API error contract | Reproduced CRC error; HTTP check in validation |
| S-04 | Medium | Export can return a package the importer rejects as oversized | Reproduced with a small compressed synthetic archive |
| D-01 | Medium | UTF-16 DOCX XML bypasses the no-DTD/entity check | Reproduced with 32 harmless entity references |

Confidence is high in all five observed behaviors: each reproduced against the
current source with isolated synthetic inputs. The security impact in S-01 is
inferred from the serving and sandbox contracts; no victim session or public
deployment was exercised. S-02 requires an injected filesystem failure.

## S-01 — Missing package provenance is treated as trusted

**Source:** [`screen_store.py`](../../Virtual%20Bot/screen_store.py),
`121–133`, `234–241`, `567–579`, `586–624`;
[`main.py`](../../Virtual%20Bot/main.py), `309–332`, `1443–1458`.

The response sandbox is selected from the current source catalog. When
`_source_root()` finds no source, `is_shared()` returns false and
`shared_app_csp()` returns `None`. The file route separately serves any existing
installed app file. Losing the catalog source therefore changes the security
policy of bytes that have already been installed.

This is reachable without injecting a filesystem failure:

1. Import and install an inert shared app.
2. Import a skin with the same ID and `install_now=False`. Changing `type` is
   accepted. The existing app does not make `was_installed` true because that
   test now looks only for an installed skin.
3. Remove the shared package. Removal uses the current skin type, leaving the
   installed app directory behind while deleting its source.
4. The old HTML still exists; its policy changes from `sandbox allow-scripts`
   to no package CSP.

**Impact:** opening the orphaned document directly at `/store-apps/<id>/…`
can give imported scripts the bot server's origin. The special `Origin: null`
mutation guard depends on sandboxing and does not replace it. A screen iframe
may retain its own sandbox attributes; the direct file URL is also supported
and needs protection. The probe uses inert HTML and performs no script-based
data access. No real victim session or deployment exposure is claimed.

**How I would fix it:** default every store-app response to the restrictive
policy unless the installed artifact has verified built-in provenance. Store
that provenance outside package-controlled files, tied to the installed
generation/digest. A source catalog change must not promote an installed copy's
trust. Reject an update that changes an existing package's type, and remove
both app/skin runtime artifacts when explicitly deleting a shared ID.

An immediate conservative policy example is:

```python
def shared_app_csp(path: str) -> str:
    return SHARED_APP_CSP
```

This closes the missing-source bypass, but also restricts trusted built-in
apps that need network access. It is a temporary containment option requiring
built-in compatibility checks, not the complete selective-trust implementation.
For that implementation, grant exceptions only from a server-owned install
receipt; unknown, missing, or mismatched receipts keep the sandbox.

**Acceptance:** the type-change sequence is rejected or fully cleans up old
artifacts; missing-source/receipt HTML is sandboxed or returns 404; a built-in
exception cannot be obtained by renaming a source or supplying manifest fields.
Verify both direct navigation and screen iframe behavior.

## S-02 — Source replacement does not restore its backup on failure

**Source:** `screen_store.py:543–574`.

Import writes a staging directory, renames the old source to `.<id>-old`, and
then promotes staging to the original name. If the second rename fails, the
exception handler removes staging but never restores the old name. The API
reports `io_error`, the catalog loses the package, and old bytes remain hidden
in the backup. An installed copy can remain accessible and encounter S-01.

The fault probe fails only `staging.rename(final)`. It observes no final source,
a hidden old source, existing installed HTML, and no sandbox policy. This is
an injected filesystem-failure case, not an observed disk failure on this Mac.
It differs from the earlier installed-copy deletion issue: this defect is in
the shared **source** transaction before installation.

**How I would fix it:** preserve the backup until promotion succeeds, restore
it on promotion failure, and expose a recovery state if restoration also fails.
For example, around the promotion step:

```python
try:
    staging.rename(final)
except OSError:
    if previous is not None:
        previous.rename(final)
    raise
```

That example addresses the immediate failed rename only. Keep a durable
transaction marker or generation pointer so startup can recover a crash between
the two renames. Never delete an earlier recovery directory blindly; verify its
transaction first. Preserve both error causes if rollback itself fails.

**Acceptance:** injected promotion failure leaves the old package discoverable
and byte-identical; injected rollback failure retains recovery bytes and reports
their state; restarting at each transaction boundary recovers deterministically.
Repair S-01 independently so a transaction gap never changes script trust.

## S-03 — Invalid ZIP contents produce an unhandled exception

**Source:** `screen_store.py:470–475`, `500–518`;
`main.py:1389–1392`, `1425–1428`.

Only opening the outer ZIP is protected by the `BadZipFile` handler. Reading a
member can raise the same exception for a CRC mismatch. It escapes as raw
`BadZipFile`, while the import endpoint catches only `StoreError`. Structurally
recognizable but damaged uploads consequently take the 500 path instead of the
stable `bad_archive` 400 path.

The probe creates a valid stored ZIP and changes one byte inside `index.html`.
The outer ZIP opens normally; validation raises `BadZipFile`, not `StoreError`.
No files are installed, which is a useful property to preserve.

**How I would fix it:** normalize malformed archive exceptions around the
whole validation/read operation, with exception chaining for diagnostics:

```python
try:
    with archive.open(info) as handle:
        content = handle.read(MAX_UNPACKED_BYTES + 1)
except (zipfile.BadZipFile, EOFError, NotImplementedError) as exc:
    raise StoreError("invalid archive member", code="bad_archive") from exc
```

Also identify and normalize failures from the supported compression decoders.
Keep genuine filesystem errors distinct. Do not catch every exception and
quietly accept or partially install an archive.

**Acceptance:** bad CRC, truncated member, encrypted input, and unsupported
compression return the stable rejection contract and leave existing packages
unchanged. A real disk-write failure continues to report an operational error.

## S-04 — Export and import disagree about package size

**Source:** `screen_store.py:255–266`, `430–461`, `509–513`, `643–653`;
`main.py:1395–1406`.

`_pack_dir()` checks file count and final compressed size, but never enforces
`MAX_UNPACKED_BYTES`. The HTTP export calls `pack()` directly. A package with a
compressible file over 8 MiB exports successfully even though the importer
rejects it. Installation already checks the unpacked limit, but export does not
require installation. The CLI has an additional `inspect_archive(data)` check;
that does not protect the HTTP path.

The fixture exports an 8,388,609-byte repeated-character payload plus small
manifest/HTML files as an **8,564-byte** archive. Import rejects that exported
archive with `too_large`. No repository package was enlarged.

**How I would fix it:** apply a shared file-count/byte/manifest/entry validation
contract to both exporters before compression. Start with the same unpacked
budget:

```python
if sum(file.stat().st_size for file in files) > MAX_UNPACKED_BYTES:
    raise StoreError("package too large", code="too_large")
```

Read each source through a bounded open descriptor and count actual bytes as
well, because files can grow after `stat()`. Run `inspect_archive(data)` before
returning bytes from **every** export path, as the CLI already does. The output
must satisfy the receiver's contract, including manifest and entry validity.

**Acceptance:** export rejects over-budget sources before large allocations;
every successful export round-trips through the importer; source mutation
during packing cannot bypass actual-byte limits. Preserve deterministic ZIP
ordering/timestamps for unchanged packages.

## D-01 — DOCX declaration filtering depends on its byte encoding

**Source:** [`chat_attachments.py`](../../Virtual%20Bot/chat_attachments.py),
`127–150`; `main.py:3173–3178`, `3227–3231`.

The DOCX reader rejects ASCII byte sequences `<!DOCTYPE` and `<!ENTITY`, then
lets `ElementTree` detect the XML encoding. UTF-16 inserts null bytes between
these characters, bypassing both checks. The parser accepts that encoding and
expands internal entities despite the explicitly documented no-DTD policy.

The same harmless document is rejected in UTF-8 and accepted in UTF-16,
producing 672 characters from 32 entity references. Existing compressed-input,
2 MiB XML-entry, and returned-text limits remain, and the XML parser has its own
protections. This probe does **not** demonstrate unlimited expansion, external
file/network access, or a server outage. It establishes a policy bypass and
that the declared XML-byte budget is not an expanded-content budget.

**How I would fix it:** reject declarations through encoding-aware parser
events before constructing the document tree, rather than scanning raw bytes.
A standard-library preflight example is:

```python
from xml.parsers import expat

def reject_dtd(xml: bytes) -> None:
    parser = expat.ParserCreate()

    def forbidden(*_args):
        raise AttachmentError("unreadable_document")

    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.Parse(xml, True)
```

Call it after the existing XML-entry size check and before `ElementTree`.
Retain extraction bounds and worker-thread offloading. The preflight parses
twice; a single-pass parser with the same rejection contract is a possible
later refinement, not a reason to skip the repair.

**Acceptance:** UTF-8/UTF-16LE/UTF-16BE declarations are all rejected before
entity expansion; normal Word documents in supported encodings still extract;
malformed encodings map to `unreadable_document`. No external entities are
resolved and existing visible-text/truncation semantics stay intact.

## Additions I would build

1. **A package preview before installation.** Show type, version, compressed and
   installed sizes, changed assets, and origin permissions. Run the same
   validation as installation and issue an opaque server-held receipt tied to
   the exact archive digest. Confirmation consumes that receipt, so the app
   installs the bytes that were reviewed. Type-change rejection and sandbox
   enforcement remain mandatory regardless of what the preview displays.
2. **A package recovery view.** List interrupted transactions with their old/new
   generations and a recover/remove action. Keep recovery metadata outside app
   files, show stable localized codes, and reconcile it at startup. A failed
   update should preserve a launchable previous generation and explain the
   failure instead of silently disappearing from the drawer.
3. **A document extraction receipt.** Return structured format, truncation,
   extracted-character count, and supported-feature warnings alongside preview
   text. Bind it to server-read bytes. Reuse this result for chat preparation
   when the file is unchanged, so preview and answer context agree. The mobile
   and dashboard can explain partial extraction with locale keys rather than
   making the model guess whether it saw the whole document. This specializes
   the [October 7 source-read receipt idea](../repository-audit-2026-10-07/REPORT.md#additions-i-would-build-next)
   for uploaded documents; the general receipt concept was already proposed.

I would fix S-01 first, then S-02 and D-01, then the archive error/size contract.
After those repairs, build the preview and recovery UI on the existing FastAPI,
filesystem store, and frontend locale system. No extra agent runtime or package
platform is needed. Every proposed fix needs its listed regression scenarios;
the passing tests recorded here do not establish that these fixes are shipped.
