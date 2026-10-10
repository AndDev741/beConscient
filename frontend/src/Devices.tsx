import { useEffect, useState, type FormEvent } from 'react';
import { api, isApiError, type Device, type DeviceKind, type Installation, type Instant, type PairingCode } from './api';
import { ago, instant, toMillis } from './format';
import { useInterval, useLoad } from './hooks';
import { Check, Copy, Desktop, Phone } from './icons';
import { Badge, Card, ConfirmDialog, Loadable, Notice, Skeleton, errorText } from './ui';

interface Pairing {
  deviceId: string;
  deviceName: string;
  code: string;
  expiresAt: Instant;
  /** Installations that existed when the code was made, so a new one stands out. */
  known: string[];
}

const KIND_LABEL: Record<DeviceKind, string> = { android: 'Android phone', desktop: 'Linux computer' };

export function Devices({ onChanged, pollMs = 3000 }: { onChanged?: () => void; pollMs?: number }) {
  const devices = useLoad(() => api.devices(), 'devices');
  const lock = useLoad(() => api.pairingState(), 'pairing-state');
  // The code lives only here, in memory: leaving the view or reloading the page drops it for good
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [repair, setRepair] = useState<Device | null>(null);
  const [revoke, setRevoke] = useState<{ device: Device; installation: Installation } | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const refresh = () => {
    devices.reload();
    lock.reload();
    onChanged?.();
  };

  const startPairing = (device: Device, code: PairingCode) => {
    setPairing({
      deviceId: device.id,
      deviceName: device.name,
      code: code.code,
      expiresAt: code.expiresAt,
      known: device.installations.map((i) => i.id),
    });
  };

  async function doRepair() {
    if (!repair) return;
    setBusy(true);
    setDialogError(null);
    try {
      const code = await api.pairingCode(repair.id);
      startPairing(repair, code);
      setRepair(null);
      lock.reload();
    } catch (e) {
      setDialogError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function doRevoke() {
    if (!revoke) return;
    setBusy(true);
    setDialogError(null);
    try {
      await api.revokeInstallation(revoke.installation.id);
      setRevoke(null);
      refresh();
    } catch (e) {
      setDialogError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Devices</h1>
      </div>

      {lock.data?.lockedOut && (
        <Notice tone="crit" title="Pairing is locked">
          {lock.data.failures} pairing attempts in a row failed, so every open code was invalidated. Create a new
          code when you are ready to pair.
        </Notice>
      )}
      {lock.data && !lock.data.lockedOut && lock.data.failures > 0 && (
        <Notice tone="warn" title={`${lock.data.failures} failed pairing ${lock.data.failures === 1 ? 'attempt' : 'attempts'}`}>
          Ten failures in a row invalidate every open code.
        </Notice>
      )}

      {pairing ? (
        <PairingPanel pairing={pairing} pollMs={pollMs} onDone={() => { setPairing(null); refresh(); }} />
      ) : (
        <AddDevice
          onCreated={(device, code) => {
            startPairing({ ...device, installations: [] }, code);
            refresh();
          }}
        />
      )}

      <Card title="Paired devices">
        <Loadable state={devices} skeleton={<Skeleton rows={3} lead={false} />}>
          {(list) =>
            list.length === 0 ? (
              <p className="help">No devices yet. Add your phone and your computer above.</p>
            ) : (
              <ul className="devices">
                {list.map((d) => (
                  <DeviceRow
                    key={d.id}
                    device={d}
                    onRepair={() => { setDialogError(null); setRepair(d); }}
                    onRevoke={(installation) => { setDialogError(null); setRevoke({ device: d, installation }); }}
                  />
                ))}
              </ul>
            )
          }
        </Loadable>
      </Card>

      {repair && (
        <ConfirmDialog
          title={`Re-pair ${repair.name}?`}
          confirmLabel="Create a pairing code"
          busy={busy}
          error={dialogError}
          onConfirm={() => void doRepair()}
          onCancel={() => setRepair(null)}
        >
          <p>
            Use this after reinstalling the collector. When the new installation pairs, the older installations of
            this device are revoked. Events they already sent stay.
          </p>
        </ConfirmDialog>
      )}
      {revoke && (
        <ConfirmDialog
          title="Revoke this installation?"
          confirmLabel="Revoke"
          tone="danger"
          busy={busy}
          error={dialogError}
          onConfirm={() => void doRevoke()}
          onCancel={() => setRevoke(null)}
        >
          <p>
            The collector on <b>{revoke.device.name}</b> with fingerprint{' '}
            <b className="mono">{revoke.installation.fingerprint}</b> will no longer be able to send events. Events it
            already sent stay. This cannot be undone; pair again to resume.
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}

function AddDevice({ onCreated }: { onCreated: (device: Device, code: PairingCode) => void }) {
  const [name, setName] = useState('');
  const [kind, setKind] = useState<DeviceKind>('android');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [secondPhone, setSecondPhone] = useState(false);

  async function create(confirmSecondPhone: boolean) {
    setBusy(true);
    setError(null);
    try {
      const res = await api.createDevice(name.trim(), kind, confirmSecondPhone);
      setSecondPhone(false);
      setName('');
      onCreated(res.device, { code: res.code, expiresAt: res.expiresAt });
    } catch (e) {
      if (isApiError(e, 'second_phone') && !confirmSecondPhone) setSecondPhone(true);
      else {
        setSecondPhone(false);
        setError(errorText(e));
      }
    } finally {
      setBusy(false);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return setError('Give the device a name, like "Phone" or "Desk".');
    void create(false);
  }

  return (
    <Card title="Add a device">
      <form className="formgrid" onSubmit={submit}>
        <div className="field">
          <label htmlFor="dev-name">Name</label>
          <input id="dev-name" className="input" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <fieldset className="field radios">
          <legend>Kind</legend>
          {(['android', 'desktop'] as DeviceKind[]).map((k) => (
            <label key={k} className="radio">
              <input type="radio" name="dev-kind" value={k} checked={kind === k} onChange={() => setKind(k)} />
              {k === 'android' ? <Phone size={16} /> : <Desktop size={16} />}
              {KIND_LABEL[k]}
            </label>
          ))}
        </fieldset>
        {error && <p className="err" role="alert">{error}</p>}
        <div className="formrow">
          <button className="btn primary" type="submit" disabled={busy}>
            {busy ? 'Creating…' : 'Create a pairing code'}
          </button>
        </div>
      </form>
      {secondPhone && (
        <ConfirmDialog
          title="Pair a second phone?"
          confirmLabel="Pair a second phone"
          busy={busy}
          onConfirm={() => void create(true)}
          onCancel={() => setSecondPhone(false)}
        >
          <p>
            Another Android phone is already active. Both phones will count toward your screen time. If this phone
            replaces the old one, cancel and use <b>Re-pair</b> on the existing device instead.
          </p>
        </ConfirmDialog>
      )}
    </Card>
  );
}

function useNow(enabled: boolean, ms = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useInterval(() => setNow(Date.now()), ms, enabled);
  return now;
}

function PairingPanel({ pairing, pollMs, onDone }: { pairing: Pairing; pollMs: number; onDone: () => void }) {
  const expires = toMillis(pairing.expiresAt);
  const [paired, setPaired] = useState<Installation | null>(null);
  const [copied, setCopied] = useState(false);
  const now = useNow(!paired);
  const left = expires === null ? null : Math.max(0, expires - now);
  const expired = left === 0;

  // Watch for the collector to show up, so the fingerprint can be compared on both sides
  useInterval(
    () => {
      api
        .devices()
        .then((list) => {
          const device = list.find((d) => d.id === pairing.deviceId);
          const fresh = device?.installations.find((i) => !pairing.known.includes(i.id) && !i.revokedAt);
          if (fresh) setPaired(fresh);
        })
        .catch(() => undefined);
    },
    pollMs,
    !paired && !expired,
  );

  useEffect(() => {
    if (!copied) return;
    const id = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(id);
  }, [copied]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(pairing.code);
      setCopied(true);
    } catch {
      // No clipboard (plain http, old browser): the code is selectable as text
    }
  }

  const mmss = left === null ? null : `${Math.floor(left / 60000)}:${String(Math.floor((left % 60000) / 1000)).padStart(2, '0')}`;

  return (
    <section className="card pairing" aria-labelledby="pairing-title">
      <h2 className="ctitle" id="pairing-title">Pair {pairing.deviceName}</h2>
      {paired ? (
        <>
          <Notice tone="info" title="Paired">
            The collector paired. Check that it shows the same fingerprint:
          </Notice>
          <div className="fingerprint mono" data-testid="new-fingerprint">{paired.fingerprint}</div>
          <p className="help">If the fingerprints differ, revoke this installation and pair again.</p>
        </>
      ) : (
        <>
          <p className="help">Type this code in the collector. It works once and is shown only now.</p>
          <div className="codebox">
            <output className="code mono" aria-label="Pairing code" data-testid="pairing-code">{pairing.code}</output>
            <button className="btn ghost small" onClick={() => void copy()} disabled={expired}>
              {copied ? <><Check size={15} /> Copied</> : <><Copy size={15} /> Copy</>}
            </button>
          </div>
          <p className={expired ? 'err' : 'help'} aria-live="polite">
            {expired
              ? 'This code expired. Close and use Re-pair on the device to get a new one.'
              : mmss
                ? `Expires in ${mmss} (at ${instant(pairing.expiresAt)}).`
                : `Expires at ${instant(pairing.expiresAt)}.`}
          </p>
          {!expired && <p className="help waiting">Waiting for the collector…</p>}
        </>
      )}
      <div className="formrow">
        <button className="btn primary" onClick={onDone}>Done</button>
      </div>
    </section>
  );
}

function DeviceRow({ device, onRepair, onRevoke }: {
  device: Device;
  onRepair: () => void;
  onRevoke: (i: Installation) => void;
}) {
  const active = device.installations.filter((i) => !i.revokedAt);
  const revoked = device.installations.filter((i) => i.revokedAt);
  return (
    <li className="device">
      <div className="devhead">
        {device.kind === 'android' ? <Phone /> : <Desktop />}
        <div className="grow">
          <b className="label">{device.name}</b>
          <div className="muted small">{KIND_LABEL[device.kind] ?? device.kind}</div>
        </div>
        <button className="btn ghost small" onClick={onRepair}>Re-pair</button>
      </div>
      {device.installations.length === 0 ? (
        <p className="help">Not paired yet.</p>
      ) : (
        <ul className="installs">
          {[...active, ...revoked].map((i) => (
            <li key={i.id} className={i.revokedAt ? 'revoked' : undefined}>
              <div className="grow">
                <div>
                  <span className="muted small">Fingerprint </span>
                  <span className="mono">{i.fingerprint}</span>
                </div>
                <div className="muted small">
                  Paired {instant(i.createdAt)} · last seen {ago(i.lastSeenAt)}
                  {i.revokedAt && <> · revoked {instant(i.revokedAt)}</>}
                </div>
              </div>
              {i.cloneSuspected && <Badge tone="crit">Possible clone</Badge>}
              {i.revokedAt ? (
                <Badge>Revoked</Badge>
              ) : (
                <>
                  <Badge tone="ok">Active</Badge>
                  <button className="btn ghost small danger" onClick={() => onRevoke(i)}>Revoke</button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}
