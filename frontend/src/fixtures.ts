/**
 * Typed fixtures shaped exactly like contract/api.md. Tests use them directly and
 * mock mode (npm run dev:mock) serves them, so both exercise the same shapes.
 */
import type {
  Backstage, Category, CategoryTotal, DayState, DayView, Device, Hour, Settings, Subject, Trends,
  ValidationRow, Period, SeriesPoint, Corrections,
} from './api';
import { isoDate, isoWeekday, parseIso, shiftDays, shiftMonths } from './format';

export const CATEGORIES: Category[] = [
  { id: 'c-work', name: 'Work', color: '#5b6cff', builtin: true },
  { id: 'c-pc', name: 'Personal computer', color: '#2f8fb3', builtin: true },
  { id: 'c-unc', name: 'Uncategorized', color: '#6b7280', builtin: true },
  { id: 'c-una', name: 'Unattributed', color: '#8a8f98', builtin: true },
  { id: 'c-social', name: 'Social', color: '#c2416b', builtin: false },
  { id: 'c-video', name: 'Video', color: '#d9822b', builtin: false },
  { id: 'c-msg', name: 'Messaging', color: '#3f7a68', builtin: false },
];

const DESKTOP_CATEGORIES = new Set(['c-work', 'c-pc']);

/** A typical weekday, minutes per category per local hour of the day. */
const WEEKDAY_PLAN: Record<number, Record<string, number>> = {
  1: { 'c-video': 20 },
  7: { 'c-msg': 10, 'c-social': 6 },
  8: { 'c-social': 14, 'c-una': 2 },
  9: { 'c-work': 48, 'c-msg': 4 },
  10: { 'c-work': 52 },
  11: { 'c-work': 44, 'c-social': 6 },
  12: { 'c-social': 18, 'c-unc': 5 },
  13: { 'c-work': 30, 'c-video': 8 },
  14: { 'c-work': 50, 'c-una': 3 },
  15: { 'c-work': 47, 'c-msg': 6 },
  16: { 'c-work': 40 },
  17: { 'c-work': 35, 'c-social': 5 },
  18: { 'c-pc': 20, 'c-video': 12 },
  19: { 'c-video': 25 },
  20: { 'c-video': 34, 'c-social': 8 },
  21: { 'c-social': 15, 'c-video': 10 },
  22: { 'c-msg': 9 },
};

const WEEKEND_PLAN: Record<number, Record<string, number>> = {
  9: { 'c-social': 12 },
  10: { 'c-video': 20, 'c-msg': 6 },
  12: { 'c-pc': 35 },
  13: { 'c-pc': 25, 'c-video': 10 },
  16: { 'c-social': 10 },
  19: { 'c-video': 40 },
  20: { 'c-video': 45 },
  21: { 'c-social': 12 },
};

const hash = (s: string) => {
  let h = 2166136261;
  for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return Math.abs(h);
};

/** Local midnight-to-midnight hour starts: 23 or 25 of them on a daylight-saving day. */
function localHours(date: string, count?: number): { start: number; label: string; hour: number }[] {
  const d = parseIso(date);
  const midnight = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const next = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime();
  const n = count ?? Math.round((next - midnight) / 3_600_000);
  return Array.from({ length: n }, (_, i) => {
    const start = midnight + i * 3_600_000;
    const at = new Date(start);
    const hour = count && count !== 24 ? i : at.getHours();
    return { start, hour, label: `${String(hour % 24).padStart(2, '0')}:00` };
  });
}

export interface DayOptions {
  state?: DayState;
  /** Number of hour columns (24 by default, or the local day's real length). */
  hourCount?: number;
  /** For today: hours from this one on have no data yet, and the headline counts only elapsed time. */
  elapsedUntilHour?: number;
  silentSensors?: string[];
  empty?: boolean;
  rebuilding?: number | null;
  /** Varies the numbers a little per date, for mock mode. */
  jitter?: boolean;
}

