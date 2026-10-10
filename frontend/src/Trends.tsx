import { useState } from 'react';
import { api, type CategoryTotal, type Period, type Trends as TrendsData } from './api';
import { Legend, TrendChart } from './charts';
import { duration, durationParts, rangeLabel, safeColor, shiftDays, shiftMonths, signedDuration, todayIso } from './format';
import { useLoad, useRevision } from './hooks';
import { ChevronLeft, ChevronRight } from './icons';
import { Card, Loadable, Notice, Segmented, Skeleton } from './ui';

const PERIODS: { value: Period; label: string }[] = [
  { value: 'day', label: 'Day' },
  { value: 'week', label: 'Week' },
  { value: 'month', label: 'Month' },
];

function shift(period: Period, anchor: string, by: number) {
  if (period === 'day') return shiftDays(anchor, by);
  if (period === 'week') return shiftDays(anchor, 7 * by);
  return shiftMonths(anchor, by);
}

export function Trends({ revision }: { revision: number }) {
  const today = todayIso();
  const [period, setPeriod] = useState<Period>('week');
  const [anchor, setAnchor] = useState(today);
  const [metric, setMetric] = useState<'awayMinutes' | 'screenMinutes'>('awayMinutes');
  const trends = useLoad(() => api.trends(period, anchor), `${period}:${anchor}`);
  useRevision(trends.reload, revision);

  const range = trends.data ? rangeLabel(trends.data.current.from, trends.data.current.to) : null;
  const atEnd = trends.data ? trends.data.current.to >= today || anchor >= today : anchor >= today;

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Trends</h1>
        <span className="grow" />
        <Segmented label="Period" value={period} options={PERIODS} onChange={(p) => { setPeriod(p); setAnchor(today); }} />
      </div>
      <div className="daynav">
        <button className="iconbtn" onClick={() => setAnchor(shift(period, anchor, -1))} aria-label="Previous period">
          <ChevronLeft />
        </button>
        <div className="dlabel">
          <h2>{range ?? ' '}</h2>
        </div>
        <button
          className="iconbtn"
          onClick={() => {
            const next = shift(period, anchor, 1);
            setAnchor(next > today ? today : next);
          }}
          disabled={atEnd}
          aria-label="Next period"
        >
          <ChevronRight />
        </button>
      </div>

      <Loadable state={trends} skeleton={<section className="card"><Skeleton rows={6} /></section>}>
        {(t) => <TrendsBody t={t} metric={metric} onMetric={setMetric} />}
      </Loadable>
    </>
  );
}

function TrendsBody({ t, metric, onMetric }: {
  t: TrendsData;
  metric: 'awayMinutes' | 'screenMinutes';
  onMetric: (m: 'awayMinutes' | 'screenMinutes') => void;
}) {
  const prev = t.previous;
  const left = t.series.filter((p) => !p.complete).length;
  return (
    <>
      <section className="card">
        <div className="compare">
          <Figure
            label="Awake away from screens"
            minutes={t.current.awayMinutes}
            previous={prev?.awayMinutes ?? null}
          />
          <Figure
            label="Screen time"
            minutes={t.current.screenMinutes}
            previous={prev?.screenMinutes ?? null}
          />
        </div>
        {prev ? (
          <p className="help">
            Compared with {rangeLabel(prev.from, prev.to)}. A period in progress is compared with the same
            span of the previous one.
          </p>
        ) : (
          <Notice tone="info" title="No previous period">
            There is no full previous span of closed days to compare with yet.
          </Notice>
        )}
        {left > 0 && (
          <p className="help">
            {left} {left === 1 ? 'day is' : 'days are'} left out of both sides because {left === 1 ? 'it is' : 'they are'} not
            closed, partial or incomplete.
          </p>
        )}
      </section>

      <Card
        title="Per day"
        action={
          <Segmented
            label="Metric"
            value={metric}
            onChange={onMetric}
            options={[
              { value: 'awayMinutes', label: 'Away' },
              { value: 'screenMinutes', label: 'Screen' },
            ]}
          />
        }
      >
        <TrendChart series={t.series} metric={metric} label={metric === 'awayMinutes' ? 'Awake away from screens' : 'Screen time'} />
      </Card>

      <Card title="By category">
        <CategoryCompare current={t.current.byCategory} previous={prev ? prev.byCategory : null} />
      </Card>
    </>
  );
}

function Figure({ label, minutes, previous }: { label: string; minutes: number; previous: number | null }) {
  return (
    <div className="cfig">
      <div className="flabel">{label}</div>
      <div className="fval num">
        {durationParts(minutes).map((p) => (
          <span key={p.unit}>{p.value}<small>{p.unit}</small></span>
        ))}
      </div>
      <div className="fmeta">
        {previous === null ? (
          'No previous period'
        ) : (
          <>
            <span className="num">{signedDuration(minutes - previous)}</span> vs{' '}
            <span className="num">{duration(previous)}</span> before
          </>
        )}
      </div>
    </div>
  );
}

function CategoryCompare({ current, previous }: { current: CategoryTotal[]; previous: CategoryTotal[] | null }) {
  const prevById = new Map((previous ?? []).map((c) => [c.categoryId, c]));
  const ids = new Set(current.map((c) => c.categoryId));
  const rows = [
    ...current,
    ...(previous ?? []).filter((c) => !ids.has(c.categoryId)).map((c) => ({ ...c, minutes: 0 })),
  ].sort((a, b) => b.minutes - a.minutes);
  if (rows.length === 0) return <p className="help">No screen time in this period.</p>;
  const max = Math.max(1, ...rows.map((r) => Math.max(r.minutes, prevById.get(r.categoryId)?.minutes ?? 0)));
  return (
    <>
      <ul className="barlist">
        {rows.map((c) => {
          const before = previous ? (prevById.get(c.categoryId)?.minutes ?? 0) : null;
          const color = safeColor(c.color);
          return (
            <li key={c.categoryId}>
              <div className="blrow">
                <span className="dot" style={{ background: color }} aria-hidden="true" />
                <span className="blname">{c.name}</span>
                <span className="grow" />
                <span className="num">{duration(c.minutes)}</span>
                {before !== null && <span className="num muted share">{signedDuration(c.minutes - before)}</span>}
              </div>
              <div className="bltrack">
                <i style={{ width: `${(c.minutes / max) * 100}%`, background: color }} />
              </div>
              {before !== null && (
                <div className="bltrack prev" aria-hidden="true">
                  <i style={{ width: `${(before / max) * 100}%` }} />
                </div>
              )}
            </li>
          );
        })}
      </ul>
      {previous && (
        <Legend
          items={[
            { id: 'cur', name: 'This period', color: 'var(--ink-2)' },
            { id: 'prev', name: 'Previous period (thin bar)', color: 'var(--hair-2)' },
          ]}
        />
      )}
    </>
  );
}
