# Repository audit — 2026-10-07

My main recommendation is to make a release one verifiable artifact contract:
the bytes, filename, version code, channel, signing identity, and metadata should
agree from CI through the phone updater. Recent work adds the necessary pieces,
but the scripts still make independent assumptions about those values.

This folder follows the existing dated agent-report folders under `reports/`.
The starting revision was `4d48b37b`. Other agents' staged and unstaged changes
were present; the findings describe the inspected working source. Product code,
release configuration, credentials, deployed services, and artifacts were not
changed by this audit. Fix examples below are proposals.

## Changes checked against the earlier reports

Paired-device stream revocation and Android download-size alignment now have
follow-up commits (`caadea11` and `4d48b37b`) and reports. They are not reopened
as new bugs here. Older queue, privacy, and persistence findings remain separate.

This review focuses on the new release workflow/pull scripts and parallel
search/page fetch. The isolated Python calculator was inspected; no new concrete
calculator defect is claimed. Calculation limits and provider availability
should not be inferred from a registration entry alone.

## Ranked findings

| ID | Priority | Finding | Basis |
| --- | --- | --- | --- |
| R-01 | High within release-workflow privileges | Release text is inserted into executable shell source | Harmless rendered-assignment probe plus official GitHub guidance |
| R-02 | High for CI-produced updates | CI does not establish the installed app's signing identity | Source tracing and Android signing contract; no certificate comparison |
| R-03 | Medium | Release upload name and pull pattern disagree | Source/CLI contract and fixture pull failure |
| R-04 | Medium | Puller invents an APK version code from the local checkout | Unchanged pull script rebadges identical fixture bytes |
| R-05 | Medium with large fetched pages | Page byte cap is applied after the whole response is buffered | Mock-stream transfer probe |
| R-06 | Medium with slow DNS | DNS validation blocks the event loop before fetching | Slow-DNS timing fixture |

The included [probes.py](probes.py) exercises four bounded scenarios. It uses
mock HTTP transport, synthetic DNS, harmless Bash input, a temporary repository,
and fake `gh`/publisher commands. No real release, APK, key, API token, or network
connection is used. Scope and suite evidence are in [VALIDATION.md](VALIDATION.md).

### R-01 — Release notes and version inputs can become shell syntax

**Evidence:** `.github/workflows/mobile-release.yml:54,62` interpolates version
inputs into `run` source. Release publication at `:103-121` does the same for
version output, `inputs.changelog`, and the tag event's commit message. This
workflow requests `contents: write` at `:27-28`.

**Observed:** rendering the workflow's exact `NOTES` assignment with a harmless
synthetic command substitution created a marker file inside the temporary
directory. The notes became empty. Passing the same input through an environment
variable preserved the literal text and did not execute it.

**Limit:** this reproduces Bash behavior after substitution, not a run on GitHub.
The workflow is triggered by tags or manual dispatch, not arbitrary pull
requests; exploitation requires influence over data consumed by those triggers.
No token, workflow run, or release was accessed. The injected operation was
Bash's built-in `printf`, writing only inside the temporary directory. Ordinary
quotes or shell-like examples in changelogs can also break publication.

