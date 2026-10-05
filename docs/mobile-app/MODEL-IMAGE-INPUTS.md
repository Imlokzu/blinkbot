# Verifying model image input

A picker badge is not proof of image delivery. The installed OpenClaw gateway
can receive valid image bytes and then silently remove them when the effective
model declares text-only input. An omitted authored `input` field can introduce
that default even when provider metadata explicitly supports images.

The mobile model picker therefore does not display image-capability badges or
reorder models using those badges. Explicit model selections remain explicit.
The web's configured image route is separate and can use a different model;
a successful web image answer does not prove that the selected text model ran.

## Evidence from the 2026-10-05 repair

- Existing authored GPT-6 Sol and Luna entries now declare `text` and `image`.
  Sol had already been corrected; this pass corrected Luna using its exact
  provider-cache entry. Other authored settings and models were preserved.
- GPT-6.1 Sol appeared in the picker but failed with an unknown-model error.
  Older text jobs had fallen back to Astra. It now has an explicit definition
  built from its exact provider metadata and installed adapter conventions:
  image input, reasoning support, 872,000 maximum context, 272,000 runtime
  context budget, and the adapter's 128,000 output-token fallback. Unknown
  pricing remains zero-valued metadata, not a claim of free service.
- Sol, Luna and GPT-6.1 Sol each recognized a newly generated six-character
  image code in a separate real gateway session. Requested and recorded models
  matched, and no tools ran. Owned temporary sessions were removed.
- Further real GPT-6.1 Sol and GPT-6 Astra checks used the actual mobile upload endpoint,
  queued message endpoint and SSE response. Uploaded bytes were unchanged,
  the job completed, the image code was recognized and the final model matched.
  Their temporary chats, gateway sessions and images were removed. Completed
  synthetic queue receipts remain as ordinary diagnostic history.

These observations cover those four live models. They are not an assertion
that every advertised model or every provider supports native image input.
Regolo's explicitly text-only GPT-OSS declarations were not relabeled. Seven
other GPT entries resolved as image-capable in isolated installed-resolver
checks and required no authored override. Unresolved runtime metadata must be
diagnosed, not treated as text-only or changed through a model-name heuristic.

## Reusable operator check

From `Virtual Bot`, run:

```sh
python3 scripts/reconcile_model_vision.py
```

The default is a local, read-only plan. It reads configured models, the picker
inventory, explicit provider-cache modalities and the installed OpenAI manifest.
It prints only model IDs, modalities, stable decisions and an evidence hash.
No provider call or gateway configuration command is made in this mode.

The planner repairs only missing input fields on existing authored OpenAI
ChatGPT entries with exact, explicit image evidence. It preserves intentional
input declarations and every unrelated setting. Discovered entries are never
added automatically. `--runtime-models` can supply separately captured resolver
metadata; the picker inventory is not an equivalent source.

After reviewing the plan, use its exact hash:

```sh
python3 scripts/reconcile_model_vision.py --apply --expect-evidence REVIEWED_HASH
```

Apply first validates through the installed CLI, checks the input snapshots
again, and conditionally merges against the full public provider definition.
This guards concurrent API/base-URL changes as well as model edits. Private or
unsupported provider fields are refused rather than copied into command-line
arguments. Post-write verification requires unrelated configuration to match.

Apply does not restart services, assert hot reload, or test recognition. Verify
those separately before describing the repair as live. When testing recognition,
use a synthetic image whose answer is absent from the prompt, record the final
model, and clean up only resources created by that test. Do not export account
credentials, user photos, full configuration or real conversation content.
