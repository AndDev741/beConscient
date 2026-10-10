// Background service worker logic.
//
// Manifest V3 stops the worker after 30 seconds without events, so every
// piece of state lives in chrome.storage: the tracker (open intervals,
// focus, active tabs) in storage.session, the unacknowledged intervals and
// the connection status in storage.local. Each event runs as one task on a
// promise chain that loads state, applies the event and saves it back. The
// only values kept in memory are runtime handles: the native port and that
// chain.

import { detectBrowser, zoneAt, type NavigatorLike } from './browser';
import {
  anyPlaying,
  applyPageReport,
  applyTabActivated,
  applyTabGone,
  applyWindowFocus,
  applyWindowRemoved,
  emptyTracker,
  evaluateAll,
  WINDOW_ID_NONE,
  type IntervalChange,
  type TrackerEnv,
  type TrackerState,
} from './intervals';
import {
  applyAck,
  isDue,
  markSent,
  OUTBOX_MAX,
  OUTBOX_PREFIX,
  outboxKey,
  resetForNewPort,
  toSiteMessage,
  upsertEntry,
  type OutboxEntry,
} from './outbox';
import { parseContentMessage } from './sanitize';
import type { AckMessage, ConnectionStatus, HostBoundMessage } from './types';
import { uuidv7 } from './uuidv7';

export const HOST_NAME = 'org.beconscient.agent';
export const HEARTBEAT_ALARM = 'heartbeat';
export const HEARTBEAT_PERIOD_MIN = 5;
export const TRACKER_KEY = 'tracker';
export const STATUS_KEY = 'status';
export const BACKOFF_MIN_MS = 5_000;
export const BACKOFF_MAX_MS = 5 * 60_000;
/** A port that stayed up this long resets the reconnect backoff when it drops. */
const STABLE_PORT_MS = 10_000;

export interface BackgroundOptions {
  now?: () => number;
  navigator?: NavigatorLike;
  newId?: (now: number) => string;
}

export interface Background {
  /** Resolves when every queued event has been handled. For tests. */
  idle(): Promise<void>;
}

export function defaultStatus(): ConnectionStatus {
  return {
    connected: false,
    connectedAt: null,
    disconnectedAt: null,
    lastError: null,
    lastHeartbeatAt: null,
    lastAckAt: null,
    retryAt: 0,
    backoffMs: 0,
  };
}

type Entries = Map<string, OutboxEntry | null>;

interface Connection {
  port: chrome.runtime.Port;
  /** The port was opened by this call and already got a full resend. */
  fresh: boolean;
}

function isAck(m: unknown): m is AckMessage {
  return (
    typeof m === 'object' &&
    m !== null &&
    (m as { t?: unknown }).t === 'ack' &&
    Array.isArray((m as { ids?: unknown }).ids)
  );
}

