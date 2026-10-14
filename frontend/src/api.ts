/**
 * Everything the page knows about the backend, typed after contract/api.md.
 * One method per endpoint. Field names follow the contract exactly.
 */

/** Epoch milliseconds. Some timestamps in the contract have no declared format, so the page also accepts ISO strings. */
export type Instant = number | string;

// ---------------------------------------------------------------- session

export interface Session {
  username: string;
}

// ---------------------------------------------------------------- day

export type DayState = 'open' | 'arriving' | 'closed';

export interface Headline {
  awakeMinutes: number;
  awayMinutes: number;
  screenMinutes: number;
  outsideAwakeScreenMinutes: number;
  /** True for today: only awake hours that already passed count. */
  elapsedOnly: boolean;
  incomplete: boolean;
  /** Sensor names with device names, e.g. "activity (Desktop)". */
  silentSensors: string[];
}

export type DeviceKind = 'android' | 'desktop';

export interface DeviceTotal {
  deviceId: string;
  name: string;
  kind: DeviceKind;
  minutes: number;
}

export interface CategoryTotal {
  categoryId: string;
  name: string;
  color: string;
  minutes: number;
}

/**
 * Not in api.md yet: the segments behind an hour, so the trace view can be
 * opened from the chart. The page works without it.
 */
export interface SegmentRef {
  id: string;
  label: string;
  minutes: number;
}

export interface Hour {
  label: string;
  start: number;
  byCategory: { categoryId: string; minutes: number }[];
  segments?: SegmentRef[];
}

export interface SubjectTotal {
  subjectKey: string;
  label: string;
  categoryId: string | null;
  minutes: number;
}

export interface Unlocks {
  count: number;
  firstApps: { subjectKey: string; label: string; count: number }[];
}

export interface NotificationCount {
  subjectKey: string;
  label: string;
  posted: number;
  tapped: number;
  dismissed: number;
}

/** A stretch of computer time marked by hand: the computer time inside it takes the category. */
export interface ComputerLabel {
  id: string;
  categoryId: string;
  from: number;
  to: number;
  /** Computer time the stretch took on this day. */
  minutes: number;
}

export interface Derivation {
  rebuilding: boolean;
  /** 0 to 1. */
  progress: number;
}

export interface DayView {
  date: string;
  zone: string;
  state: DayState;
  derivation: Derivation;
  headline: Headline;
  totals: { screenMinutes: number; overlapMinutes: number; byDevice: DeviceTotal[] };
  byCategory: CategoryTotal[];
  hours: Hour[];
  topSubjects: SubjectTotal[];
  unlocks: Unlocks;
  notifications: NotificationCount[];
  /** Absent from a backend older than marked computer time. */
  computerLabels?: ComputerLabel[];
}

// ---------------------------------------------------------------- trends

export type Period = 'day' | 'week' | 'month';

export interface PeriodSpan {
  from: string;
  to: string;
  screenMinutes: number;
  awayMinutes: number;
  byCategory: CategoryTotal[];
}

export interface SeriesPoint {
  date: string;
  screenMinutes: number;
  awayMinutes: number;
  /** False when the day is not closed, partial or incomplete: left out of both sides. */
  complete: boolean;
}

export interface Trends {
  period: Period;
  current: PeriodSpan;
  previous: PeriodSpan | null;
  series: SeriesPoint[];
}

// ---------------------------------------------------------------- categories

export interface Category {
  id: string;
  name: string;
  color: string;
  builtin: boolean;
}

export interface Subject {
  key: string;
  kind: string;
  label: string;
  categoryId: string | null;
  minutes30d: number;
}

// ---------------------------------------------------------------- devices

export interface Installation {
  id: string;
  fingerprint: string;
  createdAt: Instant;
  lastSeenAt: Instant | null;
  revokedAt: Instant | null;
  cloneSuspected: boolean;
}

export interface Device {
  id: string;
  name: string;
  kind: DeviceKind;
  installations: Installation[];
}

export interface PairingCode {
  code: string;
  expiresAt: Instant;
}

export interface CreatedDevice extends PairingCode {
  device: Device;
}

export interface PairingState {
  lockedOut: boolean;
  failures: number;
}

// ---------------------------------------------------------------- settings

export interface WorkWindow {
  /** ISO weekdays, 1 = Monday. */
  days: number[];
  start: string;
  end: string;
  validFrom: string;
}