export function makeDay(date: string, opts: DayOptions = {}): DayView {
  const weekend = isoWeekday(date) >= 6;
  const plan = weekend ? WEEKEND_PLAN : WEEKDAY_PLAN;
  const seed = hash(date);
  const hours: Hour[] = localHours(date, opts.hourCount).map(({ start, label, hour }) => {
    const elapsed = opts.elapsedUntilHour === undefined || hour < opts.elapsedUntilHour;
    const row = opts.empty || !elapsed ? {} : (plan[hour] ?? {});
    const byCategory = Object.entries(row).map(([categoryId, minutes]) => {
      const wobble = opts.jitter ? ((seed >> (hour % 16)) % 9) - 4 : 0;
      return { categoryId, minutes: Math.max(0, Math.min(60, minutes + wobble)) };
    });
    const segments = byCategory.map((c, i) => ({
      id: `seg-${date}-${hour}-${i}`,
      label: CATEGORIES.find((k) => k.id === c.categoryId)?.name ?? c.categoryId,
      minutes: c.minutes,
    }));
    return { label, start, byCategory, segments };
  });

  const sums = new Map<string, number>();
  for (const h of hours) for (const c of h.byCategory) sums.set(c.categoryId, (sums.get(c.categoryId) ?? 0) + c.minutes);
  const byCategory: CategoryTotal[] = CATEGORIES.filter((c) => (sums.get(c.id) ?? 0) > 0)
    .map((c) => ({ categoryId: c.id, name: c.name, color: c.color, minutes: sums.get(c.id) ?? 0 }))
    .sort((a, b) => b.minutes - a.minutes);

  const total = byCategory.reduce((s, c) => s + c.minutes, 0);
  const desktopOwn = byCategory.filter((c) => DESKTOP_CATEGORIES.has(c.categoryId)).reduce((s, c) => s + c.minutes, 0);
  const phone = total - desktopOwn;
  const overlap = desktopOwn > 0 && phone > 0 ? Math.min(22, phone) : 0;

  // Awake 07:00–23:00. Screen time at 01:00 is outside the window.
  const awakeHours = hours.filter((h) => {
    const hr = new Date(h.start).getHours();
    return hr >= 7 && hr < 23 && (opts.elapsedUntilHour === undefined || hr < opts.elapsedUntilHour);
  });
  const awakeMinutes = awakeHours.length * 60;
  const inside = awakeHours.reduce((s, h) => s + h.byCategory.reduce((a, c) => a + c.minutes, 0), 0);
  const outside = total - inside;
  const minutes = (id: string) => sums.get(id) ?? 0;

  const topSubjects = [
    { subjectKey: 'app:com.whatsapp', label: 'com.whatsapp', categoryId: 'c-msg', minutes: minutes('c-msg') },
    { subjectKey: 'app:com.instagram.android', label: 'com.instagram.android', categoryId: 'c-social', minutes: Math.round(minutes('c-social') * 0.6) },
    { subjectKey: 'app:com.google.android.youtube#shorts', label: 'YouTube Shorts', categoryId: 'c-video', minutes: Math.round(minutes('c-video') * 0.45) },
    { subjectKey: 'site:youtube', label: 'YouTube (browser)', categoryId: 'c-video', minutes: Math.round(minutes('c-video') * 0.35) },
    { subjectKey: 'site:linkedin', label: 'LinkedIn', categoryId: 'c-social', minutes: Math.round(minutes('c-social') * 0.4) },
    { subjectKey: 'app:org.example.reader', label: 'org.example.reader', categoryId: null, minutes: minutes('c-unc') },
  ]
    .filter((s) => s.minutes > 0)
    .sort((a, b) => b.minutes - a.minutes);

  const quiet = opts.empty || total === 0;
  return {
    date,
    zone: 'Europe/Lisbon',
    state: opts.state ?? 'closed',
    derivation: { rebuilding: opts.rebuilding != null, progress: opts.rebuilding ?? 1 },
    headline: {
      awakeMinutes,
      awayMinutes: awakeMinutes - inside,
      screenMinutes: inside,
      outsideAwakeScreenMinutes: outside,
      elapsedOnly: opts.elapsedUntilHour !== undefined,
      incomplete: (opts.silentSensors?.length ?? 0) > 0,
      silentSensors: opts.silentSensors ?? [],
    },
    totals: {
      screenMinutes: total,
      overlapMinutes: overlap,
      byDevice: quiet
        ? []
        : [
            { deviceId: 'd-phone', name: 'Phone', kind: 'android', minutes: phone },
            ...(desktopOwn > 0 ? [{ deviceId: 'd-desk', name: 'Desk', kind: 'desktop' as const, minutes: desktopOwn + overlap }] : []),
          ],
    },
    byCategory,
    hours,
    topSubjects,
    unlocks: quiet
      ? { count: 0, firstApps: [] }
      : {
          count: 57,
          firstApps: [
            { subjectKey: 'app:com.whatsapp', label: 'com.whatsapp', count: 19 },
            { subjectKey: 'app:com.instagram.android', label: 'com.instagram.android', count: 11 },
            { subjectKey: 'app:com.google.android.youtube', label: 'com.google.android.youtube', count: 6 },
          ],
        },
    notifications: quiet
      ? []
      : [
          { subjectKey: 'app:com.whatsapp', label: 'com.whatsapp', posted: 88, tapped: 21, dismissed: 30 },
          { subjectKey: 'app:com.google.android.gm', label: 'com.google.android.gm', posted: 24, tapped: 3, dismissed: 15 },
          { subjectKey: 'app:com.instagram.android', label: 'com.instagram.android', posted: 12, tapped: 5, dismissed: 2 },
        ],
  };
}

