/**
 * Hand-built charts. Every mark is a React element, so any string that came from
 * an event (a video title, a package name, a category name) is rendered as text
 * and never parsed as markup. Values are revealed by tapping, never by hover only,
 * and every colour has a text label next to it.
 */
import { useEffect, useState, type KeyboardEvent } from 'react';
import type { CategoryTotal, Hour, SeriesPoint } from './api';
import { count, dateLong, duration, parseIso, percent, safeColor, weekdayShort } from './format';
import { useWidth } from './hooks';
import { Swatch } from './ui';

const toggleKeys = (fn: () => void) => (e: KeyboardEvent) => {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    fn();
  }
};

interface CategoryMeta {
  name: string;
  color: string;
}

function metaFor(categories: CategoryTotal[]) {
  const map = new Map<string, CategoryMeta>();
  for (const c of categories) map.set(c.categoryId, { name: c.name, color: safeColor(c.color) });
  return (id: string): CategoryMeta => map.get(id) ?? { name: 'Other', color: 'var(--neutral-data)' };
}

// ---------------------------------------------------------------- hourly

const PLOT_H = 132;
const LABEL_H = 20;
const MIN_COL = 22;

/**
 * One stacked column per local hour (23 or 25 on a daylight-saving day). Below
 * phone width the columns keep a minimum width and the chart scrolls sideways.
 */
