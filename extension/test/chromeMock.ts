// In-memory stand-in for the chrome.* APIs the extension uses. Storage
// survives restartWorker(), which drops every listener and port the way
// the browser does when it stops a service worker.

/* eslint-disable @typescript-eslint/no-explicit-any */

export const EXTENSION_ID = 'hhddmdmnpejagnnmenjdgdifpimneeok';
export const YOUTUBE_ORIGIN = 'https://www.youtube.com';
export const LINKEDIN_ORIGIN = 'https://www.linkedin.com';

type Listener = (...args: any[]) => unknown;

export class MockEvent {
  listeners: Listener[] = [];
  addListener(l: Listener): void {
    this.listeners.push(l);
  }
  removeListener(l: Listener): void {
    this.listeners = this.listeners.filter((x) => x !== l);
  }
  hasListener(l: Listener): boolean {
    return this.listeners.includes(l);
  }
  hasListeners(): boolean {
    return this.listeners.length > 0;
  }
  emit(...args: unknown[]): void {
    for (const l of [...this.listeners]) l(...args);
  }
  clear(): void {
    this.listeners = [];
  }
}

export class MockPort {
  readonly onMessage = new MockEvent();
  readonly onDisconnect = new MockEvent();
  /** Messages the host received. */
  readonly delivered: any[] = [];
  closed = false;

  constructor(
    readonly name: string,
    private readonly runtime: { lastError: { message: string } | undefined },
    readonly hostUp: boolean,
  ) {}

  postMessage(msg: unknown): void {
    if (this.closed) throw new Error('Attempting to use a disconnected port object');
    // Messages to a host that failed to start are lost.
    if (this.hostUp) this.delivered.push(structuredClone(msg));
  }

  disconnect(): void {
    this.closed = true;
  }

  /** The host sends a message to the extension. */
  hostSend(msg: unknown): void {
    if (this.closed) throw new Error('port closed');
    this.onMessage.emit(structuredClone(msg), this);
  }

  /** The host side goes away; the extension sees onDisconnect. */
  hostClose(error?: string): void {
    if (this.closed) return;
    this.closed = true;
    this.runtime.lastError = error ? { message: error } : undefined;
    this.onDisconnect.emit(this);
    this.runtime.lastError = undefined;
  }
}

function storageArea() {
  const data = new Map<string, unknown>();
  return {
    data,
    async get(keys?: string | string[] | null): Promise<Record<string, any>> {
      const out: Record<string, any> = {};
      const wanted = keys === null || keys === undefined ? [...data.keys()] : typeof keys === 'string' ? [keys] : keys;
      for (const k of wanted) if (data.has(k)) out[k] = structuredClone(data.get(k));
      return out;
    },
    async set(items: Record<string, unknown>): Promise<void> {
      for (const [k, v] of Object.entries(items)) data.set(k, structuredClone(v));
    },
    async remove(keys: string | string[]): Promise<void> {
      for (const k of typeof keys === 'string' ? [keys] : keys) data.delete(k);
    },
    async clear(): Promise<void> {
      data.clear();
    },
  };
}

export interface MockOptions {
  hostAvailable?: boolean;
  /** What windows.getLastFocused() returns when a worker starts. */
  lastFocused?: { id: number; focused: boolean };
}

export interface TabInfo {
  id: number;
  windowId: number;
  active: boolean;
}

export function createChromeMock(opts: MockOptions = {}) {
  const events: MockEvent[] = [];
  const ev = (): MockEvent => {
    const e = new MockEvent();
    events.push(e);
    return e;
  };
  const state = {
    hostAvailable: opts.hostAvailable ?? true,
    ports: [] as MockPort[],
    alarms: new Map<string, { name: string; periodInMinutes?: number; delayInMinutes?: number }>(),
    alarmCreates: [] as Array<{ name: string; periodInMinutes?: number; delayInMinutes?: number }>,
    lastFocused: opts.lastFocused ?? { id: 1, focused: false },
  };

  const runtime = {
    id: EXTENSION_ID,
    lastError: undefined as { message: string } | undefined,
    getManifest: () => ({ manifest_version: 3, name: 'beConscient', version: '0.1.0' }),
    onMessage: ev(),
    onInstalled: ev(),
    onStartup: ev(),
    connectNative(name: string): MockPort {
      const port = new MockPort(name, runtime, state.hostAvailable);
      state.ports.push(port);
      if (!state.hostAvailable) {
        queueMicrotask(() => port.hostClose('Specified native messaging host not found.'));
      }
      return port;
    },
  };

  const local = storageArea();
  const session = storageArea();

  const api = {
    runtime,
    storage: { local, session, onChanged: ev() },
    tabs: { onActivated: ev(), onRemoved: ev() },
    windows: {
      WINDOW_ID_NONE: -1,
      onFocusChanged: ev(),
      onRemoved: ev(),
      getLastFocused: async () => ({ ...state.lastFocused }),
    },
    alarms: {
      get: async (name: string) => state.alarms.get(name),
      create: async (name: string, info: { periodInMinutes?: number; delayInMinutes?: number }) => {
        const alarm = { name, ...info };
        state.alarms.set(name, alarm);
        state.alarmCreates.push(alarm);
      },
      onAlarm: ev(),
    },
  };

  return {
    chrome: api as unknown as typeof chrome,
    api,
    state,
    local,
    session,

    /** The newest port that is still open. */
    openPort(): MockPort | undefined {
      return [...state.ports].reverse().find((p) => !p.closed);
    },

    /** Every message the host received, across ports, in order. */
    delivered(): any[] {
      return state.ports.flatMap((p) => p.delivered);
    },

    siteMessages(): any[] {
      return this.delivered().filter((m) => m.t === 'site');
    },

    outbox(): any[] {
      return [...local.data.entries()].filter(([k]) => k.startsWith('ob:')).map(([, v]) => structuredClone(v));
    },

    tracker(): any {
      return structuredClone(session.data.get('tracker'));
    },

    sendFromTab(tab: TabInfo, msg: unknown, origin: string = YOUTUBE_ORIGIN): void {
      const sender = {
        id: EXTENSION_ID,
        frameId: 0,
        origin,
        tab: { id: tab.id, windowId: tab.windowId, active: tab.active, index: 0, pinned: false, highlighted: tab.active, incognito: false },
      };
      runtime.onMessage.emit(structuredClone(msg), sender, () => undefined);
    },

    focusWindow(windowId: number): void {
      api.windows.onFocusChanged.emit(windowId);
    },

    activateTab(tabId: number, windowId: number): void {
      api.tabs.onActivated.emit({ tabId, windowId });
    },

    removeTab(tabId: number, windowId: number): void {
      api.tabs.onRemoved.emit(tabId, { windowId, isWindowClosing: false });
    },

    fireAlarm(name: string): void {
      api.alarms.onAlarm.emit({ name, scheduledTime: 0 });
    },

    /** The browser stops the service worker: listeners and ports are gone, storage stays. */
    restartWorker(): void {
      for (const e of events) e.clear();
      for (const p of state.ports) {
        p.closed = true;
        p.onMessage.clear();
        p.onDisconnect.clear();
      }
    },
  };
}

export type ChromeMock = ReturnType<typeof createChromeMock>;