// ---------------------------------------------------------------- trends

function periodSpan(period: Period, anchor: string): { from: string; to: string } {
  if (period === 'day') return { from: anchor, to: anchor };
  if (period === 'week') {
    const from = shiftDays(anchor, -(isoWeekday(anchor) - 1));
    return { from, to: shiftDays(from, 6) };
  }
  const d = parseIso(anchor);
  return { from: isoDate(new Date(d.getFullYear(), d.getMonth(), 1, 12)), to: isoDate(new Date(d.getFullYear(), d.getMonth() + 1, 0, 12)) };
}

function datesBetween(from: string, to: string): string[] {
  const out: string[] = [];
  for (let d = from; d <= to; d = shiftDays(d, 1)) out.push(d);
  return out;
}

export function makeTrends(
  period: Period,
  anchor: string,
  dayFor: (date: string) => DayView,
  today: string,
  coverageStart: string,
): Trends {
  const span = periodSpan(period, anchor);
  const to = span.to > today ? today : span.to;
  const dates = datesBetween(span.from, to);
  const elapsed = dates.length;

  const series: SeriesPoint[] = dates.map((date) => {
    const d = dayFor(date);
    return {
      date,
      screenMinutes: d.totals.screenMinutes,
      awayMinutes: d.headline.awayMinutes,
      complete: date >= coverageStart && d.state === 'closed' && !d.headline.incomplete,
    };
  });

  const prevAnchor = period === 'day' ? shiftDays(anchor, -1) : period === 'week' ? shiftDays(anchor, -7) : shiftMonths(anchor, -1);
  const prevSpan = periodSpan(period, prevAnchor);
  const prevDates = datesBetween(prevSpan.from, prevSpan.to).slice(0, elapsed);
  const excluded = new Set(series.filter((p) => !p.complete).map((p) => dates.indexOf(p.date)));

  const sum = (ds: string[]): Omit<Trends['current'], 'from' | 'to'> => {
    const days = ds.map(dayFor);
    const cat = new Map<string, CategoryTotal>();
    for (const d of days)
      for (const c of d.byCategory) {
        const prev = cat.get(c.categoryId);
        cat.set(c.categoryId, { ...c, minutes: (prev?.minutes ?? 0) + c.minutes });
      }
    return {
      screenMinutes: days.reduce((s, d) => s + d.totals.screenMinutes, 0),
      awayMinutes: days.reduce((s, d) => s + d.headline.awayMinutes, 0),
      byCategory: [...cat.values()].sort((a, b) => b.minutes - a.minutes),
    };
  };

  const curIncluded = dates.filter((_, i) => !excluded.has(i));
  const prevIncluded = prevDates.filter((_, i) => !excluded.has(i));
  const hasPrevious = prevSpan.from >= coverageStart && prevIncluded.length > 0;

  return {
    period,
    current: { from: span.from, to, ...sum(curIncluded) },
    previous: hasPrevious ? { from: prevSpan.from, to: prevDates[prevDates.length - 1], ...sum(prevIncluded) } : null,
    series,
  };
}

// ---------------------------------------------------------------- subjects

