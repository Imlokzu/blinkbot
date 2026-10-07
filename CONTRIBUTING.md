# Contributing

Thank you for contributing to Blink. Documentation, identifiers, and code comments must be in English. User-visible text belongs in locale files and must be referenced through translation keys, with Ukrainian and English translations. Before making changes, read [`AGENTS.md`](AGENTS.md), never add secrets, and keep each logical change narrowly scoped.

## Multi-agent workflow

1. **Planner** clarifies the scope, contracts, risks, and validation plan. Planning does not replace implementation.
2. **Worker** makes a minimal change within one logical area, runs the relevant tests, and records validation evidence.
3. **Adversarial critic** independently looks for regressions, edge cases, security issues, and contract violations. Fixes are reviewed again when needed.
4. **Pro/con analyst** weighs alternatives, benefits, drawbacks, and remaining risks without expanding the agreed scope.
5. **Final integrator** checks the diff, documentation consistency, test results, and readiness for integration.

These roles may be performed by different agents or people, but the reviewer must be independent of the change's author.

## Validation evidence and smoke tests

Each stage must leave reproducible evidence: changed files, commands, test results, and known risks. For service changes, the final smoke test covers startup, health and API endpoints, negative scenarios (including path traversal when file access is involved), static assets, and clean process shutdown.

For documentation changes, check all affected Markdown files for valid syntax or use an available linter, review the diff, and ensure no URLs, names, or secrets were added without justification.

## Pull requests

Describe the purpose, scope, validation evidence, smoke test, remaining risks, and any manual steps. Use the pull request template and the appropriate issue template. Commits must follow Conventional Commits; do not add a `LICENSE` without the owner's explicit choice.

See [docs/I18N.md](docs/I18N.md) for how to handle translations and localization in this repository.
