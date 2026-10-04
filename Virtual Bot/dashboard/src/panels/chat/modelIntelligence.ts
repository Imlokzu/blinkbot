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
  /** Best score any model reached here, 0–1; 0 when unknown. */
  top: number;
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
      .map(({ key, name, top }) => ({
        key,
        name,
        top: finite(top) && top > 0 ? Math.min(1, top) : 0,
      }))
    : [];
  const models: Record<string, IntelEntry> = {};
  if (isRecord(raw.models)) {
    for (const [id, entry] of Object.entries(raw.models)) {
      if (!isRecord(entry) || !finite(entry.index)) continue;
      const scores: Record<string, number> = {};
      if (isRecord(entry.scores)) {
        for (const [key, value] of Object.entries(entry.scores)) {
          if (finite(value)) scores[key] = Math.min(1, Math.max(0, value));
        }
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

/*
 * Speedometer zones. The cut points follow where today's models actually
 * sit, not round numbers: small open models land around 30, last year's
 * flagships around 60, the current frontier from 65 up. Four zones are
 * enough to read at a glance; a fifth would be a distinction the ± of a
 * six-benchmark index cannot carry.
 */
export const ZONES = [
  { from: 0, to: 30, tone: 'err' },
  { from: 30, to: 50, tone: 'warn' },
  { from: 50, to: 65, tone: 'ok-soft' },
  { from: 65, to: 100, tone: 'ok' },
] as const;

export type ZoneTone = (typeof ZONES)[number]['tone'];

/** 0 basic, 1 capable, 2 strong, 3 frontier — the zone an index falls in. */
export function tierOf(index: number): 0 | 1 | 2 | 3 {
  const at = ZONES.findIndex(({ to }) => index < to);
  return (at === -1 ? ZONES.length - 1 : at) as 0 | 1 | 2 | 3;
}

/** Point on the gauge's upper half-circle: 0 is the left end, 100 the right. */
export function gaugePoint(value: number, cx: number, cy: number, r: number): { x: number; y: number } {
  const angle = Math.PI * (1 - Math.min(100, Math.max(0, value)) / 100);
  return { x: cx + r * Math.cos(angle), y: cy - r * Math.sin(angle) };
}

/** SVG path of the gauge arc between two values. */
export function gaugeArc(from: number, to: number, cx: number, cy: number, r: number): string {
  const start = gaugePoint(from, cx, cy, r);
  const end = gaugePoint(to, cx, cy, r);
  const f = (n: number) => n.toFixed(2);
  // Any span of a half-circle is under 180°, so the small-arc flag stays 0.
  return `M ${f(start.x)} ${f(start.y)} A ${r} ${r} 0 0 1 ${f(end.x)} ${f(end.y)}`;
}

/** A raw score as a share of the benchmark leader's, 0–1. */
export function againstLeader(score: number, top: number): number {
  return top > 0 ? Math.min(1, Math.max(0, score / top)) : 0;
}

const INTEL_STORAGE_KEY = 'claudeBotModelIntel';

/**
 * Whether the picker's index is shown. It defaults to ON — the number is why
 * the picker exists for the owner; Settings → Appearance turns it off. A
 * legacy "1"/"0" from the brain toggle reads the same as the new value.
 */
export function loadShowIntel(storage: Pick<Storage, 'getItem'> = localStorage): boolean {
  try { return storage.getItem(INTEL_STORAGE_KEY) !== '0'; } catch { return true; }
}

/** Persist first: a blocked browser keeps the current in-memory choice. */
export function saveShowIntel(value: boolean, storage: Pick<Storage, 'setItem'> = localStorage): void {
  try { storage.setItem(INTEL_STORAGE_KEY, value ? '1' : '0'); } catch { /* Optional storage. */ }
}