export function startBackground(api: typeof chrome, opts: BackgroundOptions = {}): Background {
  const now = opts.now ?? (() => Date.now());
  const browser = detectBrowser(opts.navigator ?? (globalThis.navigator as NavigatorLike | undefined));
  const env: TrackerEnv = { newId: opts.newId ?? ((t) => uuidv7(t)), zone: zoneAt };
  const version = api.runtime.getManifest().version;

  let port: chrome.runtime.Port | null = null;
  let tail: Promise<void> = Promise.resolve();

  function run(task: () => Promise<unknown>): void {
    tail = tail.then(task).then(
      () => undefined,
      (err: unknown) => console.warn('beConscient background:', err),
    );
  }

  // ---- storage ----------------------------------------------------------

  async function loadTracker(): Promise<TrackerState> {
    const got = await api.storage.session.get(TRACKER_KEY);
    return (got[TRACKER_KEY] as TrackerState | undefined) ?? emptyTracker();
  }

  async function saveTracker(st: TrackerState): Promise<void> {
    await api.storage.session.set({ [TRACKER_KEY]: st });
  }

  async function loadStatus(): Promise<ConnectionStatus> {
    const got = await api.storage.local.get(STATUS_KEY);
    return { ...defaultStatus(), ...((got[STATUS_KEY] as Partial<ConnectionStatus> | undefined) ?? {}) };
  }

  async function saveStatus(s: ConnectionStatus): Promise<void> {
    await api.storage.local.set({ [STATUS_KEY]: s });
  }

  async function patchStatus(patch: Partial<ConnectionStatus>): Promise<void> {
    await saveStatus({ ...(await loadStatus()), ...patch });
  }

  /** Outbox entries for the given ids, or all of them. */
  async function loadEntries(ids?: string[]): Promise<Map<string, OutboxEntry>> {
    const out = new Map<string, OutboxEntry>();
    if (ids && ids.length === 0) return out;
    const got = await api.storage.local.get(ids ? ids.map(outboxKey) : null);
    for (const [key, value] of Object.entries(got)) {
      if (key.startsWith(OUTBOX_PREFIX) && value) out.set(key.slice(OUTBOX_PREFIX.length), value as OutboxEntry);
    }
    return out;
  }

  async function saveEntries(entries: Entries): Promise<void> {
    const set: Record<string, OutboxEntry> = {};
    const remove: string[] = [];
    for (const [id, e] of entries) {
      if (e) set[outboxKey(id)] = e;
      else remove.push(outboxKey(id));
    }
    if (Object.keys(set).length > 0) await api.storage.local.set(set);
    if (remove.length > 0) await api.storage.local.remove(remove);
  }

  // ---- native port ------------------------------------------------------

  function post(p: chrome.runtime.Port, msg: HostBoundMessage): boolean {
    try {
      p.postMessage(msg);
      return true;
    } catch (err) {
      // Posting on a port the browser already closed throws. When onDisconnect
      // already ran, it has recorded the real error.
      if (port === p) {
        port = null;
        run(() => onPortClosed(p, err instanceof Error ? err.message : String(err)));
      }
      return false;
    }
  }

  async function onPortClosed(p: chrome.runtime.Port | null, error: string | null): Promise<void> {
    if (port !== null && port !== p) return; // a newer port is already up
    const t = now();
    const s = await loadStatus();
    const stable = s.connectedAt !== null && t - s.connectedAt >= STABLE_PORT_MS;
    const backoffMs = stable || s.backoffMs === 0 ? BACKOFF_MIN_MS : Math.min(s.backoffMs * 2, BACKOFF_MAX_MS);
    await saveStatus({
      ...s,
      connected: false,
      disconnectedAt: t,
      lastError: error,
      backoffMs,
      retryAt: t + backoffMs,
    });
  }

  async function onHostMessage(msg: unknown): Promise<void> {
    if (!isAck(msg)) return;
    const ids = msg.ids.filter((id): id is string => typeof id === 'string');
    const entries = await loadEntries([...new Set(ids)]);
    const updates: Entries = new Map();
    for (const id of ids) {
      const e = updates.has(id) ? updates.get(id) : entries.get(id);
      if (!e) continue;
      updates.set(id, applyAck(e));
    }
    await saveEntries(updates);
    await patchStatus({ lastAckAt: now(), lastError: null });
  }

  async function connect(t: number): Promise<Connection | null> {
    let p: chrome.runtime.Port;
    try {
      p = api.runtime.connectNative(HOST_NAME);
    } catch (err) {
      await onPortClosed(null, err instanceof Error ? err.message : String(err));
      return null;
    }
    port = p;
    p.onMessage.addListener((m: unknown) => run(() => onHostMessage(m)));
    p.onDisconnect.addListener(() => {
      // lastError must be read inside the callback.
      const error = api.runtime.lastError?.message ?? null;
      if (port === p) port = null;
      run(() => onPortClosed(p, error));
    });
    await patchStatus({ connected: true, connectedAt: t, lastError: null });

    // A new port starts from scratch: everything unacknowledged goes again.
    const all = await loadEntries();
    const reset: Entries = new Map();
    const changed: Entries = new Map();
    for (const [id, e] of all) {
      const r = resetForNewPort(e);
      reset.set(id, r);
      if (e.sentRev !== 0 || e.inflight.length > 0) changed.set(id, r);
    }
    await saveEntries(changed);

    if (post(p, { t: 'heartbeat', browser, version, at: t })) await patchStatus({ lastHeartbeatAt: t });
    const st = await loadTracker();
    post(p, { t: 'playing', playing: st.playing, at: t });
    await pump(p, t, reset);
    return port === p ? { port: p, fresh: true } : null;
  }

  async function ensurePort(t: number): Promise<Connection | null> {
    if (port) return { port, fresh: false };
    const s = await loadStatus();
    if (t < s.retryAt) return null;
    return connect(t);
  }

  /** Posts the entries that are due and records what was sent. */
  async function pump(p: chrome.runtime.Port, t: number, entries: Map<string, OutboxEntry | null>): Promise<void> {
    const sent: Entries = new Map();
    for (const [id, e] of entries) {
      if (!e || !isDue(e, t)) continue;
      if (!post(p, e.msg)) break;
      sent.set(id, markSent(e, t));
    }
    await saveEntries(sent);
  }

  // ---- events -----------------------------------------------------------

  /** Writes interval revisions into the outbox. Returns the touched entries. */
  async function recordChanges(changes: IntervalChange[]): Promise<Entries> {
    const ids = [...new Set(changes.map((c) => c.interval.id))];
    const existing = await loadEntries(ids);
    const updates: Entries = new Map();
    for (const c of changes) {
      const id = c.interval.id;
      const prev = updates.has(id) ? (updates.get(id) ?? undefined) : existing.get(id);
      // Closing at an end that was already acknowledged needs no new send.
      if (!prev && !c.revised) continue;
      updates.set(id, upsertEntry(prev, toSiteMessage(c.interval, browser), c.closed));
    }
    await saveEntries(updates);
    return updates;
  }

  /** Applies one tracker event, buffers its intervals and sends what is due. */
  async function step(t: number, mutate: (st: TrackerState) => IntervalChange[]): Promise<Connection | null> {
    const st = await loadTracker();
    const changes = mutate(st);
    const playing = anyPlaying(st, t);
    const playingChanged = playing !== st.playing;
    st.playing = playing;
    await saveTracker(st);
    const touched = await recordChanges(changes);

    const conn = await ensurePort(t);
    if (conn && !conn.fresh) {
      if (playingChanged) post(conn.port, { t: 'playing', playing, at: t });
      await pump(conn.port, t, touched);
    }
    return conn;
  }

  async function onContentMessage(msg: unknown, sender: chrome.runtime.MessageSender, t: number): Promise<void> {
    if (sender.id !== api.runtime.id) return;
    const tab = sender.tab;
    if (!tab || tab.id === undefined || tab.id < 0) return;
    if (sender.frameId !== undefined && sender.frameId !== 0) return;
    const parsed = parseContentMessage(msg, sender.origin);
    if (!parsed) return;
    const tabId = tab.id;
    if (parsed.kind === 'gone') {
      await step(t, (st) => applyTabGone(st, tabId, t, env));
      return;
    }
    const report = {
      tabId,
      windowId: tab.windowId,
      active: tab.active,
      sentAt: Math.min(parsed.sentAt, t),
      page: parsed.state,
    };
    await step(t, (st) => applyPageReport(st, report, t, env));
  }

  async function onHeartbeatAlarm(t: number): Promise<void> {
    const conn = await step(t, (st) => evaluateAll(st, t, env));
    const all = await loadEntries();
    if (conn && !conn.fresh) {
      if (post(conn.port, { t: 'heartbeat', browser, version, at: t })) await patchStatus({ lastHeartbeatAt: t });
      await pump(conn.port, t, all);
    }
    await pruneOutbox(all);
  }

  /** Keeps the outbox bounded while the agent stays unreachable. */
  async function pruneOutbox(all: Map<string, OutboxEntry>): Promise<void> {
    if (all.size <= OUTBOX_MAX) return;
    const oldest = [...all.entries()]
      .filter(([, e]) => e.closed)
      .sort((a, b) => a[1].msg.start - b[1].msg.start)
      .slice(0, all.size - OUTBOX_MAX);
    await saveEntries(new Map(oldest.map(([id]) => [id, null])));
  }

  async function init(t: number): Promise<void> {
    const alarm = await api.alarms.get(HEARTBEAT_ALARM);
    if (!alarm) {
      await api.alarms.create(HEARTBEAT_ALARM, {
        periodInMinutes: HEARTBEAT_PERIOD_MIN,
        delayInMinutes: HEARTBEAT_PERIOD_MIN,
      });
    }
    // A worker that just started has no port, whatever the stored status says.
    const s = await loadStatus();
    if (s.connected) await saveStatus({ ...s, connected: false, disconnectedAt: t });

    const st = await loadTracker();
    if (st.focusedWindowId === null) {
      try {
        const w = await api.windows.getLastFocused();
        st.focusedWindowId = w.focused && w.id !== undefined ? w.id : WINDOW_ID_NONE;
        await saveTracker(st);
      } catch {
        // Unknown until the next onFocusChanged.
      }
    }
    await step(t, (state) => evaluateAll(state, t, env));
  }

  // Listeners are registered synchronously so that events wake the worker.
  api.runtime.onMessage.addListener((msg: unknown, sender: chrome.runtime.MessageSender) => {
    const t = now();
    run(() => onContentMessage(msg, sender, t));
    return false;
  });
  api.tabs.onActivated.addListener((info: chrome.tabs.OnActivatedInfo) => {
    const t = now();
    run(() => step(t, (st) => applyTabActivated(st, info.tabId, info.windowId, t, env)));
  });
  api.tabs.onRemoved.addListener((tabId: number) => {
    const t = now();
    run(() => step(t, (st) => applyTabGone(st, tabId, t, env)));
  });
  api.windows.onFocusChanged.addListener((windowId: number) => {
    const t = now();
    run(() => step(t, (st) => applyWindowFocus(st, windowId, t, env)));
  });
  api.windows.onRemoved.addListener((windowId: number) => {
    const t = now();
    run(() => step(t, (st) => applyWindowRemoved(st, windowId, t, env)));
  });
  api.alarms.onAlarm.addListener((alarm: chrome.alarms.Alarm) => {
    if (alarm.name !== HEARTBEAT_ALARM) return;
    const t = now();
    run(() => onHeartbeatAlarm(t));
  });

  const startedAt = now();
  run(() => init(startedAt));

  return {
    async idle() {
      let seen: Promise<void>;
      do {
        seen = tail;
        await seen;
      } while (seen !== tail);
    },
  };
}
