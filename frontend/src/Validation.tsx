import { useState, type FormEvent } from 'react';
import { api, isApiError, type ValidationRow } from './api';
import { dateLong, dateShort, duration, rangeLabel, shiftDays, signedPercent, todayIso } from './format';
import { useLoad, useRevision } from './hooks';
import { ChevronLeft, ChevronRight } from './icons';
import { Badge, Card, Loadable, Skeleton, errorText } from './ui';

const SPAN = 14;

/** (measured − settings) / settings, in percent. Null until both numbers exist. */
export function diffPercent(row: Pick<ValidationRow, 'measuredMinutes' | 'settingsMinutes'>): number | null {
  if (row.measuredMinutes === null || row.settingsMinutes === null || row.settingsMinutes <= 0) return null;
  return ((row.measuredMinutes - row.settingsMinutes) / row.settingsMinutes) * 100;
}

export function Validation({ revision }: { revision?: number }) {
  const today = todayIso();
  const [to, setTo] = useState(today);
  const from = shiftDays(to, -(SPAN - 1));
  const rows = useLoad(() => api.validation(from, to), `${from}:${to}`);
  useRevision(rows.reload, revision ?? 0);

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Validation</h1>
      </div>
      <p className="help lead">
        Read the phone's daily screen time in Android Settings (Digital Wellbeing) and enter it for a closed day. Days
        where the measured total differs by more than 10% are flagged.
      </p>

      <div className="daynav">
        <button className="iconbtn" onClick={() => setTo(shiftDays(to, -SPAN))} aria-label="Earlier days">
          <ChevronLeft />
        </button>
        <div className="dlabel"><h2>{rangeLabel(from, to)}</h2></div>
        <button
          className="iconbtn"
          onClick={() => {
            const next = shiftDays(to, SPAN);
            setTo(next > today ? today : next);
          }}
          disabled={to >= today}
          aria-label="Later days"
        >
          <ChevronRight />
        </button>
      </div>

      <Loadable state={rows} skeleton={<section className="card"><Skeleton rows={6} /></section>}>
        {(list) => (
          <ValidationBody
            rows={list}
            today={today}
            onSaved={(row) => rows.set(list.map((r) => (r.date === row.date ? row : r)))}
          />
        )}
      </Loadable>
    </>
  );
}