GitHub documents that expressions are substituted into inline script source
before shell execution and recommends intermediate environment variables.
[GitHub script-injection guidance](https://docs.github.com/en/actions/concepts/security/script-injections)
supports the boundary identified here.

**How I would fix it:** pass context/input values through `env`, validate version
name/channel/code as data, and write notes to a file using a constant shell script:

```yaml
env:
  RELEASE_NOTES: ${{ inputs.changelog }}
run: |
  printf '%s' "$RELEASE_NOTES" > release-notes.txt
```

Then pass that file with `gh release create --notes-file`. Apply the same data
boundary to the commit-message fallback and version/tag inputs; do not repair
only the changelog. No `eval` or second shell interpretation should be involved.

**Acceptance check:** notes containing quotes, newlines, backticks, and command
substitution survive byte-for-byte. Invalid versions fail before source editing
or publishing; valid metadata cannot create a marker or run an extra command.

### R-02 — The CI signer is not tied to the distributed app

**Evidence:** `mobile-app/androidApp/build.gradle.kts:23-30` signs the release
variant with `signingConfigs.getByName("debug")`. The CI workflow checks out
source, sets up Java/Gradle, and builds at
`.github/workflows/mobile-release.yml:32-95`. It does not provision the existing
owner signing identity or compare an output certificate with a pinned expected
identity before publishing.

**Inference:** a new CI environment can produce a signed APK that is not
compatible with an app installed from the owner's local builds. Android's
ordinary update path requires a compatible signing certificate; a matching
download digest proves byte integrity, not signer compatibility. Debug signing
is generated locally by the tools. These platform requirements are documented
in [Android's signing guide](https://developer.android.com/studio/publish/app-signing).

**Limit:** no keystore, signing secret, deployed APK, or certificate fingerprint
was read or generated. This is a missing CI identity guarantee, not a reproduced
failure of the currently installed app. A working local signing identity should
not be replaced casually; changing it can strand existing installations.

**How I would fix it:** provision the authorized existing identity through the
approved secret-management path and compare the resulting certificate fingerprint
with a nonsecret expected value before publication. Keep signing material out of
logs, artifacts, caches, and this repository. A later production-key migration
needs a supported distribution/migration plan rather than an automatic key swap.

**Acceptance check:** outputs from two clean CI environments and the existing
distribution lineage have the expected compatible signer. A wrong signer stops
publication even if the package's SHA-256 matches its own manifest. Verification
can compare public certificates without exposing the private key.

### R-03 — A display label is being used as if it renamed the asset

**Evidence:** the workflow's APK path is `androidApp-release.apk` at
`.github/workflows/mobile-release.yml:102`. Upload at `:119` appends
`#ClaudeBot-<version>.apk`. In `gh release create`, the suffix after `#` is
a display label, not a new asset filename. See the
[official CLI contract](https://cli.github.com/manual/gh_release_create).
The puller requests `--pattern "ClaudeBot-*.apk"` and searches for that basename
at `scripts/mobile_pull_release.sh:33-35`.

**Observed:** a fixture `gh` exposing the workflow basename did not match the
puller's requested pattern; the unchanged copied pull script exited 1. With a
matching renamed fixture asset, the script proceeded. This checks the naming
assumptions under a fake CLI, not a real GitHub download.

**How I would fix it:** rename/copy the built artifact to its intended actual
filename before upload, or read the exact asset name from a release manifest.
Keep the label decorative. Require exactly one matching artifact and reject
ambiguous assets rather than selecting whichever `find` returns first.

**Acceptance check:** the producer's uploaded basename matches the consumer's
selection. A release fixture with zero or multiple APK candidates fails clearly;
one manifest-selected candidate reaches validation.

### R-04 — Metadata version codes change without changing the artifact

**Evidence:** `scripts/mobile_pull_release.sh:37-44` reads `versionCode` from
the local source checkout, takes the last component of the release name, and
selects that value or `CURRENT_CODE + 1`. It never reads the downloaded APK's
embedded code. The workflow independently supports an explicit version-code
input, so the two paths are not guaranteed to agree.

**Observed:** two runs of the unchanged pull script on identical synthetic
artifact bytes produced advertised codes 20 and 21 when only the temporary
checkout's current code changed from 19 to 20. The captured artifact digest was
identical. The bytes were a noninstallable dummy file; no embedded manifest or
real APK version was inspected.

**Impact:** the host can claim that an unchanged or older artifact is a new
update. Client availability and the installer's embedded version may then
disagree. A semver patch number is not a reliable APK version-code authority.

**How I would fix it:** extract package identity/version from the artifact and
cross-check an immutable manifest supplied by the producer. Publish that exact
code, reject mismatches and non-increasing releases under the chosen channel
policy, and keep repeated pulls idempotent.

Example of the intended data relationship:

```json
{
  "version_name": "0.4.13",
  "version_code": 20,
  "channel": "stable",
  "artifact_name": "ClaudeBot-0.4.13.apk"
}
```

The actual manifest should also include byte size, digest, source revision, and
signer information. Those values must come from verification of the built file,
not from naming conventions or the importing machine's checkout.

**Acceptance check:** pulling the same release twice preserves code and bytes;
changing the local checkout cannot relabel it. An artifact/manifest mismatch is
rejected before updater fields or deployed files are changed.

### R-05 — The page-fetch limit does not bound downloaded bytes

**Evidence:** `_fetch_one` at `Virtual Bot/tools/search.py:494-503` calls
`await client.get(...)`, which buffers the response. Only afterwards does it
slice `response.content` at `:517` using `_FETCH_MAX_BYTES = 200_000` (`:439`).
The reported byte count at `:524` is the sliced length.

**Observed:** a mock transport emitted 128 chunks totaling 2,097,152 bytes.
Every chunk was consumed; the result still returned `ok: true` and
`bytes: 200000`. This demonstrates transfer/buffering behavior, not production
memory exhaustion. Up to ten pages can be requested concurrently.

**How I would fix it:** use streaming, cap bytes while reading, and reject
oversized responses before collecting/extracting them. Define both encoded and
decoded limits if accepting compressed bodies. A simple decoded-byte guard is:

```python
async def bounded_body(response, maximum: int) -> bytes:
    body = bytearray()
    async for chunk in response.aiter_bytes():
        if len(body) + len(chunk) > maximum:
            raise ValueError("page_too_large")
        body.extend(chunk)
    return bytes(body)
```

The caller must open the response in a streaming context and close it on every
exit; applying this helper to an already-buffered response is too late. Bound
decoder output too, or reject unsupported content encodings. Return truthful
size/truncation metadata instead of making a capped prefix look like a full read.

**Acceptance check:** an over-limit chunk stream stops promptly, closes its
response, and produces a specific failure/partial-read receipt. Concurrent
fetches stay within a declared aggregate budget.

### R-06 — The pre-fetch DNS check defeats cooperative timeouts

**Evidence:** `_check_public_fetch` at `Virtual Bot/tools/search.py:450-467`
calls synchronous `socket.getaddrinfo`. `_fetch_one` invokes it before its first
network await at `:490`. The outer `asyncio.wait_for` cannot interrupt a function
that blocks its own event-loop thread. The HTTP request timeout covers a later
operation, not this synchronous call.

**Observed:** a synthetic 120ms DNS call left a 10ms timer unfinished and let
the fetch return successfully after approximately 125ms despite a synthetic
20ms outer timeout. The actual six/twelve-second production settings were not
benchmarked or forced to stall. No real DNS lookup occurred.

**How I would fix it:** resolve off the event loop under a total deadline, and
pin the validated destination rather than asking the HTTP client to resolve
the hostname again. Reuse the design in `mobile_images._destination` and its
Host/SNI treatment while adapting it for bounded text responses. Disable ambient
proxy routing for this public-fetch boundary and keep redirects disabled until
each target can be revalidated. Do not silently expose a checked-host-only
helper as a complete DNS-rebinding guarantee.

**Acceptance check:** slow DNS does not block unrelated timers/listeners; the
total deadline returns predictably. A destination changed between validation
and connection cannot bypass the private-address policy. Thread cancellation
does not terminate a resolver thread, so keep that resource lifetime bounded
and accounted for separately.

## Additions I would build next

1. **One immutable release manifest.** Connect artifact name, byte size, digest,
   embedded version, channel, compatible signer, and source revision. CI,
   importer, host metadata, and phone should consume the same verified receipt.
2. **A publication preflight and dry run.** Validate metadata, naming, signing,
   channel policy, artifact integrity, and active-job readiness before changing
   deployed bytes or updater fields. Show precisely what would change; make
   repeated imports produce no new release state.
3. **Source-read receipts.** Distinguish a search snippet, a 600-character page
   excerpt, a truncated body, and a fully read document. Let tool results state
   their coverage explicitly so the assistant cannot imply it read an entire
   source when it only saw a small preview.
4. **A shared outbound-fetch policy.** Consolidate DNS pinning, redirect checks,
   credential exclusion, content encoding, total deadlines, and byte budgets
   across media and text fetches. Keep per-feature type rules, using the current
   httpx implementation rather than introducing another network library.
5. **Freshness in the follow-up index.** The existing `AGENT-FOLLOWUPS.md` still
   has rows from its October 5 triage. Attach an inspected revision/date and fix
   evidence to each status so later agents do not treat fixed revocation/size
   issues as still open. Existing reports remain historical evidence.

## Scope and suggested order

Fix workflow data handling and establish signing compatibility before relying
on the CI updater path. Align artifact names and verified version codes next.
Make page reads truly bounded and DNS validation cooperative before extending
parallel fetch counts or raising limits.

The calculator's resource isolation and the new native preview architecture
were inspected but not certified exhaustively. No signing key was accessed,
workflow triggered, release uploaded, updater published, service restarted, or
native package installed. The replay evidence and passing existing tests do not
turn the six proposed fixes into shipped behavior.
