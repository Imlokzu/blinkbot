/*
 * Benchmark intelligence index next to each model in the picker.
 *
 * The number comes from the server (`model_intelligence.py`): six public
 * Epoch AI benchmarks folded into 0–100 with a Rasch fit. This file only
 * reads that reply defensively and turns one entry into the words of its
 * tooltip; it never recomputes the index.
 */

export interface IntelEntry {
  index: number;
  /** Raw benchmark scores, 0–1, keyed like `IntelBenchmark.key`. */
  scores: Record<string, number>;
}

export interface IntelBenchmark {
  key: string;
  /** A proper name ("GPQA Diamond"), the same in every language. */
  name: string;
}

export interface IntelligenceResponse {
  available: boolean;
  updated: number;
  source: { name: string; url: string; license: string };
  benchmarks: IntelBenchmark[];
  models: Record<string, IntelEntry>;
}

/** Fewer benchmarks than this and the index is shown as approximate. */
export const SOLID_COVERAGE = 4;

const EMPTY: IntelligenceResponse = {
  available: false,
  updated: 0,
  source: { name: '', url: '', license: '' },
  benchmarks: [],
  models: {},
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);

/** A reply from an older or newer server must never break the picker. */
export function parseIntelligence(raw: unknown): IntelligenceResponse {
  if (!isRecord(raw)) return EMPTY;
  const source = isRecord(raw.source) ? raw.source : {};
  // Only an http(s) link is rendered as the attribution href.
  const url = typeof source.url === 'string' && /^https?:\/\//.test(source.url) ? source.url : '';
  const benchmarks = Array.isArray(raw.benchmarks)
    ? raw.benchmarks.filter((b): b is IntelBenchmark => isRecord(b) && typeof b.key === 'string' && typeof b.name === 'string')
      .map(({ key, name }) => ({ key, name }))
    : [];
  const models: Record<string, IntelEntry> = {};
  if (isRecord(raw.models)) {
    for (const [id, entry] of Object.entries(raw.models)) {
      if (!isRecord(entry) || !finite(entry.index)) continue;
      const scores: Record<string, number> = {};
      if (isRecord(entry.scores)) {
        for (const [key, value] of Object.entries(entry.scores)) if (finite(value)) scores[key] = value;
      }
      models[id] = { index: Math.min(100, Math.max(0, entry.index)), scores };
    }
  }
  return {
    available: raw.available === true,
    updated: finite(raw.updated) ? raw.updated : 0,
    source: {
      name: typeof source.name === 'string' ? source.name : '',
      url,
      license: typeof source.license === 'string' ? source.license : '',
    },
    benchmarks,
    models,
  };
}

/** How many of the index's benchmarks this model was actually scored on. */
export function coverage(entry: IntelEntry, benchmarks: IntelBenchmark[]): number {
  return benchmarks.filter(({ key }) => key in entry.scores).length;
}

/** "GPQA Diamond 94% · SciCode 57%": the scores the index was built from, in index order. */
export function scoreLine(entry: IntelEntry, benchmarks: IntelBenchmark[]): string {
  return benchmarks
    .filter(({ key }) => key in entry.scores)
    .map(({ key, name }) => `${name} ${Math.round(entry.scores[key] * 100)}%`)
    .join(' · ');
}
