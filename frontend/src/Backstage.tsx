import { useState, type FormEvent, type ReactNode } from 'react';
import { api, type Backstage, type DeviceHealth, type SensorState, type SensorStatus, type Trace } from './api';
import { ago, bytes, count, dateShort, instant, millis, percent } from './format';
import { useInterval, useLoad } from './hooks';
import { ChevronDown, ChevronRight, Desktop, Phone, Refresh } from './icons';
import { Badge, Card, Loadable, Notice, Progress, Skeleton } from './ui';

export const SENSOR_STATE: Record<SensorState, { label: string; tone: 'ok' | 'neutral' | 'warn' | 'crit'; explain: string }> = {
  active: { label: 'Active', tone: 'ok', explain: 'Reported recently.' },
  expected_stop: { label: 'Stopped as expected', tone: 'neutral', explain: 'The device announced a shutdown or suspend.' },
  late: { label: 'Late', tone: 'warn', explain: 'No health record for longer than usual.' },
  stopped: { label: 'Stopped', tone: 'crit', explain: 'A permission or a service is off on the device.' },
  silent: { label: 'Silent', tone: 'crit', explain: 'No health record beyond the gap rule. Days it covers are marked incomplete.' },
};

const stateOf = (s: SensorState) =>
  SENSOR_STATE[s] ?? { label: String(s), tone: 'warn' as const, explain: 'Unknown state.' };

/** The database alert threshold, as a share of its budget. */
export const DB_ALERT = 0.7;

export function BackstageView({ traceSegmentId, onTrace }: {
  traceSegmentId?: string | null;
  onTrace?: (segmentId: string | null) => void;
}) {
  const data = useLoad(() => api.backstage(), 'backstage');
  useInterval(data.reload, 60_000, data.data !== null);

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Backstage</h1>
        <span className="grow" />
        <button className="btn ghost small" onClick={data.reload} disabled={data.loading}>
          <Refresh size={15} /> Refresh
        </button>
      </div>
      <Loadable state={data} skeleton={<section className="card"><Skeleton rows={6} /></section>}>
        {(b) => <BackstageBody b={b} />}
      </Loadable>
      <TraceCard segmentId={traceSegmentId ?? null} onSegment={(id) => onTrace?.(id)} />
    </>
  );
}

function BackstageBody({ b }: { b: Backstage }) {
  const rows = b.devices.flatMap((d) => d.sensors.map((s) => ({ device: d, sensor: s })));
  const attention = rows.filter((r) => r.sensor.state !== 'active' && r.sensor.state !== 'expected_stop').length;
  const share = b.database.budgetBytes > 0 ? b.database.sizeBytes / b.database.budgetBytes : 0;

  return (
    <>
      {share >= DB_ALERT && (
        <Notice tone={share >= 0.9 ? 'crit' : 'warn'} title={`Database at ${percent(share * 100)} of its budget`}>
          {bytes(b.database.sizeBytes)} of {bytes(b.database.budgetBytes)}. Plan a cleanup or a larger budget.
        </Notice>
      )}

      <Card
        title="Sensors"
        hint={rows.length === 0 ? undefined : attention === 0 ? 'All reporting' : `${attention} need${attention === 1 ? 's' : ''} attention`}
      >
        {b.devices.length === 0 ? (
          <p className="help">No devices are paired yet.</p>
        ) : (
          b.devices.map((d) => <DeviceSensors key={d.id} device={d} />)
        )}
      </Card>

      <div className="grid2">
        <Card title="Database">
          <div className="dbline">
            <span className="num">{bytes(b.database.sizeBytes)}</span>
            <span className="muted">of {bytes(b.database.budgetBytes)} · {percent(share * 100)}</span>
          </div>
          <Progress value={share} label="Database size against budget" tone={share >= 0.9 ? 'crit' : share >= DB_ALERT ? 'warn' : undefined} />
          <p className="help">An alert shows from {percent(DB_ALERT * 100)} of the budget.</p>
        </Card>

        <Card title="Derivation" hint={`Rules version ${b.derivation.version}`}>
          {b.derivation.rebuilding ? (
            <>
              <p>Recalculating past days · <span className="num">{percent(b.derivation.progress * 100)}</span></p>
              <Progress value={b.derivation.progress} label="Recalculation progress" />
            </>
          ) : (
            <p className="help">Up to date. Every segment was derived with the current rules.</p>
          )}
        </Card>
      </div>

      <Card title="Blind spots" hint="What this setup cannot see">
        {b.blindSpots.length === 0 ? (
          <p className="help">None listed.</p>
        ) : (
          <ul className="bullets">
            {b.blindSpots.map((s) => <li key={s}>{s}</li>)}
          </ul>
        )}
      </Card>
    </>
  );
}