export function HourlyChart({ hours, categories, onTrace }: {
  hours: Hour[];
  categories: CategoryTotal[];
  onTrace?: (segmentId: string) => void;
}) {
  const [selected, setSelected] = useState<number | null>(null);
  const { ref, width } = useWidth<HTMLDivElement>(640);
  const meta = metaFor(categories);
  const order = new Map(categories.map((c, i) => [c.categoryId, i]));

  const n = Math.max(1, hours.length);
  const colW = Math.max(MIN_COL, Math.floor(width / n));
  const svgW = colW * n;
  const totals = hours.map((h) => h.byCategory.reduce((s, c) => s + c.minutes, 0));
  const scale = Math.max(60, ...totals);
  const labelEvery = colW >= 34 ? 1 : 3;
  const pad = Math.max(2, Math.round(colW * 0.16));
  const sel = selected !== null && selected < hours.length ? selected : null;

  // On a narrow screen the chart scrolls; start with the latest hour that has data in view
  const lastWithData = totals.reduce((last, t, i) => (t > 0 ? i : last), -1);
  useEffect(() => {
    const el = ref.current;
    if (!el || lastWithData < 0 || el.scrollWidth <= el.clientWidth) return;
    el.scrollLeft = Math.max(0, (lastWithData + 2) * colW - el.clientWidth);
  }, [ref, lastWithData, colW]);

  return (
    <div className="hourly">
      <div className="hscroll" ref={ref}>
        <svg
          className="hsvg"
          width={svgW}
          height={PLOT_H + LABEL_H}
          viewBox={`0 0 ${svgW} ${PLOT_H + LABEL_H}`}
          role="group"
          aria-label="Screen time by hour. Select an hour to see its breakdown."
        >
          <line className="grid" x1={0} x2={svgW} y1={PLOT_H - (30 / scale) * PLOT_H} y2={PLOT_H - (30 / scale) * PLOT_H} />
          <line className="grid" x1={0} x2={svgW} y1={PLOT_H - (60 / scale) * PLOT_H} y2={PLOT_H - (60 / scale) * PLOT_H} />
          <line className="axis" x1={0} x2={svgW} y1={PLOT_H} y2={PLOT_H} />
          {hours.map((h, i) => {
            let y = PLOT_H;
            const stack = [...h.byCategory]
              .filter((c) => c.minutes > 0)
              .sort((a, b) => (order.get(a.categoryId) ?? 99) - (order.get(b.categoryId) ?? 99));
            const toggle = () => setSelected(sel === i ? null : i);
            return (
              <g
                key={`${h.start}-${i}`}
                className={sel === i ? 'hcol sel' : 'hcol'}
                transform={`translate(${i * colW},0)`}
                role="button"
                tabIndex={0}
                aria-pressed={sel === i}
                aria-label={`${h.label}, ${duration(totals[i])}`}
                onClick={toggle}
                onKeyDown={toggleKeys(toggle)}
              >
                <rect className="hit" x={0} y={0} width={colW} height={PLOT_H} />
                {stack.map((c) => {
                  const height = (c.minutes / scale) * PLOT_H;
                  y -= height;
                  const m = meta(c.categoryId);
                  return (
                    <rect key={c.categoryId} x={pad} y={y} width={colW - 2 * pad} height={height} fill={m.color}>
                      <title>{`${m.name}: ${duration(c.minutes)}`}</title>
                    </rect>
                  );
                })}
                {i % labelEvery === 0 && (
                  <text className="tick" x={colW / 2} y={PLOT_H + LABEL_H - 5} textAnchor="middle">
                    {h.label.slice(0, 2)}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      </div>

      {sel === null ? (
        <p className="help">Tap a bar to see that hour.</p>
      ) : (
        <HourDetail hour={hours[sel]} next={hours[sel + 1]} total={totals[sel]} meta={meta} onTrace={onTrace} />
      )}

      <Legend items={categories.filter((c) => c.minutes > 0).map((c) => ({ id: c.categoryId, name: c.name, color: safeColor(c.color) }))} />
    </div>
  );
}

function HourDetail({ hour, next, total, meta, onTrace }: {
  hour: Hour;
  next?: Hour;
  total: number;
  meta: (id: string) => CategoryMeta;
  onTrace?: (segmentId: string) => void;
}) {
  const rows = hour.byCategory.filter((c) => c.minutes > 0).sort((a, b) => b.minutes - a.minutes);
  return (
    <div className="hdetail" aria-live="polite">
      <div className="hdhead">
        <b>{next ? `${hour.label} – ${next.label}` : hour.label}</b>
        <span className="num">{duration(total)}</span>
      </div>
      {rows.length === 0 ? (
        <p className="help">No screen time in this hour.</p>
      ) : (
        <ul className="rows">
          {rows.map((c) => {
            const m = meta(c.categoryId);
            return (
              <li key={c.categoryId}>
                <Swatch color={m.color} />
                <span className="grow">{m.name}</span>
                <span className="num">{duration(c.minutes)}</span>
              </li>
            );
          })}
        </ul>
      )}
      {onTrace && hour.segments && hour.segments.length > 0 && (
        <div className="traces">
          <span className="help">Trace a segment:</span>
          {hour.segments.map((s) => (
            <button key={s.id} className="linkbtn" onClick={() => onTrace(s.id)}>
              {s.label} · {duration(s.minutes)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function Legend({ items }: { items: { id: string; name: string; color: string; note?: string }[] }) {
  if (items.length === 0) return null;
  return (
    <ul className="legend" aria-label="Legend">
      {items.map((i) => (
        <li key={i.id}>
          <Swatch color={i.color} />
          {i.name}
          {i.note && <span className="muted"> {i.note}</span>}
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------- bar list

/** Horizontal bars with the name, the time and the share written out. */
export function BarList({ items, total }: {
  items: { id: string; name: string; color?: string; minutes: number; note?: string }[];
  total: number;
}) {
  const max = Math.max(1, ...items.map((i) => i.minutes));
  return (
    <ul className="barlist">
      {items.map((i) => (
        <li key={i.id}>
          <div className="blrow">
            {i.color && <Swatch color={i.color} />}
            <span className="blname">{i.name}</span>
            {i.note && <span className="muted small">{i.note}</span>}
            <span className="grow" />
            <span className="num">{duration(i.minutes)}</span>
            {total > 0 && <span className="num muted share">{percent((i.minutes / total) * 100)}</span>}
          </div>
          <div className="bltrack">
            <i style={{ width: `${(i.minutes / max) * 100}%`, background: i.color ?? 'var(--accent)' }} />
          </div>
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------- trend

const TREND_H = 120;

/**
 * One column per day of the period. Days left out of the comparison (not closed,
 * partial or incomplete) are drawn as a dashed outline and say so when tapped.
 */
export function TrendChart({ series, metric, label }: {
  series: SeriesPoint[];
  metric: 'awayMinutes' | 'screenMinutes';
  label: string;
}) {
  const [selected, setSelected] = useState<number | null>(null);
  const { ref, width } = useWidth<HTMLDivElement>(640);
  const n = Math.max(1, series.length);
  const colW = Math.min(64, Math.max(18, Math.floor(width / n)));
  const svgW = colW * n;
  const values = series.map((p) => p[metric]);
  const scale = Math.max(60, ...values);
  const pad = Math.max(3, Math.round(colW * 0.2));
  const labelEvery = series.length > 14 && colW < 26 ? 5 : 1;
  const sel = selected !== null && selected < series.length ? selected : null;

  const tick = (iso: string) => (series.length <= 7 ? weekdayShort(iso) : String(parseIso(iso).getDate()));

  return (
    <div className="trend">
      <div className="hscroll" ref={ref}>
        <svg
          className="hsvg"
          width={svgW}
          height={TREND_H + LABEL_H}
          viewBox={`0 0 ${svgW} ${TREND_H + LABEL_H}`}
          role="group"
          aria-label={`${label} per day. Select a day to see its numbers.`}
        >
          <line className="axis" x1={0} x2={svgW} y1={TREND_H} y2={TREND_H} />
          {series.map((p, i) => {
            const h = Math.max(1, (p[metric] / scale) * TREND_H);
            const toggle = () => setSelected(sel === i ? null : i);
            return (
              <g
                key={p.date}
                className={sel === i ? 'hcol sel' : 'hcol'}
                transform={`translate(${i * colW},0)`}
                role="button"
                tabIndex={0}
                aria-pressed={sel === i}
                aria-label={`${p.date}, ${duration(p[metric])}${p.complete ? '' : ', left out of the comparison'}`}
                onClick={toggle}
                onKeyDown={toggleKeys(toggle)}
              >
                <rect className="hit" x={0} y={0} width={colW} height={TREND_H} />
                <rect
                  className={p.complete ? 'tbar' : 'tbar out'}
                  x={pad}
                  y={TREND_H - h}
                  width={colW - 2 * pad}
                  height={h}
                  rx={2}
                />
                {i % labelEvery === 0 && (
                  <text className="tick" x={colW / 2} y={TREND_H + LABEL_H - 5} textAnchor="middle">
                    {tick(p.date)}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      </div>
      {sel === null ? (
        <p className="help">Tap a bar to see that day.</p>
      ) : (
        <div className="hdetail" aria-live="polite">
          <div className="hdhead">
            <b>{dateLong(series[sel].date)}</b>
          </div>
          <ul className="rows">
            <li>
              <span className="grow">Awake away from screens</span>
              <span className="num">{duration(series[sel].awayMinutes)}</span>
            </li>
            <li>
              <span className="grow">Screen time</span>
              <span className="num">{duration(series[sel].screenMinutes)}</span>
            </li>
          </ul>
          {!series[sel].complete && (
            <p className="help">Left out of the comparison: the day is not closed, partial or incomplete.</p>
          )}
        </div>
      )}
      <ul className="legend" aria-label="Legend">
        <li><span className="dot solid" aria-hidden="true" /> Counted in the comparison</li>
        <li><span className="dot dashed" aria-hidden="true" /> Left out (not closed, partial or incomplete)</li>
      </ul>
    </div>
  );
}

// ---------------------------------------------------------------- daily counts

const COUNT_H = 56;
const VALUE_H = 16;

/**
 * One column per day with its count written above it, for short series such as
 * focus periods per day. The numbers are always visible; tapping a day adds its
 * date and any extra rows the caller gives.
 */
export function DayCounts({ points, label, detail }: {
  points: { date: string; count: number }[];
  /** What is counted, e.g. "Focus periods". */
  label: string;
  /** Extra rows for the tapped day. */
  detail?: (index: number) => { name: string; value: string }[];
}) {
  const [selected, setSelected] = useState<number | null>(null);
  const { ref, width } = useWidth<HTMLDivElement>(640);
  const n = Math.max(1, points.length);
  const colW = Math.min(64, Math.max(30, Math.floor(width / n)));
  const svgW = colW * n;
  const base = VALUE_H + COUNT_H;
  const svgH = base + LABEL_H;
  const max = Math.max(1, ...points.map((p) => p.count));
  const pad = Math.max(4, Math.round(colW * 0.22));
  const sel = selected !== null && selected < points.length ? selected : null;

  const tick = (iso: string) => (points.length <= 7 ? weekdayShort(iso) : String(parseIso(iso).getDate()));

  return (
    <div className="trend">
      <div className="hscroll" ref={ref}>
        <svg
          className="hsvg"
          width={svgW}
          height={svgH}
          viewBox={`0 0 ${svgW} ${svgH}`}
          role="group"
          aria-label={`${label} per day. Select a day to see it.`}
        >
          <line className="axis" x1={0} x2={svgW} y1={base} y2={base} />
          {points.map((p, i) => {
            const h = (p.count / max) * COUNT_H;
            const toggle = () => setSelected(sel === i ? null : i);
            return (
              <g
                key={p.date}
                className={sel === i ? 'hcol sel' : 'hcol'}
                transform={`translate(${i * colW},0)`}
                role="button"
                tabIndex={0}
                aria-pressed={sel === i}
                aria-label={`${p.date}, ${label.toLowerCase()}: ${count(p.count)}`}
                onClick={toggle}
                onKeyDown={toggleKeys(toggle)}
              >
                <rect className="hit" x={0} y={0} width={colW} height={base} />
                {p.count > 0 && <rect className="tbar" x={pad} y={base - h} width={colW - 2 * pad} height={h} rx={2} />}
                <text className="cval" x={colW / 2} y={base - h - 4} textAnchor="middle">
                  {count(p.count)}
                </text>
                <text className="tick" x={colW / 2} y={svgH - 5} textAnchor="middle">
                  {tick(p.date)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      {sel === null ? (
        <p className="help">Tap a bar to see that day.</p>
      ) : (
        <div className="hdetail" aria-live="polite">
          <div className="hdhead">
            <b>{dateLong(points[sel].date)}</b>
          </div>
          <ul className="rows">
            <li>
              <span className="grow">{label}</span>
              <span className="num">{count(points[sel].count)}</span>
            </li>
            {detail?.(sel).map((r) => (
              <li key={r.name}>
                <span className="grow">{r.name}</span>
                <span className="num">{r.value}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
