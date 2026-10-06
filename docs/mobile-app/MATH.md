# Mathematics in bot replies

The Android/shared Compose client renders inline `$...$` or `\(...\)` formulas
and display `$$...$$` or `\[...\]` equations with
`io.github.huarangmeng:latex-renderer:1.5.6`. Fonts ship with the app; rendering
requires neither a WebView nor a network call. Fractions, roots, sums, integrals
and matrices use native formula layout. Wide equations scroll inside the bubble;
inline formulas retain surrounding Markdown styles, links and table cells.
Original message text remains the source for copy, share and history.

Code spans/blocks, link destinations and currency text are protected. Incomplete
formulas remain readable source until their delimiters close. Bounded formula
length/depth and unsupported macro fallback avoid sending unlimited documents
into the formula parser. Ordinary Markdown retains its existing rendering path.
The web dashboard uses its existing code-aware delimiter normalizer with
remark-math and KaTeX, including MathML, bounded expansion and untrusted links
disabled. Completed blockquote equations remain display math.

`python_calculate` runs numeric Python in a fresh local Pydantic Monty worker.
It supports `math`, arithmetic, complex numbers, comprehensions and functions;
NumPy and SymPy are not available. The tool returns printed output and the last
expression, or a structured failure. No filesystem mounts, host callbacks or
network access are exposed. Worker time, memory and output are bounded; result
formatting also happens inside the worker. Installation requires the pinned
`pydantic-monty` dependency in `Virtual Bot/requirements.txt`.

The tools-MCP declaration and local registry share `calculation_schema.py`.
Deployment must enable `tools__python_calculate` in OpenClaw's tool allowlist and
reload MCP discovery. A declared tool is not automatically available to the
model under the configured minimal profile. Chat-channel instructions describe
LaTeX syntax and calculation-tool use without changing voice/screen formatting.

Validation sources: `MathMarkdownTest`, `MathRenderingTest`, dashboard
`math.test.mjs` / `math.browser.mjs`, and backend `test_calculation_tools.py` /
`test_calculation_api_smoke.py`. The browser fixture uses the actual chat Markdown
component and no bot API calls. Android screenshots validate native glyphs and
inline placeholders. iOS shares the renderer but has not been runtime-validated.

Upstream references: https://github.com/huarangmeng/latex,
https://github.com/remarkjs/remark-math, https://katex.org/,
and https://pydantic.dev/docs/monty/quickstart/python/ .