export interface AwakeWindow {
  start: string;
  end: string;
  validFrom: string;
}

export interface SettingsHistoryEntry {
  kind: 'work' | 'awake' | string;
  value: Record<string, unknown>;
  validFrom: string;
}

export interface Settings {
  workWindow: WorkWindow;
  awakeWindow: AwakeWindow;
  history: SettingsHistoryEntry[];
  nonWorkdays: string[];
}

// ---------------------------------------------------------------- backstage

export type SensorState = 'active' | 'expected_stop' | 'late' | 'stopped' | 'silent';

export interface SensorStatus {
  name: string;
  state: SensorState;
  lastContact: number | null;
  detail: string | null;
}

export interface Gap {
  sensor: string;
  from: number;
  to: number;
  expected: boolean;
}

export interface DeviceHealth {
  id: string;
  name: string;
  kind: DeviceKind;
  sensors: SensorStatus[];
  eventsPerDay: { date: string; count: number }[];
  lagMs: { p50: number | null; p95: number | null };
  duplicates: number;
  rejected: number;
  droppedFields: number;
  intervalConflicts: number;
  invariantViolations: number;
  clockSkewMs: number | null;
  gaps: Gap[];
  shorts: { unconfirmedSinceVersion: string | null; lastConfirmedVersion: string | null } | null;
}

export interface Backstage {
  devices: DeviceHealth[];
  database: { sizeBytes: number; budgetBytes: number };
  derivation: Derivation & { version: number };
  blindSpots: string[];
}

/** api.md does not pin this shape down ("the raw events behind one segment and the rules that produced it"). */
export type Trace = Record<string, unknown>;

// ---------------------------------------------------------------- validation

export interface ValidationRow {
  date: string;
  closed: boolean;
  measuredMinutes: number | null;
  rawMinutes: number | null;
  settingsMinutes: number | null;
  diffPct: number | null;
  flagged: boolean;
}

// ---------------------------------------------------------------- corrections

export interface Redaction {
  id: string;
  deviceId: string;
  type: string;
  from: Instant;
  to: Instant;
  field: string | null;
  createdAt?: Instant;
  affected?: number;
}

export interface Exclusion {
  id: string;
  deviceId: string;
  type: string;
  from: Instant;
  to: Instant;
  note: string | null;
  createdAt?: Instant;
}

export interface Corrections {
  redactions: Redaction[];
  exclusions: Exclusion[];
}

// ---------------------------------------------------------------- transport

/** Thrown for any non-2xx so callers can branch on the status or the contract's error code. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const isApiError = (e: unknown, code?: string): e is ApiError =>
  e instanceof ApiError && (code === undefined || e.code === code);

/** Set once by the app shell: any 401 anywhere drops the user back to the login screen. */
let onUnauthorized: () => void = () => {};
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn;
};

const FALLBACK: Record<number, string> = {
  400: 'The server refused the request.',
  403: 'Not allowed.',
  404: 'Not found.',
  409: 'That conflicts with the current state.',
  429: 'Too many attempts. Wait a moment and try again.',
  500: 'The server failed.',
  502: 'The server is not reachable.',
  503: 'The server is not available right now.',
};