function DeviceSensors({ device }: { device: DeviceHealth }) {
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div className="devhealth">
      <h3 className="group">
        {device.kind === 'android' ? <Phone size={16} /> : <Desktop size={16} />} <span className="label">{device.name}</span>
      </h3>
      <ul className="sensors">
        {device.sensors.map((s) => {
          const meta = stateOf(s.state);
          const isOpen = open === s.name;
          return (
            <li key={s.name}>
              <button
                className="sensorrow"
                aria-expanded={isOpen}
                onClick={() => setOpen(isOpen ? null : s.name)}
              >
                {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                <span className="sname">{s.name}</span>
                <Badge tone={meta.tone}>{meta.label}</Badge>
                <span className="grow" />
                <span className="muted small" title={instant(s.lastContact)}>{ago(s.lastContact)}</span>
              </button>
              {isOpen && <SensorDetail device={device} sensor={s} />}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function SensorDetail({ device, sensor }: { device: DeviceHealth; sensor: SensorStatus }) {
  const meta = stateOf(sensor.state);
  const gaps = device.gaps.filter((g) => g.sensor === sensor.name);
  const unexpected = gaps.filter((g) => !g.expected);
  return (
    <div className="sdetail">
      <p>{meta.explain}</p>
      {sensor.detail && <p className="muted">Device says: <span className="label">{sensor.detail}</span></p>}
      <p className="muted small">Last contact {instant(sensor.lastContact)}</p>

      {sensor.name === 'shorts' && <ShortsState shorts={device.shorts} />}

      <h4>Gaps</h4>
      {gaps.length === 0 ? (
        <p className="help">No gaps.</p>
      ) : (
        <ul className="rows">
          {gaps.map((g) => (
            <li key={`${g.from}-${g.to}`} className={g.expected ? 'muted' : undefined}>
              <span className="grow">{instant(g.from)} – {instant(g.to)}</span>
              <span className="num">{millis(g.to - g.from)}</span>
              {g.expected ? <Badge>Expected (off or suspended)</Badge> : <Badge tone="warn">Gap</Badge>}
            </li>
          ))}
        </ul>
      )}
      {unexpected.length > 0 && <p className="help">Unexpected gaps inside the awake window mark those days incomplete.</p>}

      <DeviceQuality device={device} showShorts={sensor.name !== 'shorts'} />
    </div>
  );
}

function ShortsState({ shorts }: { shorts: DeviceHealth['shorts'] }) {
  if (!shorts) return null;
  if (shorts.unconfirmedSinceVersion) {
    return (
      <Notice tone="warn" title={`Shorts not classified since YouTube ${shorts.unconfirmedSinceVersion}`}>
        Shorts time counts as YouTube until the detector recognises this version.
        {shorts.lastConfirmedVersion && ` Last confirmed on ${shorts.lastConfirmedVersion}.`}
      </Notice>
    );
  }
  if (shorts.lastConfirmedVersion) return <p className="ok">Shorts detection confirmed on YouTube {shorts.lastConfirmedVersion}.</p>;
  return <p className="help">No Shorts markers yet.</p>;
}

function Stat({ label, value, warn }: { label: string; value: ReactNode; warn?: boolean }) {
  return (
    <div className={warn ? 'stat warn' : 'stat'}>
      <dt>{label}</dt>
      <dd className="num">{value}</dd>
    </div>
  );
}

function DeviceQuality({ device: d, showShorts }: { device: DeviceHealth; showShorts: boolean }) {
  const days = [...d.eventsPerDay].sort((a, b) => (a.date < b.date ? 1 : -1)).slice(0, 7);
  return (
    <>
      <h4>{d.name}: data quality</h4>
      <dl className="stats">
        <Stat label="Lag p50" value={millis(d.lagMs.p50)} />
        <Stat label="Lag p95" value={millis(d.lagMs.p95)} />
        <Stat label="Duplicates" value={count(d.duplicates)} />
        <Stat label="Rejected" value={count(d.rejected)} warn={d.rejected > 0} />
        <Stat label="Dropped fields" value={count(d.droppedFields)} warn={d.droppedFields > 0} />
        <Stat label="Interval conflicts" value={count(d.intervalConflicts)} warn={d.intervalConflicts > 0} />
        <Stat label="Invariant violations" value={count(d.invariantViolations)} warn={d.invariantViolations > 0} />
        <Stat label="Clock skew" value={millis(d.clockSkewMs)} warn={d.clockSkewMs !== null && Math.abs(d.clockSkewMs) > 60_000} />
      </dl>
      {days.length > 0 && (
        <>
          <h4>Events per day</h4>
          <ul className="rows compact">
            {days.map((e) => (
              <li key={e.date}>
                <span className="grow">{dateShort(e.date)}</span>
                <span className="num">{count(e.count)}</span>
              </li>
            ))}
          </ul>
        </>
      )}
      {showShorts && d.shorts && <ShortsState shorts={d.shorts} />}
    </>
  );
}

// ---------------------------------------------------------------- trace

function TraceCard({ segmentId, onSegment }: { segmentId: string | null; onSegment: (id: string | null) => void }) {
  const [input, setInput] = useState(segmentId ?? '');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSegment(input.trim() || null);
  };
  return (
    <Card title="Trace a segment" hint="The raw events and rules behind one number">
      <form className="formrow" onSubmit={submit}>
        <div className="field grow">
          <label htmlFor="seg">Segment id</label>
          <input id="seg" className="input mono" value={input} onChange={(e) => setInput(e.target.value)} />
        </div>
        <button className="btn ghost" type="submit" disabled={!input.trim()}>Trace</button>
      </form>
      {segmentId && <TraceResult key={segmentId} segmentId={segmentId} />}
    </Card>
  );
}

function TraceResult({ segmentId }: { segmentId: string }) {
  const trace = useLoad(() => api.trace(segmentId), segmentId);
  return (
    <div className="trace" data-testid="trace">
      <Loadable state={trace} skeleton={<Skeleton rows={3} lead={false} />}>
        {(t) => <Tree value={t as Trace} depth={0} />}
      </Loadable>
    </div>
  );
}

const MAX_ITEMS = 200;

/** Renders any JSON as text. Event fields (titles, package names) are data, never markup. */
export function Tree({ value, depth }: { value: unknown; depth: number }) {
  if (value === null || value === undefined) return <span className="muted">—</span>;
  if (depth > 6) return <span className="muted">…</span>;
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="muted">none</span>;
    return (
      <ol className="tree">
        {value.slice(0, MAX_ITEMS).map((v, i) => (
          <li key={i}><Tree value={v} depth={depth + 1} /></li>
        ))}
        {value.length > MAX_ITEMS && <li className="muted">and {value.length - MAX_ITEMS} more</li>}
      </ol>
    );
  }
  if (typeof value === 'object') {
    return (
      <dl className="tree">
        {Object.entries(value as Record<string, unknown>).map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd><Tree value={v} depth={depth + 1} /></dd>
          </div>
        ))}
      </dl>
    );
  }
  return <span className="mono label">{String(value)}</span>;
}
