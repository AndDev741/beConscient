import { useState, type FormEvent } from 'react';
import { api, type Corrections, type Device, type Exclusion, type Redaction } from './api';
import { instant } from './format';
import { useLoad } from './hooks';
import { Undo } from './icons';
import { Card, ConfirmDialog, Loadable, Notice, Skeleton, errorText } from './ui';

/** Event types from contract/events.md, with the free-text fields a redaction can clear. */
export const EVENT_TYPES: { type: string; fields: string[] }[] = [
  { type: 'browser.site', fields: ['title', 'page_kind'] },
  { type: 'android.usage', fields: ['class'] },
  { type: 'android.notification_posted', fields: ['category'] },
  { type: 'android.notification_removed', fields: [] },
  { type: 'android.shorts', fields: [] },
  { type: 'android.pause', fields: ['block'] },
  { type: 'android.tz_change', fields: [] },
  { type: 'desktop.activity', fields: [] },
  { type: 'health', fields: ['home_package'] },
];

const fieldsOf = (type: string) => EVENT_TYPES.find((t) => t.type === type)?.fields ?? [];

interface Range {
  deviceId: string;
  type: string;
  from: string;
  to: string;
}

const toMs = (local: string) => (local ? new Date(local).getTime() : NaN);

function rangeProblem(r: Range): string | null {
  if (!r.deviceId) return 'Pick a device.';
  if (!r.from || !r.to) return 'Pick when the period starts and ends.';
  const from = toMs(r.from);
  const to = toMs(r.to);
  if (Number.isNaN(from) || Number.isNaN(to)) return 'Use a valid date and time.';
  if (to <= from) return 'The period must end after it starts.';
  return null;
}

export function CorrectionsView({ onChanged }: { onChanged?: () => void }) {
  const data = useLoad(() => api.corrections(), 'corrections');
  const devices = useLoad(() => api.devices(), 'devices');

  const changed = () => {
    data.reload();
    onChanged?.();
  };

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Corrections</h1>
      </div>
      <p className="help lead">
        For data that should not have come in. An exclusion hides events from the numbers and can be undone. A redaction
        deletes a field, or whole events, for good.
      </p>
      <Loadable state={devices} skeleton={<section className="card"><Skeleton rows={4} lead={false} /></section>}>
        {(devs) => (
          <Loadable state={data} skeleton={<section className="card"><Skeleton rows={4} lead={false} /></section>}>
            {(c) => <CorrectionsBody c={c} devices={devs} onChanged={changed} />}
          </Loadable>
        )}
      </Loadable>
    </>
  );
}

function CorrectionsBody({ c, devices, onChanged }: { c: Corrections; devices: Device[]; onChanged: () => void }) {
  const deviceName = (id: string) => devices.find((d) => d.id === id)?.name ?? 'Unknown device';
  const [undoing, setUndoing] = useState<string | null>(null);
  const [undoError, setUndoError] = useState<string | null>(null);

  async function undo(x: Exclusion) {
    setUndoing(x.id);
    setUndoError(null);
    try {
      await api.removeExclusion(x.id);
      onChanged();
    } catch (e) {
      setUndoError(errorText(e));
    } finally {
      setUndoing(null);
    }
  }

  return (
    <>
      <Card title="Exclusions" hint="Reversible">
        {c.exclusions.length === 0 ? (
          <p className="help">Nothing excluded.</p>
        ) : (
          <ul className="rows">
            {c.exclusions.map((x) => (
              <li key={x.id}>
                <div className="grow">
                  <div>
                    <span className="label">{deviceName(x.deviceId)}</span> · <span className="mono small">{x.type}</span>
                  </div>
                  <div className="muted small">
                    {instant(x.from)} – {instant(x.to)}
                    {x.note && <> · <span className="label">{x.note}</span></>}
                  </div>
                </div>
                <button className="btn ghost small" onClick={() => void undo(x)} disabled={undoing === x.id}>
                  <Undo size={15} /> {undoing === x.id ? 'Undoing…' : 'Undo'}
                </button>
              </li>
            ))}
          </ul>
        )}
        {undoError && <p className="err" role="alert">{undoError}</p>}
        <ExcludeForm devices={devices} onDone={onChanged} />
      </Card>

      <Card title="Redaction" hint="Permanent">
        <RedactForm devices={devices} onDone={onChanged} />
        <h3 className="group">Audit</h3>
        {c.redactions.length === 0 ? (
          <p className="help">No redactions.</p>
        ) : (
          <ul className="rows">
            {c.redactions.map((r) => <RedactionRow key={r.id} r={r} device={deviceName(r.deviceId)} />)}
          </ul>
        )}
      </Card>
    </>
  );
}

function RedactionRow({ r, device }: { r: Redaction; device: string }) {
  return (
    <li>
      <div className="grow">
        <div>
          <span className="label">{device}</span> · <span className="mono small">{r.type}</span> ·{' '}
          {r.field ? <>field <span className="mono small">{r.field}</span> cleared</> : 'events deleted'}
        </div>
        <div className="muted small">
          {instant(r.from)} – {instant(r.to)}
          {r.createdAt !== undefined && <> · done {instant(r.createdAt)}</>}
          {r.affected !== undefined && <> · {r.affected} events</>}
        </div>
      </div>
    </li>
  );
}