async function errorFrom(res: Response): Promise<ApiError> {
  let code: string | null = null;
  let message = FALLBACK[res.status] ?? `Request failed (${res.status}).`;
  try {
    const text = await res.text();
    if (text) {
      const body = JSON.parse(text) as { error?: unknown; message?: unknown };
      if (typeof body.error === 'string') code = body.error;
      if (typeof body.message === 'string' && body.message) message = body.message.slice(0, 300);
    }
  } catch {
    // not JSON: keep the generic message
  }
  return new ApiError(res.status, code, message);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const json = typeof init.body === 'string';
  let res: Response;
  try {
    res = await fetch(path, {
      credentials: 'same-origin',
      ...init,
      headers: { accept: 'application/json', ...(json ? { 'content-type': 'application/json' } : {}) },
    });
  } catch {
    throw new ApiError(0, 'network', 'The server could not be reached. Check the connection.');
  }
  if (!res.ok) {
    if (res.status === 401 && path !== '/api/session') onUnauthorized();
    throw await errorFrom(res);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const body = (value: unknown): RequestInit['body'] => JSON.stringify(value);
const q = (params: Record<string, string | number | boolean | undefined>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') s.set(k, String(v));
  const out = s.toString();
  return out ? `?${out}` : '';
};
const seg = encodeURIComponent;

export const api = {
  // session
  session: () => request<Session>('/api/session'),

  async login(username: string, password: string): Promise<void> {
    let res: Response;
    try {
      res = await fetch('/api/login', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ j_username: username, j_password: password }),
      });
    } catch {
      throw new ApiError(0, 'network', 'The server could not be reached.');
    }
    if (res.ok) return;
    if (res.status === 401) throw new ApiError(401, null, 'Wrong username or password.');
    throw await errorFrom(res);
  },

  logout: () => request<void>('/api/logout', { method: 'POST' }).catch(() => undefined),

  // day and trends
  day: (date: string) => request<DayView>(`/api/day${q({ date })}`),
  trends: (period: Period, anchor: string) => request<Trends>(`/api/trends${q({ period, anchor })}`),

  // categories and subjects
  categories: () => request<Category[]>('/api/categories'),
  createCategory: (name: string, color: string) =>
    request<Category>('/api/categories', { method: 'POST', body: body({ name, color }) }),
  updateCategory: (id: string, name: string, color: string) =>
    request<Category>(`/api/categories/${seg(id)}`, { method: 'PUT', body: body({ name, color }) }),
  deleteCategory: (id: string) => request<void>(`/api/categories/${seg(id)}`, { method: 'DELETE' }),
  subjects: (params: { q?: string; unmapped?: boolean; limit?: number } = {}) =>
    request<Subject[]>(`/api/subjects${q(params)}`),
  mapSubject: (key: string, categoryId: string | null) =>
    request<Subject>(`/api/subjects/${seg(key)}/category`, { method: 'PUT', body: body({ categoryId }) }),

  // computer time marked by hand
  addComputerLabel: (input: { date: string; start: string; end: string; categoryId: string }) =>
    request<Omit<ComputerLabel, 'minutes'>>('/api/computer-labels', { method: 'POST', body: body(input) }),
  removeComputerLabel: (id: string) => request<void>(`/api/computer-labels/${seg(id)}`, { method: 'DELETE' }),

  // devices
  devices: () => request<Device[]>('/api/devices'),
  createDevice: (name: string, kind: DeviceKind, confirmSecondPhone = false) =>
    request<CreatedDevice>('/api/devices', { method: 'POST', body: body({ name, kind, confirmSecondPhone }) }),
  pairingCode: (deviceId: string) =>
    request<PairingCode>(`/api/devices/${seg(deviceId)}/pairing-code`, { method: 'POST' }),
  revokeInstallation: (id: string) => request<void>(`/api/installations/${seg(id)}`, { method: 'DELETE' }),
  pairingState: () => request<PairingState>('/api/pairing-codes/state'),

  // settings
  settings: () => request<Settings>('/api/settings'),
  setWorkWindow: (days: number[], start: string, end: string) =>
    request<Settings>('/api/settings/work-window', { method: 'PUT', body: body({ days, start, end }) }),
  setAwakeWindow: (start: string, end: string) =>
    request<Settings>('/api/settings/awake-window', { method: 'PUT', body: body({ start, end }) }),
  addNonWorkday: (date: string) => request<Settings>('/api/non-workdays', { method: 'POST', body: body({ date }) }),
  removeNonWorkday: (date: string) => request<Settings>(`/api/non-workdays/${seg(date)}`, { method: 'DELETE' }),

  // backstage
  backstage: () => request<Backstage>('/api/backstage'),
  trace: (segmentId: string) => request<Trace>(`/api/backstage/trace${q({ segmentId })}`),

  // validation
  validation: (from: string, to: string) => request<ValidationRow[]>(`/api/validation${q({ from, to })}`),
  setValidation: (date: string, minutes: number) =>
    request<ValidationRow>(`/api/validation/${seg(date)}`, { method: 'PUT', body: body({ minutes }) }),

  // corrections
  corrections: () => request<Corrections>('/api/corrections'),
  redact: (input: { deviceId: string; type: string; from: number; to: number; field: string | null }) =>
    request<Redaction>('/api/corrections/redactions', { method: 'POST', body: body(input) }),
  exclude: (input: { deviceId: string; type: string; from: number; to: number; note: string | null }) =>
    request<Exclusion>('/api/corrections/exclusions', { method: 'POST', body: body(input) }),
  removeExclusion: (id: string) => request<void>(`/api/corrections/exclusions/${seg(id)}`, { method: 'DELETE' }),
};

export type Api = typeof api;