export const SUBJECTS: Subject[] = [
  { key: 'app:com.whatsapp', kind: 'app', label: 'com.whatsapp', categoryId: 'c-msg', minutes30d: 1210 },
  { key: 'app:com.instagram.android', kind: 'app', label: 'com.instagram.android', categoryId: 'c-social', minutes30d: 980 },
  { key: 'app:com.google.android.youtube#shorts', kind: 'app', label: 'YouTube Shorts', categoryId: 'c-video', minutes30d: 760 },
  { key: 'app:com.google.android.youtube', kind: 'app', label: 'com.google.android.youtube', categoryId: 'c-video', minutes30d: 640 },
  { key: 'site:youtube', kind: 'site', label: 'YouTube (browser)', categoryId: 'c-video', minutes30d: 590 },
  { key: 'site:linkedin', kind: 'site', label: 'LinkedIn', categoryId: null, minutes30d: 410 },
  { key: 'app:org.example.reader', kind: 'app', label: 'org.example.reader', categoryId: null, minutes30d: 260 },
  { key: 'app:com.google.android.gm', kind: 'app', label: 'com.google.android.gm', categoryId: 'c-work', minutes30d: 230 },
  { key: 'app:com.android.chrome', kind: 'app', label: 'com.android.chrome', categoryId: null, minutes30d: 205 },
  { key: 'app:org.telegram.messenger', kind: 'app', label: 'org.telegram.messenger', categoryId: 'c-msg', minutes30d: 120 },
  { key: 'app:com.spotify.music', kind: 'app', label: 'com.spotify.music', categoryId: null, minutes30d: 44 },
  { key: 'app:com.duolingo', kind: 'app', label: 'com.duolingo', categoryId: null, minutes30d: 31 },
];

// ---------------------------------------------------------------- devices

const DAY = 86_400_000;

export const DEVICES: Device[] = [
  {
    id: 'd-phone',
    name: 'Phone',
    kind: 'android',
    installations: [
      { id: 'i-phone-1', fingerprint: 'K7QX-M2PD', createdAt: Date.now() - 60 * DAY, lastSeenAt: Date.now() - 31 * DAY, revokedAt: Date.now() - 30 * DAY, cloneSuspected: false },
      { id: 'i-phone-2', fingerprint: 'R4WN-8HTC', createdAt: Date.now() - 30 * DAY, lastSeenAt: Date.now() - 6 * 60_000, revokedAt: null, cloneSuspected: false },
    ],
  },
  {
    id: 'd-desk',
    name: 'Desk',
    kind: 'desktop',
    installations: [
      { id: 'i-desk-1', fingerprint: 'B9FJ-2LZE', createdAt: Date.now() - 59 * DAY, lastSeenAt: Date.now() - 2 * 60_000, revokedAt: null, cloneSuspected: false },
    ],
  },
];

// ---------------------------------------------------------------- settings

export function makeSettings(today: string): Settings {
  const since = shiftDays(today, -40);
  return {
    workWindow: { days: [1, 2, 3, 4, 5], start: '09:00', end: '18:00', validFrom: since },
    awakeWindow: { start: '07:00', end: '23:00', validFrom: since },
    history: [
      { kind: 'work', value: { days: [1, 2, 3, 4, 5], start: '09:00', end: '18:00' }, validFrom: since },
      { kind: 'awake', value: { start: '07:00', end: '23:00' }, validFrom: since },
    ],
    nonWorkdays: ['2026-12-25', '2026-10-05'],
  };
}

// ---------------------------------------------------------------- backstage

