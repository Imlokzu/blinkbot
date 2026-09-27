# Contributing

Thank you for contributing to Claude Bot. UI text and code comments are written in Ukrainian. Before making changes, read [`AGENTS.md`](AGENTS.md), do not add secrets, and work in narrow logical changes.

## Multi-agent process

1. **Planner** clarifies the scope, contracts, risks, and verification plan. It does not replace the implementation.
2. **Worker** makes a minimal change in a specific logical area, runs relevant tests, and captures evidence.
3. **Adversarial critic** independently searches for regressions, edge cases, security issues, and contract non-compliance. If necessary, fixes undergo a re-check.
4. **Pro/con analyst** weighs alternatives, pros, cons, and residual risks without expanding the agreed scope.
5. **Final integrator** checks the diff, documentation consistency, test results, and readiness for integration.

Roles can be performed by different agents or people, but the critique must be independent of the author of the change.

## Evidence and smoke test

Every step must leave reproducible evidence: changed files, commands, test results, and known risks. For service changes, the final smoke test covers startup, health/API endpoints, negative scenarios (especially path traversal if working with files), static assets, and correct process termination.

For documentation changes, check all Markdown files syntactically or using an available linter, review the diff, and make sure no URLs, names, or secrets appeared without reason.

## Pull request

Describe the goal, scope, evidence, smoke test, residual risks, and manual steps. Use the pull request template and the appropriate issue template. Commits must follow Conventional Commits; do not add a `LICENSE` without explicitly choosing the owner.
