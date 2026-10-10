import { useCallback, useEffect, useRef, useState } from 'react';
import { api, isApiError, setUnauthorizedHandler, type Session } from './api';
import { todayIso } from './format';
import { useTheme } from './hooks';
import { ChevronDown, Moon, SignOut, Sun } from './icons';
import { Login } from './Login';
import { Day } from './Day';
import { Trends } from './Trends';
import { Categories } from './Categories';
import { Devices } from './Devices';
import { SettingsView } from './Settings';
import { BackstageView } from './Backstage';
import { Validation } from './Validation';
import { CorrectionsView } from './Corrections';
import { ErrorState, errorText } from './ui';

export type View =
  | 'today' | 'trends' | 'categories'
  | 'devices' | 'settings' | 'backstage' | 'validation' | 'corrections';

const PRIMARY: { id: View; label: string }[] = [
  { id: 'today', label: 'Today' },
  { id: 'trends', label: 'Trends' },
  { id: 'categories', label: 'Categories' },
];

const MORE: { id: View; label: string; hint: string }[] = [
  { id: 'devices', label: 'Devices', hint: 'Pair, re-pair and revoke collectors' },
  { id: 'settings', label: 'Settings', hint: 'Work and awake windows, days off' },
  { id: 'backstage', label: 'Backstage', hint: 'Sensor health and data quality' },
  { id: 'validation', label: 'Validation', hint: 'Compare with Android Settings' },
  { id: 'corrections', label: 'Corrections', hint: 'Redact or exclude events' },
];

const isMore = (v: View) => MORE.some((m) => m.id === v);

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [boot, setBoot] = useState<'loading' | 'ready' | { error: string }>('loading');
  const [view, setView] = useState<View>('today');
  const [date, setDate] = useState(todayIso);
  const [trace, setTrace] = useState<string | null>(null);
  const [more, setMore] = useState<'top' | 'bottom' | null>(null);
  // Bumped after any write so every view that depends on it refetches
  const [revision, setRevision] = useState(0);
  const changed = useCallback(() => setRevision((r) => r + 1), []);
  const { isDark, toggle } = useTheme();

  const check = useCallback(() => {
    setBoot('loading');
    api
      .session()
      .then((s) => {
        setSession(s);
        setBoot('ready');
      })
      .catch((e: unknown) => {
        setSession(null);
        // A 401 means "sign in"; anything else is a failure worth saying out loud
        setBoot(isApiError(e) && e.status === 401 ? 'ready' : { error: errorText(e) });
      });
  }, []);

  useEffect(check, [check]);
  useEffect(() => setUnauthorizedHandler(() => setSession(null)), []);

  const go = (v: View) => {
    setView(v);
    setMore(null);
    if (v !== 'backstage') setTrace(null);
  };

  const signedIn = () => {
    setView('today');
    setDate(todayIso());
    check();
  };

  if (boot === 'loading') return <div className="loginwrap" aria-busy="true" />;
  if (typeof boot === 'object') {
    return (
      <div className="loginwrap">
        <div className="loginbox">
          <ErrorState title="The server did not answer." message={boot.error} onRetry={check} />
        </div>
      </div>
    );
  }
  if (!session) return <Login onSignedIn={signedIn} />;

  const openTrace = (segmentId: string) => {
    setTrace(segmentId);
    setView('backstage');
  };

  return (
    <div className="app">
      <header className="appbar">
        <span className="logo">
          <span className="mark" aria-hidden="true" />
          beConscient
        </span>

        <nav className="nav" aria-label="Main">
          {PRIMARY.map((t) => (
            <button key={t.id} onClick={() => go(t.id)} aria-current={view === t.id ? 'page' : undefined}>
              {t.label}
            </button>
          ))}
          <MoreMenu
            open={more === 'top'}
            onToggle={() => setMore(more === 'top' ? null : 'top')}
            onClose={() => setMore(null)}
            onPick={go}
            current={view}
            variant="top"
          />
        </nav>

        <span className="grow" />

        <button className="iconbtn" onClick={toggle} aria-label={isDark ? 'Use light theme' : 'Use dark theme'}>
          {isDark ? <Sun size={18} /> : <Moon size={18} />}
        </button>
        <button
          className="iconbtn"
          onClick={() => api.logout().then(() => setSession(null))}
          aria-label="Sign out"
          title={`Signed in as ${session.username}`}
        >
          <SignOut size={18} />
        </button>
      </header>

      <main className="content">
        {view === 'today' && (
          <Day date={date} onDate={setDate} revision={revision} onTrace={openTrace} />
        )}
        {view === 'trends' && <Trends revision={revision} />}
        {view === 'categories' && <Categories onChanged={changed} />}
        {view === 'devices' && <Devices onChanged={changed} />}
        {view === 'settings' && <SettingsView onChanged={changed} />}
        {view === 'backstage' && <BackstageView traceSegmentId={trace} onTrace={setTrace} />}
        {view === 'validation' && <Validation revision={revision} />}
        {view === 'corrections' && <CorrectionsView onChanged={changed} />}
      </main>

      <nav className="tabbar" aria-label="Main">
        {PRIMARY.map((t) => (
          <button key={t.id} onClick={() => go(t.id)} aria-current={view === t.id ? 'page' : undefined}>
            {t.label}
          </button>
        ))}
        <MoreMenu
          open={more === 'bottom'}
          onToggle={() => setMore(more === 'bottom' ? null : 'bottom')}
          onClose={() => setMore(null)}
          onPick={go}
          current={view}
          variant="bottom"
        />
      </nav>
    </div>
  );
}

function MoreMenu({ open, onToggle, onClose, onPick, current, variant }: {
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onPick: (v: View) => void;
  current: View;
  variant: 'top' | 'bottom';
}) {
  const wrap = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, onClose]);

  const active = MORE.find((m) => m.id === current);
  return (
    <div className={`more ${variant}`} ref={wrap}>
      <button
        onClick={onToggle}
        aria-expanded={open}
        aria-haspopup="true"
        aria-current={isMore(current) ? 'page' : undefined}
      >
        {active ? active.label : 'More'}
        {variant === 'top' ? <ChevronDown size={14} /> : null}
      </button>
      {open && (
        <div className="menu" role="menu" aria-label="More">
          {MORE.map((m) => (
            <button
              key={m.id}
              role="menuitem"
              onClick={() => onPick(m.id)}
              aria-current={current === m.id ? 'page' : undefined}
            >
              <span className="mlabel">{m.label}</span>
              <span className="mhint">{m.hint}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