function RangeFields({ prefix, devices, value, onChange }: {
  prefix: string;
  devices: Device[];
  value: Range;
  onChange: (r: Range) => void;
}) {
  return (
    <div className="formrow">
      <div className="field">
        <label htmlFor={`${prefix}-dev`}>Device</label>
        <select id={`${prefix}-dev`} className="input" value={value.deviceId} onChange={(e) => onChange({ ...value, deviceId: e.target.value })}>
          <option value="">Pick a device</option>
          {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${prefix}-type`}>Event type</label>
        <select id={`${prefix}-type`} className="input" value={value.type} onChange={(e) => onChange({ ...value, type: e.target.value })}>
          {EVENT_TYPES.map((t) => <option key={t.type} value={t.type}>{t.type}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${prefix}-from`}>From</label>
        <input id={`${prefix}-from`} className="input" type="datetime-local" value={value.from} onChange={(e) => onChange({ ...value, from: e.target.value })} />
      </div>
      <div className="field">
        <label htmlFor={`${prefix}-to`}>To</label>
        <input id={`${prefix}-to`} className="input" type="datetime-local" value={value.to} onChange={(e) => onChange({ ...value, to: e.target.value })} />
      </div>
    </div>
  );
}

const blank = (): Range => ({ deviceId: '', type: EVENT_TYPES[0].type, from: '', to: '' });

function ExcludeForm({ devices, onDone }: { devices: Device[]; onDone: () => void }) {
  const [range, setRange] = useState<Range>(blank);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const problem = rangeProblem(range);
    if (problem) return setError(problem);
    setBusy(true);
    setError(null);
    try {
      await api.exclude({ deviceId: range.deviceId, type: range.type, from: toMs(range.from), to: toMs(range.to), note: note.trim() || null });
      setRange(blank());
      setNote('');
      onDone();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="formgrid subform" onSubmit={submit}>
      <h3 className="group">Exclude events</h3>
      <RangeFields prefix="ex" devices={devices} value={range} onChange={setRange} />
      <div className="field">
        <label htmlFor="ex-note">Note</label>
        <input id="ex-note" className="input" maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
      </div>
      {error && <p className="err" role="alert">{error}</p>}
      <div className="formrow">
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Excluding…' : 'Exclude'}</button>
      </div>
    </form>
  );
}

function RedactForm({ devices, onDone }: { devices: Device[]; onDone: () => void }) {
  const [range, setRange] = useState<Range>(blank);
  const [field, setField] = useState<string>(fieldsOf(EVENT_TYPES[0].type)[0] ?? '');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const fields = fieldsOf(range.type);
  const target = field && fields.includes(field) ? field : '';

  const setType = (r: Range) => {
    setRange(r);
    if (r.type !== range.type) setField(fieldsOf(r.type)[0] ?? '');
  };

  function review(e: FormEvent) {
    e.preventDefault();
    setDone(null);
    const problem = rangeProblem(range);
    if (problem) return setError(problem);
    setError(null);
    setConfirming(true);
  }

  async function redact() {
    setBusy(true);
    setError(null);
    try {
      await api.redact({ deviceId: range.deviceId, type: range.type, from: toMs(range.from), to: toMs(range.to), field: target || null });
      setConfirming(false);
      setDone('Redaction done and recorded in the audit below.');
      setRange(blank());
      onDone();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  const device = devices.find((d) => d.id === range.deviceId)?.name ?? '';

  return (
    <>
    <form className="formgrid" onSubmit={review}>
      <RangeFields prefix="rd" devices={devices} value={range} onChange={setType} />
      <fieldset className="field radios">
        <legend>What to delete</legend>
        {fields.map((f) => (
          <label key={f} className="radio">
            <input type="radio" name="rd-what" checked={target === f} onChange={() => setField(f)} />
            The <span className="mono">{f}</span> field
          </label>
        ))}
        <label className="radio">
          <input type="radio" name="rd-what" checked={target === ''} onChange={() => setField('')} />
          The whole events
        </label>
      </fieldset>
      {error && !confirming && <p className="err" role="alert">{error}</p>}
      {done && <p className="ok" role="status">{done}</p>}
      <div className="formrow">
        <button className="btn ghost danger" type="submit">Review redaction…</button>
      </div>
    </form>
      {confirming && (
        <ConfirmDialog
          title="Redact for good?"
          confirmLabel="Redact"
          tone="danger"
          requireText="REDACT"
          busy={busy}
          error={error}
          onConfirm={() => void redact()}
          onCancel={() => setConfirming(false)}
        >
          <p>
            {target ? (
              <>This clears the <b className="mono">{target}</b> field of every </>
            ) : (
              <>This deletes every </>
            )}
            <b className="mono">{range.type}</b> event from <b>{device}</b> between <b>{instant(toMs(range.from))}</b> and{' '}
            <b>{instant(toMs(range.to))}</b>. It cannot be undone. The audit keeps who, what and when, never the content.
          </p>
          <Notice tone="warn" title="Copies elsewhere">
            Database dumps in the backups and the collectors' local queues may still hold these events. Clean them too.
          </Notice>
        </ConfirmDialog>
      )}
    </>
  );
}
