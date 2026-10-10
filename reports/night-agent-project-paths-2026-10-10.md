# Night-agent project path repair — 2026-10-10

Starting revision: `ad931f6a`. This pass inspected the new iOS editor validation
and October 10 repository audit, reproduced the audit's synthetic observations,
then narrowed implementation to the P-01 project symlink boundary. Existing
shared drafts and staging were preserved. No real project, profile, credential,
conversation, signing account or provider was used.

## Reproduction and change

Before the repair, the audit's real-module fixture listed a project-directory
symlink's external synthetic name and changed its `.project.json` through
rename. A symlink at the predictable `.project.json.tmp` pathname also caused
rename to change a separate synthetic file. The fixture ran wholly in temporary
storage; it does not establish remote link creation or deployed exploitation.

The project registry now opens the configured code root and project directories
with no-follow directory descriptors. Listing skips unsafe entries; get, rename,
delete and existence checks reject linked project directories and linked or
nonregular metadata. Existing route handling maps unsafe operations to HTTP 400.
Timestamp scanning stays bounded to 300 entries and never follows file or
directory links. Normal names, stable slugs, API fields and chat links remain.

Each metadata writer creates a unique file exclusively with mode 0600 inside
the opened project, flushes/fsyncs it, verifies its identity and atomically
promotes it relative to that directory. A legacy temporary-file link is never
opened. Existing metadata stays untouched until promotion. Cleanup checks staging
inode ownership before unlinking, and detected external replacements are left
intact. Post-promotion validation failures can leave the promoted entry in place.
Rename returns its own submitted metadata rather than rereading another caller's
later name; 24 concurrent renames retain separate staging files.

Deletion opens this code root's trash without following links, reserves a unique
empty destination, checks directory identity and moves directory entries using
descriptors. Detectable trash/source replacements reject the success receipt.
A move that already committed retains its files even if validation then fails;
cleanup never recursively removes the committed directory. Ordinary deletion
continues to preserve project files in local trash.

Edited module comments and documentation are English. Project messages now use
keys in `project_locales.py`, with English/Ukrainian catalogs. The existing
translation checker validates this catalog and excludes it from inline-string
debt; no debt baseline was relaxed.

