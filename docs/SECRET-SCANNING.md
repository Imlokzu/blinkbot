# Secret scanning

This repository uses four layers to keep credentials out of commits and the
remote repository:

- **Gitleaks** is the primary detector. It blocks staged secrets in the local
  pre-commit hook and scans the complete Git history in GitHub Actions.
- **TruffleHog** is a second detector. The local hook scans the complete staged
  snapshot, while CI scans the complete Git history for verified and unknown
  results.
- **GitHub Secret Scanning** and **push protection** are enabled on the
  repository. GitHub's non-provider-pattern setting is not available to this
  user-owned public repository on the current plan, so generic patterns are
  covered locally by Gitleaks and TruffleHog instead.
- **pre-commit** installs and runs both local hooks before a commit is created.

## Install locally

On macOS with Homebrew:

```bash
brew install gitleaks trufflehog pre-commit
pre-commit install
```

The hook expects `gitleaks` and `trufflehog` on `PATH`. The project pins the
Gitleaks configuration in `.gitleaks.toml`; the TruffleHog history job is
defined in `.github/workflows/secret-scan.yml`.

Add files before committing so the hook checks the exact staged snapshot:

```bash
git add <files>
pre-commit run
git commit -m "..."
```

To audit the complete repository history locally without creating a commit:

```bash
gitleaks git . --no-banner --redact --config .gitleaks.toml --exit-code 1
scripts/secret_scan_trufflehog.sh history
```

The TruffleHog wrapper intentionally keeps detector output out of terminal and
CI logs. It reports only that a scan failed, so a secret is not copied into a
log while the commit is being blocked.

`.trufflehogignore` contains one documented synthetic test fixture that would
otherwise produce a false positive. It contains no secret value.

If a detector finds a real credential, remove it from the working tree, rotate
it at the provider, and audit the Git history before deciding whether an
exception is safe. Never add a credential to an ignore file.
