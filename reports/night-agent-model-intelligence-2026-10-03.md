# Night agent review: benchmark index parser

The benchmark intelligence layer added to the model picker reads a server
response defensively, but its finite-value checks accepted scores and benchmark
leaders outside the documented 0–1 range. A malformed or stale response could
therefore show values such as 400% in the picker tooltip.

The parser now clamps benchmark leaders and model scores to 0–1 before they
reach coverage, leader comparisons, or tooltip text. Existing index clamping
and unknown-value handling remain unchanged. A regression covers positive and
negative out-of-range values.

Validation: intelligence tests 10 passed, dashboard tests 207 passed, and
TypeScript typecheck passed. An independent adversarial review approved the
scoped parser change. No server or benchmark data was downloaded during tests.
