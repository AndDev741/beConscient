import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import type { Loaded } from './hooks';
import { Close, Info, Refresh, Undo, Warning } from './icons';

export function Card({ title, hint, action, children, className }: {
  title?: ReactNode;
  hint?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={className ? `card ${className}` : 'card'}>
      {(title || action) && (
        <header className="chead">
          {title && <h2 className="ctitle">{title}</h2>}
          {hint && <span className="chint">{hint}</span>}
          {action && <span className="cact">{action}</span>}
        </header>
      )}
      {children}
    </section>
  );
}

/** Placeholder blocks with no digits in them, so loading never reads as zero. */
export function Skeleton({ rows = 4, lead = true }: { rows?: number; lead?: boolean }) {
  return (
    <div className="skelwrap" aria-busy="true" aria-live="polite">
      <span className="sr">Loading…</span>
      {lead && <div className="skel" style={{ height: 56, width: '55%' }} />}
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="skelrow">
          <div className="skel" style={{ height: 14, flex: 1 }} />
          <div className="skel" style={{ height: 14, width: 56 }} />
        </div>
      ))}
    </div>
  );
}

export function ErrorState({ message, onRetry, title = 'Could not load this.' }: {
  message: string;
  onRetry?: () => void;
  title?: string;
}) {
  return (
    <div className="banner crit" role="alert">
      <Warning size={18} className="bicon" />
      <div className="btext">
        <b>{title}</b>
        <p>{message}</p>
      </div>
      {onRetry && (
        <button type="button" className="btn ghost small" onClick={onRetry}>
          <Refresh size={15} /> Try again
        </button>
      )}
    </div>
  );
}

export function Notice({ tone = 'info', title, children, action }: {
  tone?: 'info' | 'warn' | 'crit';
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const Icon = tone === 'info' ? Info : Warning;
  return (
    <div className={`banner ${tone}`} role={tone === 'info' ? 'note' : 'alert'}>
      <Icon size={18} className="bicon" />
      <div className="btext">
        {title && <b>{title}</b>}
        {children && <div className="bbody">{children}</div>}
      </div>
      {action}
    </div>
  );
}

/**
 * The three states every view draws. Loading shows a skeleton, a failure shows a
 * retry, and only real data ever shows numbers.
 */
export function Loadable<T>({ state, skeleton, children }: {
  state: Loaded<T>;
  skeleton?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  if (state.data !== null) {
    return (
      <>
        {state.error && <ErrorState title="Could not refresh." message={state.error} onRetry={state.reload} />}
        {children(state.data)}
      </>
    );
  }
  if (state.error) return <ErrorState message={state.error} onRetry={state.reload} />;
  return <>{skeleton ?? <Skeleton />}</>;
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  );
}

export function Badge({ tone = 'neutral', children }: {
  tone?: 'neutral' | 'ok' | 'warn' | 'crit' | 'accent';
  children: ReactNode;
}) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

export function Progress({ value, label, tone }: { value: number; label: string; tone?: 'warn' | 'crit' }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100);
  return (
    <div className={tone ? `progress ${tone}` : 'progress'} role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
      <i style={{ width: `${pct}%` }} />
    </div>
  );
}

export function Swatch({ color }: { color: string }) {
  return <span className="dot" style={{ background: color }} aria-hidden="true" />;
}

/**
 * A modal confirmation. With `requireText`, the confirm button stays disabled
 * until the user types that exact word: used before anything destructive.
 */
export function ConfirmDialog({
  title, children, confirmLabel, tone = 'normal', requireText, busy, error, onConfirm, onCancel,
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  tone?: 'normal' | 'danger';
  requireText?: string;
  busy?: boolean;
  error?: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const [typed, setTyped] = useState('');
  const titleId = useId();
  const inputId = useId();
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = box.current?.querySelector<HTMLElement>('input, button.primary');
    el?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const ready = !requireText || typed.trim() === requireText;
  return (
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={box}>
        <div className="dhead">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="iconbtn" onClick={onCancel} aria-label="Close">
            <Close size={16} />
          </button>
        </div>
        <div className="dbody">{children}</div>
        {requireText && (
          <div className="field">
            <label htmlFor={inputId}>
              Type <b className="mono">{requireText}</b> to confirm
            </label>
            <input
              id={inputId}
              className="input"
              autoComplete="off"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
            />
          </div>
        )}
        {error && <p className="err" role="alert">{error}</p>}
        <div className="dfoot">
          <button type="button" className="btn ghost" onClick={onCancel} disabled={busy}>Cancel</button>
          <button
            type="button"
            className={tone === 'danger' ? 'btn primary danger' : 'btn primary'}
            onClick={onConfirm}
            disabled={!ready || busy}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/** A short-lived bar after a reversible change, with an undo. */
export function UndoBar({ message, onUndo, onDismiss }: { message: string; onUndo: () => void; onDismiss: () => void }) {
  useEffect(() => {
    const id = setTimeout(onDismiss, 10_000);
    return () => clearTimeout(id);
  }, [onDismiss]);
  return (
    <div className="toast" role="status">
      <span>{message}</span>
      <button type="button" className="btn ghost small" onClick={onUndo}>
        <Undo size={15} /> Undo
      </button>
      <button type="button" className="iconbtn" onClick={onDismiss} aria-label="Dismiss">
        <Close size={14} />
      </button>
    </div>
  );
}

/** Segmented control: one choice among a few, pressed state exposed to assistive tech. */
export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  label: string;
}) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button type="button" key={o.value} aria-pressed={value === o.value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : 'Something went wrong.';
}
