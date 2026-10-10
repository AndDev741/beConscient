// Shared content script loop. It reads the page state, sends it to the
// background when it changes, and repeats it as a heartbeat every few
// seconds while the page is visible or a video plays. The page state never
// includes a URL.

import type { ContentMessage, PageState } from '../types';

export const HEARTBEAT_MS = 5_000;
/** Cheap local re-check of title, path and playback between heartbeats. */
export const CHECK_MS = 1_000;

const MEDIA_EVENTS = ['play', 'playing', 'pause', 'ended', 'emptied'] as const;

export interface ReporterDeps {
  doc: Document;
  win: Window;
  /** Current page state, or null while it is settling after a navigation. */
  read: () => PageState | null;
  /** Delivers a message; returns false once the extension context is gone. */
  send: (msg: ContentMessage) => boolean;
  now?: () => number;
  heartbeatMs?: number;
  checkMs?: number;
  /** Site-specific watchers that call `check` on change. Returns a cleanup. */
  watch?: (check: () => void) => () => void;
}

export interface Reporter {
  check(force?: boolean): void;
  stop(): void;
}

export function samePage(a: PageState, b: PageState): boolean {
  return (
    a.site === b.site &&
    a.visible === b.visible &&
    a.focused === b.focused &&
    a.playing === b.playing &&
    a.title === b.title &&
    a.pageKind === b.pageKind
  );
}

export function startReporter(d: ReporterDeps): Reporter {
  const now = d.now ?? (() => Date.now());
  const heartbeatMs = d.heartbeatMs ?? HEARTBEAT_MS;
  const checkMs = d.checkMs ?? CHECK_MS;
  let last: PageState | null = null;
  let lastSentAt = -Infinity;
  let timer: ReturnType<typeof setInterval> | null = null;
  let stopped = false;

  function emit(msg: ContentMessage): void {
    if (!d.send(msg)) stop();
  }

  function startTimer(): void {
    if (timer === null && !stopped) timer = setInterval(() => check(), checkMs);
  }

  function stopTimer(): void {
    if (timer !== null) clearInterval(timer);
    timer = null;
  }

  function check(force = false): void {
    if (stopped) return;
    const state = d.read();
    if (state === null) {
      startTimer(); // settling: look again shortly
      return;
    }
    const live = state.visible || state.playing;
    const t = now();
    const changed = last === null || !samePage(last, state);
    if (changed || force || (live && t - lastSentAt >= heartbeatMs)) {
      last = state;
      lastSentAt = t;
      emit({ kind: 'page', sentAt: t, state });
    }
    if (live) startTimer();
    else stopTimer(); // the final hidden state went out above
  }

  const onChange = (): void => check();
  const onShow = (): void => check(true);
  const onHide = (): void => {
    stopTimer();
    last = null;
    emit({ kind: 'gone', sentAt: now() });
  };

  d.doc.addEventListener('visibilitychange', onChange);
  d.win.addEventListener('focus', onChange);
  d.win.addEventListener('blur', onChange);
  d.win.addEventListener('popstate', onChange);
  d.win.addEventListener('pageshow', onShow);
  d.win.addEventListener('pagehide', onHide);
  // Media events do not bubble; a capturing listener sees every <video>.
  for (const type of MEDIA_EVENTS) d.doc.addEventListener(type, onChange, true);
  const nav = (d.win as Window & { navigation?: EventTarget }).navigation;
  nav?.addEventListener('navigatesuccess', onChange);
  const unwatch = d.watch?.(onChange);

  function stop(): void {
    if (stopped) return;
    stopped = true;
    stopTimer();
    d.doc.removeEventListener('visibilitychange', onChange);
    d.win.removeEventListener('focus', onChange);
    d.win.removeEventListener('blur', onChange);
    d.win.removeEventListener('popstate', onChange);
    d.win.removeEventListener('pageshow', onShow);
    d.win.removeEventListener('pagehide', onHide);
    for (const type of MEDIA_EVENTS) d.doc.removeEventListener(type, onChange, true);
    nav?.removeEventListener('navigatesuccess', onChange);
    unwatch?.();
  }

  check(true);
  return { check, stop };
}