export function makeBackstage(now: number): Backstage {
  const min = 60_000;
  const day = (offset: number) => isoDate(new Date(now - offset * 86_400_000));
  return {
    devices: [
      {
        id: 'd-phone',
        name: 'Phone',
        kind: 'android',
        sensors: [
          { name: 'usage', state: 'active', lastContact: now - 6 * min, detail: null },
          { name: 'notifications', state: 'active', lastContact: now - 6 * min, detail: null },
          { name: 'shorts', state: 'late', lastContact: now - 2 * 60 * min, detail: 'YouTube updated; rules 3' },
          { name: 'battery_exemption', state: 'active', lastContact: now - 6 * min, detail: null },
        ],
        eventsPerDay: [0, 1, 2, 3, 4, 5, 6].map((i) => ({ date: day(i), count: 5210 - i * 137 })),
        lagMs: { p50: 420_000, p95: 1_800_000 },
        duplicates: 12,
        rejected: 0,
        droppedFields: 0,
        intervalConflicts: 0,
        invariantViolations: 0,
        clockSkewMs: 1200,
        gaps: [
          { sensor: 'usage', from: now - 30 * 60 * min, to: now - 22 * 60 * min, expected: true },
          { sensor: 'usage', from: now - 50 * 60 * min, to: now - 49 * 60 * min, expected: false },
        ],
        shorts: { unconfirmedSinceVersion: '19.41.36', lastConfirmedVersion: '19.40.33' },
      },
      {
        id: 'd-desk',
        name: 'Desk',
        kind: 'desktop',
        sensors: [
          { name: 'activity', state: 'active', lastContact: now - 2 * min, detail: null },
          { name: 'extension_chrome', state: 'silent', lastContact: now - 26 * 60 * min, detail: null },
          { name: 'extension_brave', state: 'expected_stop', lastContact: now - 3 * 60 * min, detail: null },
        ],
        eventsPerDay: [0, 1, 2, 3, 4, 5, 6].map((i) => ({ date: day(i), count: 420 - i * 11 })),
        lagMs: { p50: 90_000, p95: 330_000 },
        duplicates: 3,
        rejected: 1,
        droppedFields: 2,
        intervalConflicts: 1,
        invariantViolations: 0,
        clockSkewMs: -800,
        gaps: [{ sensor: 'extension_chrome', from: now - 26 * 60 * min, to: now, expected: false }],
        shorts: null,
      },
    ],
    database: { sizeBytes: 7_730_941_132, budgetBytes: 10_737_418_240 },
    derivation: { version: 1, rebuilding: false, progress: 1 },
    blindSpots: ['Firefox', 'YouTube in the phone browser', 'Computer leisure outside the browser'],
  };
}

export function makeTrace(segmentId: string): Record<string, unknown> {
  return {
    segment: { id: segmentId, deviceId: 'd-phone', subjectKey: 'app:com.whatsapp', start: 1760083200000, end: 1760083800000, derivationVersion: 1 },
    events: [
      { id: '0192-a', type: 'android.usage', at: 1760083200000, kind: 'SCREEN_INTERACTIVE' },
      { id: '0192-b', type: 'android.usage', at: 1760083201000, kind: 'KEYGUARD_HIDDEN' },
      { id: '0192-c', type: 'android.usage', at: 1760083203000, kind: 'ACTIVITY_RESUMED', package: 'com.whatsapp' },
      { id: '0192-d', type: 'android.usage', at: 1760083800000, kind: 'ACTIVITY_PAUSED', package: 'com.whatsapp' },
    ],
    rules: ['App interval: ACTIVITY_RESUMED to ACTIVITY_PAUSED', 'Clipped to screen on and unlocked'],
  };
}

// ---------------------------------------------------------------- validation

export function makeValidation(from: string, to: string, today: string, entered: Record<string, number>, dayFor: (d: string) => DayView): ValidationRow[] {
  return datesBetween(from, to).map((date) => {
    const closed = date < shiftDays(today, -1);
    const phone = dayFor(date).totals.byDevice.find((d) => d.kind === 'android')?.minutes ?? 0;
    const measured = closed ? phone : null;
    const settings = entered[date] ?? null;
    const diffPct = measured !== null && settings ? ((measured - settings) / settings) * 100 : null;
    return {
      date,
      closed,
      measuredMinutes: measured,
      rawMinutes: measured === null ? null : measured + 14,
      settingsMinutes: settings,
      diffPct,
      flagged: diffPct !== null && Math.abs(diffPct) > 10,
    };
  });
}

export const CORRECTIONS: Corrections = {
  redactions: [
    { id: 'r-1', deviceId: 'd-desk', type: 'browser.site', from: 1759300000000, to: 1759303600000, field: 'title', createdAt: 1759400000000 },
  ],
  exclusions: [
    { id: 'x-1', deviceId: 'd-phone', type: 'android.usage', from: 1759500000000, to: 1759503600000, note: 'Phone lent to a friend', createdAt: 1759600000000 },
  ],
};