function ValidationBody({ rows, today, onSaved }: { rows: ValidationRow[]; today: string; onSaved: (r: ValidationRow) => void }) {
  const sorted = [...rows].sort((a, b) => (a.date < b.date ? 1 : -1));
  const entered = rows.filter((r) => r.settingsMinutes !== null).length;
  const flagged = rows.filter((r) => r.flagged).length;
  return (
    <>
      <EntryForm rows={sorted} today={today} onSaved={onSaved} />
      <Card
        title="Measured against Android Settings"
        hint={`${entered} of ${rows.length} days entered${flagged ? ` · ${flagged} flagged` : ''}`}
      >
        <div className="tablewrap">
          <table className="validation">
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col" className="r">Settings</th>
                <th scope="col" className="r">Measured</th>
                <th scope="col" className="r">Raw</th>
                <th scope="col" className="r">Difference</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => {
                const diff = diffPercent(r);
                return (
                  <tr key={r.date} className={r.flagged ? 'flagged' : undefined} data-testid={`vrow-${r.date}`}>
                    <td>
                      {dateShort(r.date)}
                      {!r.closed && <span className="muted small"> · {r.date >= today ? 'today' : 'not closed'}</span>}
                    </td>
                    <td className="r num">{r.settingsMinutes === null ? '—' : duration(r.settingsMinutes)}</td>
                    <td className="r num">{r.measuredMinutes === null ? '—' : duration(r.measuredMinutes)}</td>
                    <td className="r num muted">{r.rawMinutes === null ? '—' : duration(r.rawMinutes)}</td>
                    <td className="r num">
                      {diff === null ? '—' : signedPercent(diff)}
                      {r.flagged && <> <Badge tone="warn">Over 10%</Badge></>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <p className="help">
          Measured is the phone's time with the screen on and unlocked, by the derivation rules. Raw is foreground app
          time before that cut. Flags are recomputed every time this page loads.
        </p>
      </Card>
    </>
  );
}

function EntryForm({ rows, today, onSaved }: { rows: ValidationRow[]; today: string; onSaved: (r: ValidationRow) => void }) {
  const open = rows.filter((r) => r.closed && r.date < today);
  const firstMissing = open.find((r) => r.settingsMinutes === null) ?? open[0];
  const [date, setDate] = useState(firstMissing?.date ?? '');
  const [hours, setHours] = useState('');
  const [minutes, setMinutes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<ValidationRow | null>(null);

  const row = rows.find((r) => r.date === date);
  const closed = !!row && row.closed && row.date < today;
  const h = hours === '' ? 0 : Number(hours);
  const m = minutes === '' ? 0 : Number(minutes);
  const total = h * 60 + m;
  const problems: string[] = [];
  if (!Number.isInteger(h) || h < 0 || h > 24) problems.push('Hours must be a whole number from 0 to 24.');
  if (!Number.isInteger(m) || m < 0 || m > 59) problems.push('Minutes must be a whole number from 0 to 59.');
  if (problems.length === 0 && total > 25 * 60) problems.push('A day has at most 25 hours.');
  const empty = hours === '' && minutes === '';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!closed || empty || problems.length > 0) return;
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const next = await api.setValidation(date, total);
      onSaved(next);
      setSaved(next);
      setHours('');
      setMinutes('');
    } catch (err) {
      setError(
        isApiError(err, 'day_not_closed')
          ? 'This day is not closed yet: the phone has not caught up with it. Try again later.'
          : errorText(err),
      );
    } finally {
      setBusy(false);
    }
  }

  const savedDiff = saved ? diffPercent(saved) : null;

  return (
    <Card title="Enter the Settings total">
      <form className="formgrid" onSubmit={submit}>
        <div className="formrow">
          <div className="field">
            <label htmlFor="vday">Day</label>
            <select id="vday" className="input" value={date} onChange={(e) => { setDate(e.target.value); setSaved(null); setError(null); }}>
              {!date && <option value="">Pick a closed day</option>}
              {rows.map((r) => {
                const ok = r.closed && r.date < today;
                return (
                  <option key={r.date} value={r.date} disabled={!ok}>
                    {dateShort(r.date)}
                    {ok ? (r.settingsMinutes !== null ? ' · entered' : '') : r.date >= today ? ' · today, not closed' : ' · not closed'}
                  </option>
                );
              })}
            </select>
          </div>
          <div className="field narrow">
            <label htmlFor="vh">Hours</label>
            <input id="vh" className="input" inputMode="numeric" type="number" min={0} max={24} value={hours} onChange={(e) => setHours(e.target.value)} />
          </div>
          <div className="field narrow">
            <label htmlFor="vm">Minutes</label>
            <input id="vm" className="input" inputMode="numeric" type="number" min={0} max={59} value={minutes} onChange={(e) => setMinutes(e.target.value)} />
          </div>
          <button className="btn primary" type="submit" disabled={busy || !closed || empty || problems.length > 0}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
        {date && !closed && (
          <p className="err" role="alert">
            Only closed days take a value. {date >= today ? 'Today is still in progress.' : 'The phone has not caught up with this day yet.'}
          </p>
        )}
        {!empty && problems.map((p) => <p key={p} className="err" role="alert">{p}</p>)}
        {error && <p className="err" role="alert">{error}</p>}
        {open.length === 0 && <p className="help">No closed day in this range yet.</p>}
      </form>
      {saved && (
        <div className={saved.flagged ? 'result flagged' : 'result'} role="status">
          <b>{dateLong(saved.date)}</b>: measured{' '}
          <span className="num">{saved.measuredMinutes === null ? '—' : duration(saved.measuredMinutes)}</span> against{' '}
          <span className="num">{saved.settingsMinutes === null ? '—' : duration(saved.settingsMinutes)}</span> in Settings
          {savedDiff !== null && <> (<span className="num">{signedPercent(savedDiff)}</span>)</>}.
          {saved.flagged ? ' Flagged: more than 10% apart.' : ' Within 10%.'}
        </div>
      )}
    </Card>
  );
}
