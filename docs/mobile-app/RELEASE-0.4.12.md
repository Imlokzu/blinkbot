# Android 0.4.12: math formulas and Python calculations

Android version code 19 adds native LaTeX formulas to chat replies. Inline
fractions, display equations, roots, integrals and matrices render with bundled
fonts; table cells and formula-only links retain their behavior. Code, currency
and unfinished formulas remain readable. The web dashboard has matching KaTeX
support. This build also retains the previously committed startup loading screen.

The live bot now has `tools__python_calculate` enabled through its existing MCP
allowlist. MCP discovery was refreshed successfully, and the live API verified
square root, multiplication and factorial results. Calculations run in fresh,
bounded Monty workers with numeric Python and `math`; NumPy/SymPy are not exposed.
No paid provider calls were needed for validation.

Published through **Profile → App updates**, with release notes, authenticated
package download and SHA-256 verification. Builds below 19 see the update; build
19 reports current. Android still asks for installation confirmation.

- Package: `runtime/releases/ClaudeBot-0.4.12.apk` (ignored runtime artifact).
- Size: 3,354,021 bytes.
- SHA-256: `52ea2f71137cd92122cf99c1f91a8ea4a8ac6a3a9a2172e73fa1f896f88ecc44`.
- Signing certificate matches the earlier owner-distributed builds.

The optimized APK built and installed successfully. Live metadata and downloaded
bytes/hash were checked after restarting only the web launchagent with no active
mobile jobs. The public dashboard and entry assets return 200; missing mobile
credentials still return 401. Source tests and the two unchanged baseline pixel
comparison limitations are recorded in HANDOFF.md and `MATH.md`. iOS was not
published or runtime-certified in this release.