The descriptor and no-follow mechanisms use the existing Python/POSIX stack.
[Python's OS documentation](https://docs.python.org/3.12/library/os.html#files-and-directories)
describes descriptor-relative operations and link-handling controls.

## Boundary and separate findings

This closes the reproduced directory/metadata-link and fixed-staging-name
redirection paths. It does **not** make local coding processes an OS sandbox or
promise atomic containment of a directory's changing pathname. Authorization
is pinned to the opened directory inode. A process with filesystem permissions
can relocate that inode after a validation check, including in the final syscall
window; descriptors continue to refer to the same original directory. Review
reproduced that limit with synthetic directory moves. Detectable replacements
fail explicitly and unrelated link-target bytes remain unchanged, but a local
process can still relocate authorized data or substitute namespace entries.
The configured code root and its ancestors are trusted: `workspace.code_root()`
resolves that root before descriptors open. This repair does not harden
configured-root or per-user namespace ancestor symlinks.

Portable check/replace sequences cannot atomically coordinate arbitrary external
filesystem writers. Such isolation requires constraining those writers or an
agreed coordination boundary; this pass adds neither. Unsupported platforms
without POSIX descriptor/no-follow facilities receive no unsafe compatibility
fallback. Backend execution was checked on macOS; Raspberry Pi/Linux and Windows
runtime behavior were not exercised here.

Unique staging also eliminates the audit's demonstrated ordinary writer
collision. Renames remain last-writer-wins and have no revision compare-and-swap
or history. This is not the full proposed P-03 generation/conflict API. P-02
malformed-metadata recovery and I-01 expired-interaction capacity remain separate
unfixed audit findings. The historical audit probe still expects its original
failures; the new regression suite defines the repaired behavior.

## Validation record

- Final guarded backend run: **1,750 passed, eight skipped, 178 subtests passed**,
  with five existing deprecation warnings, in 113.01 seconds. An earlier full
  run before the review refinements had 1,746 passes; it is not the final result.
  Both used offline mode, filtered inherited environment, blocked repository
  dotenv and owner-data reads, synthetic configuration, and temporary runtime
  paths. They exercise working source, including existing shared drafts.
- Final focused project run: **41 passed and four subtests passed**, with one
  existing Starlette/httpx deprecation warning. The cases cover directory,
  metadata and staging links, live directory replacement, FIFO rejection,
  failure cleanup, concurrent renames, timestamp links, trash safety and actual
  project routes. One newly added relocation test initially recursed through
  its patched `os.rename`; saving and calling the original syscall corrected
  that test-harness defect. Final focused and independent reruns passed.
- Translation checker: **21 tests passed**; `catalogs` passed. The broader
  `all` command remains nonzero because of existing language debt in benchmark
  fixtures, dashboard/mobile source and generated editor/dashboard bundles.
  It reports no new project-catalog debt. No global migration or baseline change
  was made.
- Dashboard: **231 tests passed**, zero failures/skips; TypeScript passed.
  No production build or live-asset publication was needed for this backend fix.
- The recent iOS code passed `Tools/check-native.sh` in a temporary copy:
  Swift parsing, six plist/locale files, Compose's frame-rate plist flag,
  bounded file/import/Keychain checks, PCM/WAV recording checks and XcodeGen
  project generation. The first isolated attempt passed all earlier checks but
  XcodeGen could not find a username; the runner supplied `USER`/`LOGNAME` and
  the retry passed. No iOS source or real Xcode project was changed. This is
  local preflight validation, not a new simulator run of the historical 26
  hosted tests, UIKit editor runtime, device build or hardware audio check.
- Python AST/compile and scoped whitespace checks passed. No Linux, Windows,
  physical device, app deployment or real provider was exercised.

Run the new regression with the Mac muted, from `Virtual Bot/`:

```bash
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_projects.py tests/test_project_paths.py -q
```

## Independent review and post-review smoke

Fable and the configured specialist model were unavailable. Two separate
supported native agents reviewed at maximum effort using the installed
code-reviewer and architect instructions. LSP/ast-grep were unavailable;
AST/compile, source tracing, executable filesystem probes and tests were the
explicitly authorized diagnostic fallback. This is native independent review,
not Fable certification.

The code/spec/security lane returned **APPROVE** for the five source/test files.
It independently ran all 41 focused tests, four subtests and 21 checker tests,
plus deterministic concurrent and partial-write probes. The architecture lane
returned **WATCH**, with no unresolved blocker for the scoped alias repair.
It independently ran seven updated boundary regressions, 21 checker tests,
catalog validation and AST checks. Review caught the replaced-stage cleanup and
invalid trash receipt issues; the implementation and regressions now address
them. Final review synthesis is **COMMENT**, retaining the opened-inode/root
trust limitations above rather than claiming unrestricted sandbox containment.

After both reviews, actual `main.app` ran on `127.0.0.1:18140`, lifespan disabled,
with temporary code, workspace, brain, chats, profile, store, widgets, databases
and integration paths. Its guard blocked owner dotenv/data, external DNS and
connections; provider health targets pointed to an unused loopback port.
**37 curl expectations passed**, including linked-project rename/delete 400,
linked metadata rejection, legacy staging-link safety, normal creation/rename,
preserved project bytes in trash, three traversal responses of 400, synthetic
mobile-host 401/404 boundaries, and directly referenced static assets.
Outside synthetic metadata bytes stayed unchanged. The server exited with
code zero and the port was confirmed closed.

The Mac remains muted. No background application lifespan, ASR/TTS, live
integration, signing, production restart or release publication was performed.
