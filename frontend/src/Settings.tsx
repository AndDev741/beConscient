import { useCallback, useState, type FormEvent } from 'react';
import { api, type Settings, type SettingsHistoryEntry } from './api';
import { WEEKDAYS, dateLong, dateShort, duration, isTime, timeToMinutes, todayIso } from './format';
import { useLoad } from './hooks';
import { Trash } from './icons';
import { Card, Loadable, Notice, Skeleton, UndoBar, errorText } from './ui';

export function SettingsView({ onChanged }: { onChanged?: () => void }) {
  const settings = useLoad(() => api.settings(), 'settings');
  const saved = (next: Settings) => {
    settings.set(next);
    onChanged?.();
  };

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Settings</h1>
      </div>
      <Notice tone="info" title="Changes apply from today">
        Editing a window creates a new version valid from today. Past days keep the windows they had, so your baseline
        never shifts. A second edit on the same day replaces that day's version.
      </Notice>
      <Loadable state={settings} skeleton={<section className="card"><Skeleton rows={5} /></section>}>
        {(s) => (
          <>
            <div className="grid2">
              <WorkWindowForm s={s} onSaved={saved} />
              <AwakeWindowForm s={s} onSaved={saved} />
            </div>
            <NonWorkdays s={s} onSaved={saved} />
            <History entries={s.history} />
          </>
        )}
      </Loadable>
    </>
  );
}

