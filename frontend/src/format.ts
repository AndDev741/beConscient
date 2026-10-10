/** Formatting and date helpers. Pure functions, no React. */

import type { Instant } from './api';

const LOCALE = 'en-GB';

/** 0 → "0 min", 45 → "45 min", 125 → "2 h 5 min", 600 → "10 h". */
export function duration(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  const h = Math.floor(m / 60);
  const rest = m % 60;
  if (h === 0) return `${rest} min`;
  if (rest === 0) return `${h} h`;
  return `${h} h ${rest} min`;
}

/** The pieces of a duration, for typography that sizes number and unit differently. */
export function durationParts(minutes: number): { value: string; unit: string }[] {
  const m = Math.max(0, Math.round(minutes));
  const h = Math.floor(m / 60);
  const rest = m % 60;
  const parts: { value: string; unit: string }[] = [];
  if (h > 0) parts.push({ value: String(h), unit: 'h' });
  if (rest > 0 || h === 0) parts.push({ value: String(rest), unit: 'min' });
  return parts;
}

/** Signed difference as text: "+1 h 5 min", "−20 min", "no change". */
export function signedDuration(minutes: number): string {
  if (Math.round(minutes) === 0) return 'no change';
  return `${minutes > 0 ? '+' : '−'}${duration(Math.abs(minutes))}`;
}

export const percent = (value: number, digits = 0) =>
  `${value.toLocaleString(LOCALE, { minimumFractionDigits: digits, maximumFractionDigits: digits })}%`;

export function signedPercent(value: number, digits = 1): string {
  const abs = Math.abs(value).toLocaleString(LOCALE, { minimumFractionDigits: digits, maximumFractionDigits: digits });
  if (Number(abs) === 0) return `${abs}%`;
  return `${value > 0 ? '+' : '−'}${abs}%`;
}

export const count = (n: number) => n.toLocaleString(LOCALE);

export function bytes(n: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toLocaleString(LOCALE, { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 })} ${units[i]}`;
}

/** 420000 → "7 min", 1200 → "1.2 s", 90 → "90 ms". */
export function millis(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  const abs = Math.abs(ms);
  const sign = ms < 0 ? '−' : '';
  if (abs < 1000) return `${sign}${Math.round(abs)} ms`;
  if (abs < 60_000) return `${sign}${(abs / 1000).toLocaleString(LOCALE, { maximumFractionDigits: 1 })} s`;
  if (abs < 3_600_000) return `${sign}${Math.round(abs / 60_000)} min`;
  return `${sign}${duration(abs / 60_000)}`;
}

// ---------------------------------------------------------------- dates

const pad = (n: number) => String(n).padStart(2, '0');

/** Local calendar date of the browser, as YYYY-MM-DD. */
export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export const todayIso = (now: Date = new Date()) => isoDate(now);

/** Noon avoids any daylight-saving edge when doing calendar arithmetic. */
export function parseIso(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d, 12);
}

export function shiftDays(iso: string, by: number): string {
  const d = parseIso(iso);
  d.setDate(d.getDate() + by);
  return isoDate(d);
}

export function shiftMonths(iso: string, by: number): string {
  const d = parseIso(iso);
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + by);
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  d.setDate(Math.min(day, last));
  return isoDate(d);
}

/** ISO weekday, 1 = Monday … 7 = Sunday. */
export const isoWeekday = (iso: string) => ((parseIso(iso).getDay() + 6) % 7) + 1;

export const WEEKDAYS = [
  { n: 1, short: 'Mon', long: 'Monday' },
  { n: 2, short: 'Tue', long: 'Tuesday' },
  { n: 3, short: 'Wed', long: 'Wednesday' },
  { n: 4, short: 'Thu', long: 'Thursday' },
  { n: 5, short: 'Fri', long: 'Friday' },
  { n: 6, short: 'Sat', long: 'Saturday' },
  { n: 7, short: 'Sun', long: 'Sunday' },
];

/** "Saturday, 10 October" (the year only when it is not the current one). */
export function dateLong(iso: string, now: Date = new Date()): string {
  const d = parseIso(iso);
  const opts: Intl.DateTimeFormatOptions = { weekday: 'long', day: 'numeric', month: 'long' };
  if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString(LOCALE, opts);
}

/** "10 Oct" or "10 Oct 2025". */
export function dateShort(iso: string, now: Date = new Date()): string {
  const d = parseIso(iso);
  const opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' };
  if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString(LOCALE, opts);
}

export const weekdayShort = (iso: string) => parseIso(iso).toLocaleDateString(LOCALE, { weekday: 'short' });

export function rangeLabel(from: string, to: string, now: Date = new Date()): string {
  if (from === to) return dateShort(from, now);
  return `${dateShort(from, now)} – ${dateShort(to, now)}`;
}

export function toMillis(v: Instant | null | undefined): number | null {
  if (v === null || v === undefined || v === '') return null;
  if (typeof v === 'number') return v;
  const n = Number(v);
  if (Number.isFinite(n) && /^\d+$/.test(v)) return n;
  const t = Date.parse(v);
  return Number.isNaN(t) ? null : t;
}

/** "10 Oct, 14:05". */
export function instant(v: Instant | null | undefined): string {
  const t = toMillis(v);
  if (t === null) return '—';
  return new Date(t).toLocaleString(LOCALE, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

export function timeOfDay(v: Instant): string {
  const t = toMillis(v);
  if (t === null) return '—';
  return new Date(t).toLocaleTimeString(LOCALE, { hour: '2-digit', minute: '2-digit' });
}

/** "just now", "12 min ago", "3 h ago", "2 days ago". */
export function ago(v: Instant | null | undefined, now: number = Date.now()): string {
  const t = toMillis(v);
  if (t === null) return 'never';
  const diff = Math.max(0, now - t);
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} h ago`;
  const days = Math.floor(diff / 86_400_000);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

/** "HH:MM" with 00 ≤ HH ≤ 23. */
export const isTime = (s: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(s);

export const timeToMinutes = (s: string) => {
  const [h, m] = s.split(':').map(Number);
  return h * 60 + m;
};

/** Only "#rrggbb" colours reach a style or an SVG attribute; anything else falls back to neutral. */
export function safeColor(c: string | null | undefined, fallback = 'var(--neutral-data)'): string {
  return typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c) ? c : fallback;
}
