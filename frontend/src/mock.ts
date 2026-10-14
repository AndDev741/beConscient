/**
 * Mock mode: answers /api/* from fixtures in the browser, with in-memory state,
 * so the page can be previewed with `npm run dev:mock` and no backend.
 * Loaded only when the build runs in mock mode; production builds leave it out.
 */
import type { Category, ComputerLabel, Corrections, DayView, Device, Settings, Subject } from './api';
import {
  CATEGORIES, CORRECTIONS, DEVICES, SUBJECTS, makeBackstage, makeDay, makeSettings, makeTrace, makeTrends,
  makeValidation,
} from './fixtures';
import { shiftDays, todayIso } from './format';

interface Reply {
  status: number;
  body?: unknown;
}

const ok = (body?: unknown): Reply => ({ status: body === undefined ? 204 : 200, body });
const fail = (status: number, error: string, message: string): Reply => ({ status, body: { error, message } });
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

function randomCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const pick = () => alphabet[Math.floor(Math.random() * alphabet.length)];
  return `${Array.from({ length: 4 }, pick).join('')}-${Array.from({ length: 4 }, pick).join('')}`;
}

export function createMockState() {
  const today = todayIso();
  const coverageStart = shiftDays(today, -45);
  let signedIn = true;
  let categories: Category[] = clone(CATEGORIES);
  const subjects: Subject[] = clone(SUBJECTS);
  const devices: Device[] = clone(DEVICES);
  let settings: Settings = makeSettings(today);
  const corrections: Corrections = clone(CORRECTIONS);
  // Listed on the day they were marked; mock mode does not move minutes between categories
  let labels: (ComputerLabel & { date: string })[] = [];
  const entered: Record<string, number> = {
    [shiftDays(today, -2)]: 240,
    [shiftDays(today, -3)]: 205,
    [shiftDays(today, -4)]: 260,
  };
  const pendingPairings: { deviceId: string; at: number }[] = [];
  let failures = 0;

  function dayFor(date: string): DayView {
    const now = new Date();
    if (date > today || date < coverageStart) return makeDay(date, { empty: true, state: date > today ? 'open' : 'closed' });
    if (date === today) return makeDay(date, { state: 'open', elapsedUntilHour: now.getHours() + 1, jitter: true });
    const yesterday = shiftDays(today, -1);
    const silent = date === shiftDays(today, -5) ? ['extension_chrome (Desk)'] : undefined;
    return makeDay(date, { state: date === yesterday ? 'arriving' : 'closed', silentSensors: silent, jitter: true });
  }

  /** Category names and colours follow the current mapping, like the backend does at read time. */
  function withCategories(d: DayView): DayView {
    const byId = new Map(categories.map((c) => [c.id, c]));
    return {
      ...d,
      byCategory: d.byCategory.map((c) => ({ ...c, name: byId.get(c.categoryId)?.name ?? c.name, color: byId.get(c.categoryId)?.color ?? c.color })),
      topSubjects: d.topSubjects.map((s) => ({ ...s, categoryId: subjects.find((x) => x.key === s.subjectKey)?.categoryId ?? s.categoryId })),
    };
  }

  function settle() {
    // A collector "pairs" eight seconds after a code is created, so the fingerprint step can be seen
    const now = Date.now();
    for (let i = pendingPairings.length - 1; i >= 0; i--) {
      const p = pendingPairings[i];
      if (now - p.at < 8000) continue;
      const device = devices.find((d) => d.id === p.deviceId);
      if (device) {
        for (const inst of device.installations) if (!inst.revokedAt) inst.revokedAt = now;
        device.installations.push({
          id: `i-${now}`,
          fingerprint: randomCode(),
          createdAt: now,
          lastSeenAt: now,
          revokedAt: null,
          cloneSuspected: false,
        });
      }
      pendingPairings.splice(i, 1);
    }
  }

  function route(method: string, path: string, query: URLSearchParams, body: Record<string, unknown>): Reply {
    if (method === 'POST' && path === '/api/login') {
      signedIn = true;
      return ok();
    }
    if (method === 'POST' && path === '/api/logout') {
      signedIn = false;
      return ok();
    }
    if (!signedIn) return fail(401, 'unauthorized', 'Sign in first.');
    if (method === 'GET' && path === '/api/session') return ok({ username: 'owner' });

    if (method === 'GET' && path === '/api/day') {
      const date = query.get('date') ?? today;
      return ok({ ...withCategories(dayFor(date)), computerLabels: labels.filter((l) => l.date === date) });
    }
    if (method === 'POST' && path === '/api/computer-labels') {
      const date = String(body.date);
      const from = new Date(`${date}T${String(body.start)}`).getTime();
      let to = new Date(`${date}T${String(body.end)}`).getTime();
      if (to <= from) to += 86_400_000;
      if (labels.some((l) => l.from < to && l.to > from)) return fail(409, 'label_overlap', 'That stretch overlaps one already marked. Remove that one first.');
      const row = { id: `l-${Date.now()}`, categoryId: String(body.categoryId), from, to };
      labels = [...labels, { ...row, date, minutes: Math.round((to - from) / 60_000) }];
      return { status: 201, body: row };
    }
    let m = path.match(/^\/api\/computer-labels\/([^/]+)$/);
    if (m && method === 'DELETE') {
      const id = decodeURIComponent(m[1]);
      labels = labels.filter((l) => l.id !== id);
      return ok();
    }
    if (method === 'GET' && path === '/api/trends') {
      const period = (query.get('period') ?? 'week') as 'day' | 'week' | 'month';
      return ok(makeTrends(period, query.get('anchor') ?? today, (d) => withCategories(dayFor(d)), today, coverageStart));
    }

    if (path === '/api/categories') {
      if (method === 'GET') return ok(categories);
      const c: Category = { id: `c-${Date.now()}`, name: String(body.name), color: String(body.color), builtin: false };
      categories = [...categories, c];
      return { status: 201, body: c };
    }
    m = path.match(/^\/api\/categories\/([^/]+)$/);
    if (m) {
      const id = decodeURIComponent(m[1]);
      const c = categories.find((x) => x.id === id);
      if (!c) return fail(404, 'not_found', 'No such category.');
      if (method === 'PUT') {
        Object.assign(c, { name: c.builtin ? c.name : String(body.name), color: String(body.color) });
        return ok(c);
      }
      if (method === 'DELETE') {
        if (subjects.some((s) => s.categoryId === id) || labels.some((l) => l.categoryId === id)) {
          return fail(409, 'category_in_use', 'Category in use.');
        }
        categories = categories.filter((x) => x.id !== id);
        return ok();
      }
    }
    if (method === 'GET' && path === '/api/subjects') {
      const q = (query.get('q') ?? '').toLowerCase();
      const unmapped = query.get('unmapped') === 'true';
      return ok(
        subjects
          .filter((s) => (!q || s.label.toLowerCase().includes(q) || s.key.toLowerCase().includes(q)) && (!unmapped || s.categoryId === null))
          .sort((a, b) => b.minutes30d - a.minutes30d)
          .slice(0, Number(query.get('limit') ?? 500)),
      );
    }
    m = path.match(/^\/api\/subjects\/(.+)\/category$/);
    if (m && method === 'PUT') {
      const key = decodeURIComponent(m[1]);
      const s = subjects.find((x) => x.key === key);
      if (!s) return fail(404, 'not_found', 'No such subject.');
      s.categoryId = (body.categoryId as string | null) ?? null;
      return ok(s);
    }

    if (path === '/api/devices') {
      settle();
      if (method === 'GET') return ok(devices);
      const kind = body.kind === 'desktop' ? 'desktop' : 'android';
      const activePhone = devices.some((d) => d.kind === 'android' && d.installations.some((i) => !i.revokedAt));
      if (kind === 'android' && activePhone && !body.confirmSecondPhone) {
        return fail(409, 'second_phone', 'Another Android phone is active.');
      }
      const device: Device = { id: `d-${Date.now()}`, name: String(body.name), kind, installations: [] };
      devices.push(device);
      pendingPairings.push({ deviceId: device.id, at: Date.now() });
      failures = 0;
      return ok({ device, code: randomCode(), expiresAt: Date.now() + 15 * 60_000 });
    }
    m = path.match(/^\/api\/devices\/([^/]+)\/pairing-code$/);
    if (m && method === 'POST') {
      pendingPairings.push({ deviceId: decodeURIComponent(m[1]), at: Date.now() });
      failures = 0;
      return ok({ code: randomCode(), expiresAt: Date.now() + 15 * 60_000 });
    }
    m = path.match(/^\/api\/installations\/([^/]+)$/);
    if (m && method === 'DELETE') {
      for (const d of devices) for (const i of d.installations) if (i.id === decodeURIComponent(m[1])) i.revokedAt = Date.now();
      return ok();
    }
    if (method === 'GET' && path === '/api/pairing-codes/state') return ok({ lockedOut: failures >= 10, failures });

    if (method === 'GET' && path === '/api/settings') return ok(settings);
    if (method === 'PUT' && path === '/api/settings/work-window') {
      const value = { days: body.days as number[], start: String(body.start), end: String(body.end) };
      settings = {
        ...settings,
        workWindow: { ...value, validFrom: today },
        history: [...settings.history.filter((h) => !(h.kind === 'work' && h.validFrom === today)), { kind: 'work', value, validFrom: today }],
      };
      return ok(settings);
    }
    if (method === 'PUT' && path === '/api/settings/awake-window') {
      const value = { start: String(body.start), end: String(body.end) };
      settings = {
        ...settings,
        awakeWindow: { ...value, validFrom: today },
        history: [...settings.history.filter((h) => !(h.kind === 'awake' && h.validFrom === today)), { kind: 'awake', value, validFrom: today }],
      };
      return ok(settings);
    }
    if (method === 'POST' && path === '/api/non-workdays') {
      const date = String(body.date);
      if (!settings.nonWorkdays.includes(date)) settings = { ...settings, nonWorkdays: [...settings.nonWorkdays, date] };
      return ok(settings);
    }
    m = path.match(/^\/api\/non-workdays\/([^/]+)$/);
    if (m && method === 'DELETE') {
      settings = { ...settings, nonWorkdays: settings.nonWorkdays.filter((d) => d !== decodeURIComponent(m![1])) };
      return ok(settings);
    }

    if (method === 'GET' && path === '/api/backstage') return ok(makeBackstage(Date.now()));
    if (method === 'GET' && path === '/api/backstage/trace') return ok(makeTrace(query.get('segmentId') ?? ''));

    if (method === 'GET' && path === '/api/validation') {
      return ok(makeValidation(query.get('from') ?? today, query.get('to') ?? today, today, entered, dayFor));
    }
    m = path.match(/^\/api\/validation\/([^/]+)$/);
    if (m && method === 'PUT') {
      const date = decodeURIComponent(m[1]);
      if (date >= shiftDays(today, -1)) return fail(409, 'day_not_closed', 'The day is not closed.');
      entered[date] = Number(body.minutes);
      return ok(makeValidation(date, date, today, entered, dayFor)[0]);
    }

    if (method === 'GET' && path === '/api/corrections') return ok(corrections);
    if (method === 'POST' && path === '/api/corrections/redactions') {
      const row = { id: `r-${Date.now()}`, deviceId: String(body.deviceId), type: String(body.type), from: Number(body.from), to: Number(body.to), field: (body.field as string | null) ?? null, createdAt: Date.now() };
      corrections.redactions.unshift(row);
      return ok(row);
    }
    if (method === 'POST' && path === '/api/corrections/exclusions') {
      const row = { id: `x-${Date.now()}`, deviceId: String(body.deviceId), type: String(body.type), from: Number(body.from), to: Number(body.to), note: (body.note as string | null) ?? null, createdAt: Date.now() };
      corrections.exclusions.unshift(row);
      return ok(row);
    }
    m = path.match(/^\/api\/corrections\/exclusions\/([^/]+)$/);
    if (m && method === 'DELETE') {
      corrections.exclusions = corrections.exclusions.filter((x) => x.id !== decodeURIComponent(m![1]));
      return ok();
    }

    return fail(404, 'not_found', `Mock has no route for ${method} ${path}.`);
  }

  return { route };
}

export function installMock() {
  const state = createMockState();
  const real = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw, window.location.origin);
    if (!url.pathname.startsWith('/api/')) return real(input, init);
    const method = (init?.method ?? 'GET').toUpperCase();
    let body: Record<string, unknown> = {};
    if (typeof init?.body === 'string') {
      try {
        body = JSON.parse(init.body) as Record<string, unknown>;
      } catch {
        body = {};
      }
    }
    await new Promise((r) => setTimeout(r, 120 + Math.random() * 280));
    const reply = state.route(method, url.pathname, url.searchParams, body);
    if (reply.status === 204) return new Response(null, { status: 204 });
    return new Response(JSON.stringify(reply.body), { status: reply.status, headers: { 'content-type': 'application/json' } });
  };
  console.info('[beConscient] mock mode: /api/* answered from fixtures');
}
