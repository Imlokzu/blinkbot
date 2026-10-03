import { t as pickerText } from '@/locales/modelPicker';
import {
  SOLID_COVERAGE, ZONES, againstLeader, coverage, gaugeArc, gaugePoint, tierOf,
  type IntelBenchmark, type IntelEntry,
} from './modelIntelligence';

/*
 * The intelligence index as a speedometer: a half-circle split into four
 * coloured zones, dim where the model does not reach and full where it does.
 *
 * Zones are separate arcs with a hairline gap, not a gradient — the
 * dashboard bans gradient fills, and discrete zones are also what makes the
 * dial readable at 24px in a picker row. The needle and readout appear only
 * in the full size; the mini dial in a row relies on the number next to it.
 * Nothing here animates: a value that moves on every hover would be noise.
 */

const SIZES = {
  mini: { width: 24, height: 14, r: 10, stroke: 3, gap: 1.5 },
  full: { width: 140, height: 80, r: 60, stroke: 10, gap: 0.8 },
} as const;

export function IntelGauge({ value, size = 'mini' }: { value: number; size?: keyof typeof SIZES }) {
  const { width, height, r, stroke, gap } = SIZES[size];
  const cx = width / 2;
  const cy = height - stroke / 2 - (size === 'full' ? 3 : 0.5);
  const needle = gaugePoint(value, cx, cy, r - stroke - 6);
  return (
    <svg aria-hidden="true" className={`intel-gauge intel-gauge-${size}`} width={width} height={height}
      viewBox={`0 0 ${width} ${height}`} fill="none" strokeWidth={stroke}>
      {ZONES.map(({ from, to, tone }) => {
        // A hairline gap between zones, none at the dial's two ends.
        const start = from === 0 ? 0 : from + gap;
        const end = to === 100 ? 100 : to - gap;
        const reached = Math.min(end, value);
        return (
          <g key={tone} data-tone={tone}>
            <path className="intel-zone-track" d={gaugeArc(start, end, cx, cy, r)} />
            {reached > start ? <path className="intel-zone-fill" d={gaugeArc(start, reached, cx, cy, r)} /> : null}
          </g>
        );
      })}
      {size === 'full' ? (
        <g className="intel-needle">
          <line x1={cx} y1={cy} x2={needle.x} y2={needle.y} strokeWidth={2.5} strokeLinecap="round" />
          <circle cx={cx} cy={cy} r={4.5} />
        </g>
      ) : null}
    </svg>
  );
}

/** Area each benchmark stands for. Benchmark names are proper names; areas are interface copy. */
const AREA = {
  gpqa: 'areaGpqa',
  aime: 'areaAime',
  scicode: 'areaScicode',
  arc_agi_2: 'areaArc',
  simpleqa: 'areaSimpleqa',
  critpt: 'areaCritpt',
} as const;

const TIER = ['intelTier0', 'intelTier1', 'intelTier2', 'intelTier3'] as const;

/** What the hover card shows: the dial, the level in words, and where it comes from. */
export function IntelCard({ label, entry, benchmarks }: { label: string; entry: IntelEntry; benchmarks: IntelBenchmark[] }) {
  const index = Math.round(entry.index);
  const tier = tierOf(entry.index);
  const count = coverage(entry, benchmarks);
  return (
    <div className="intel-card">
      <p className="intel-card-model">{label}</p>
      <div className="intel-card-dial">
        <IntelGauge value={entry.index} size="full" />
        <p className="intel-card-readout">
          <span className="intel-card-value">{index}</span>
          <span className="intel-card-of">/100</span>
        </p>
      </div>
      <p className="intel-card-tier">
        <span data-tone={ZONES[tier].tone}>{pickerText(TIER[tier])}</span>
        {' · '}{pickerText('intelCoverage', { count, total: benchmarks.length })}
      </p>
      {count < SOLID_COVERAGE ? <p className="intel-card-note">{pickerText('intelPartial')}</p> : null}
      <h3 className="u-label intel-card-head">{pickerText('intelByArea')}</h3>
      <ul className="intel-card-areas">
        {benchmarks.map(({ key, name, top }) => {
          const score = entry.scores[key];
          const area = AREA[key as keyof typeof AREA];
          const taken = score !== undefined;
          return (
            <li key={key} className={taken ? undefined : 'is-missing'}>
              <span className="intel-area">
                <span className="intel-area-name">{area ? pickerText(area) : name}</span>
                <span className="intel-area-bench">{name}</span>
              </span>
              <span aria-hidden="true" className="intel-area-bar"
                style={{ '--share': `${taken ? againstLeader(score, top) * 100 : 0}%` } as React.CSSProperties} />
              <span className="intel-area-value">{taken ? `${Math.round(score * 100)}%` : pickerText('intelNotTaken')}</span>
            </li>
          );
        })}
      </ul>
      <p className="intel-card-hint">{pickerText('intelBarHint')}</p>
    </div>
  );
}