function WorkWindowForm({ s, onSaved }: { s: Settings; onSaved: (s: Settings) => void }) {
  const w = s.workWindow;
  const [days, setDays] = useState<number[]>(w.days);
  const [start, setStart] = useState(w.start);
  const [end, setEnd] = useState(w.end);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const problems: string[] = [];
  if (days.length === 0) problems.push('Pick at least one working day.');
  if (!isTime(start) || !isTime(end)) problems.push('Use times like 09:00.');
  else if (timeToMinutes(end) <= timeToMinutes(start)) problems.push('The work window must end after it starts, on the same day.');
  const dirty = start !== w.start || end !== w.end || [...days].sort().join() !== [...w.days].sort().join();

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (problems.length > 0 || !dirty) return;
    setBusy(true);
    setError(null);
    try {
      onSaved(await api.setWorkWindow([...days].sort((a, b) => a - b), start, end));
      setDone(true);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  const toggle = (n: number) => {
    setDone(false);
    setDays((d) => (d.includes(n) ? d.filter((x) => x !== n) : [...d, n]));
  };

  return (
    <Card title="Work window" hint={`Valid from ${dateShort(w.validFrom)}`}>
      <form onSubmit={submit} className="formgrid">
        <p className="help">Computer time inside this window counts as Work; outside it, as Personal computer.</p>
        <div className="weekdays" role="group" aria-label="Working days">
          {WEEKDAYS.map((d) => (
            <button
              key={d.n}
              type="button"
              aria-pressed={days.includes(d.n)}
              aria-label={d.long}
              onClick={() => toggle(d.n)}
            >
              {d.short}
            </button>
          ))}
        </div>
        <div className="formrow">
          <div className="field">
            <label htmlFor="ws">Starts</label>
            <input id="ws" className="input" type="time" value={start} onChange={(e) => { setDone(false); setStart(e.target.value); }} />
          </div>
          <div className="field">
            <label htmlFor="we">Ends</label>
            <input id="we" className="input" type="time" value={end} onChange={(e) => { setDone(false); setEnd(e.target.value); }} />
          </div>
        </div>
        {dirty && problems.map((p) => <p key={p} className="err" role="alert">{p}</p>)}
        {error && <p className="err" role="alert">{error}</p>}
        {done && !dirty && <p className="ok" role="status">Saved. Applies from today.</p>}
        <div className="formrow">
          <button className="btn primary" type="submit" disabled={busy || !dirty || problems.length > 0}>
            {busy ? 'Saving…' : 'Save from today'}
          </button>
        </div>
      </form>
    </Card>
  );
}

function AwakeWindowForm({ s, onSaved }: { s: Settings; onSaved: (s: Settings) => void }) {
  const a = s.awakeWindow;
  const [start, setStart] = useState(a.start);
  const [end, setEnd] = useState(a.end);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const valid = isTime(start) && isTime(end);
  const problems: string[] = [];
  if (!valid) problems.push('Use times like 07:00.');
  else if (start === end) problems.push('Start and end must differ.');
  const crosses = valid && timeToMinutes(end) < timeToMinutes(start);
  const length = valid ? (timeToMinutes(end) - timeToMinutes(start) + 1440) % 1440 : 0;
  const dirty = start !== a.start || end !== a.end;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (problems.length > 0 || !dirty) return;
    setBusy(true);
    setError(null);
    try {
      onSaved(await api.setAwakeWindow(start, end));
      setDone(true);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Awake window" hint={`Valid from ${dateShort(a.validFrom)}`}>
      <form onSubmit={submit} className="formgrid">
        <p className="help">The headline counts the hours inside this window that you spent away from screens.</p>
        <div className="formrow">
          <div className="field">
            <label htmlFor="as">Wake up</label>
            <input id="as" className="input" type="time" value={start} onChange={(e) => { setDone(false); setStart(e.target.value); }} />
          </div>
          <div className="field">
            <label htmlFor="ae">Go to sleep</label>
            <input id="ae" className="input" type="time" value={end} onChange={(e) => { setDone(false); setEnd(e.target.value); }} />
          </div>
        </div>
        {valid && start !== end && (
          <p className="help" data-testid="awake-length">
            {duration(length)} awake.
            {crosses && ` Crosses midnight: the window ends at ${end} the next day, and that tail counts for the day it started.`}
          </p>
        )}
        {dirty && problems.map((p) => <p key={p} className="err" role="alert">{p}</p>)}
        {error && <p className="err" role="alert">{error}</p>}
        {done && !dirty && <p className="ok" role="status">Saved. Applies from today.</p>}
        <div className="formrow">
          <button className="btn primary" type="submit" disabled={busy || !dirty || problems.length > 0}>
            {busy ? 'Saving…' : 'Save from today'}
          </button>
        </div>
      </form>
    </Card>
  );
}

function NonWorkdays({ s, onSaved }: { s: Settings; onSaved: (s: Settings) => void }) {
  const today = todayIso();
  const [date, setDate] = useState(today);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ message: string; date: string; wasAdded: boolean } | null>(null);
  const dismiss = useCallback(() => setUndo(null), []);

  const marked = s.nonWorkdays.includes(date);
  const upcoming = s.nonWorkdays.filter((d) => d >= today).sort();
  const past = s.nonWorkdays.filter((d) => d < today).sort().reverse();

  async function apply(day: string, add: boolean, offerUndo: boolean) {
    setBusy(true);
    setError(null);
    try {
      onSaved(add ? await api.addNonWorkday(day) : await api.removeNonWorkday(day));
      setUndo(offerUndo ? { message: `${dateShort(day)} ${add ? 'is now a day off' : 'is a working day again'}.`, date: day, wasAdded: add } : null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const row = (d: string) => (
    <li key={d}>
      <span className="grow">{dateLong(d)}</span>
      <button className="iconbtn" disabled={busy} onClick={() => void apply(d, false, true)} aria-label={`Remove day off ${d}`}>
        <Trash size={16} />
      </button>
    </li>
  );

  return (
    <Card title="Days off" hint="Holidays and other non-working days">
      <p className="help">On a day off, computer time counts as Personal computer all day.</p>
      <div className="formrow">
        <div className="field">
          <label htmlFor="nwd">Date</label>
          <input id="nwd" className="input" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </div>
        <button className="btn ghost" disabled={busy || !date} onClick={() => void apply(date, !marked, true)}>
          {marked ? 'Make it a working day' : 'Mark as day off'}
        </button>
      </div>
      {error && <p className="err" role="alert">{error}</p>}
      {upcoming.length === 0 && past.length === 0 ? (
        <p className="help">No days off marked.</p>
      ) : (
        <>
          {upcoming.length > 0 && (
            <>
              <h3 className="group">Upcoming</h3>
              <ul className="rows">{upcoming.map(row)}</ul>
            </>
          )}
          {past.length > 0 && (
            <details>
              <summary className="group">Past <span className="muted num">{past.length}</span></summary>
              <ul className="rows">{past.map(row)}</ul>
            </details>
          )}
        </>
      )}
      {undo && (
        <UndoBar
          message={undo.message}
          onDismiss={dismiss}
          onUndo={() => void apply(undo.date, !undo.wasAdded, false)}
        />
      )}
    </Card>
  );
}

function summary(e: SettingsHistoryEntry): string {
  const v = e.value ?? {};
  const parts: string[] = [];
  if (Array.isArray(v.days)) {
    const days = (v.days as unknown[]).filter((d): d is number => typeof d === 'number');
    parts.push(days.map((d) => WEEKDAYS.find((w) => w.n === d)?.short ?? String(d)).join(' '));
  }
  if (typeof v.start === 'string' && typeof v.end === 'string') parts.push(`${v.start}–${v.end}`);
  return parts.join(', ') || '—';
}

function History({ entries }: { entries: SettingsHistoryEntry[] }) {
  const sorted = [...entries].sort((a, b) => (a.validFrom < b.validFrom ? 1 : -1));
  return (
    <Card title="Version history">
      {sorted.length === 0 ? (
        <p className="help">No earlier versions.</p>
      ) : (
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Valid from</th>
                <th scope="col">Window</th>
                <th scope="col">Value</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((e, i) => (
                <tr key={`${e.kind}-${e.validFrom}-${i}`}>
                  <td className="num">{dateShort(e.validFrom)}</td>
                  <td>{e.kind === 'work' ? 'Work' : e.kind === 'awake' ? 'Awake' : e.kind}</td>
                  <td className="num">{summary(e)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
