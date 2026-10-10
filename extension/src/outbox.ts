// Buffer of site intervals not yet acknowledged by the native host. Each
// interval id is one entry holding its newest revision. Entries live in
// chrome.storage.local under "ob:<id>" so they survive worker and browser
// restarts. The functions here are pure; the background does the storage.

import type { OpenInterval } from './intervals';
import type { BrowserName, SiteMessage } from './types';

export const OUTBOX_PREFIX = 'ob:';
/** A growing interval is sent at most this often; open and close go out at once. */
export const SEND_EVERY_MS = 30_000;
/** Sent but never acknowledged for this long: send again. */
export const RESEND_AFTER_MS = 5 * 60_000;
/** Cap on buffered intervals while the agent is unreachable. Oldest go first. */
export const OUTBOX_MAX = 5_000;

export interface OutboxEntry {
  msg: SiteMessage;
  closed: boolean;
  /** Highest rev posted on the current port; 0 when not posted on it yet. */
  sentRev: number;
  sentAt: number;
  /** Revs posted on the current port and not yet acknowledged, oldest first. */
  inflight: number[];
  ackedRev: number;
}

export function outboxKey(id: string): string {
  return OUTBOX_PREFIX + id;
}

/** Wire message for one interval revision. LinkedIn messages carry no title key. */
export function toSiteMessage(iv: OpenInterval, browser: BrowserName): SiteMessage {
  const msg: SiteMessage = {
    t: 'site',
    id: iv.id,
    rev: iv.rev,
    site: iv.site,
    browser,
    start: iv.start,
    end: iv.end,
    playing: iv.playing,
    focused: iv.focused,
    page_kind: null,
    tz: iv.tz,
    tz_offset_min: iv.tzOffsetMin,
  };
  if (iv.site === 'youtube') {
    // Keep the contract's field order: title before page_kind.
    return {
      t: msg.t,
      id: msg.id,
      rev: msg.rev,
      site: msg.site,
      browser: msg.browser,
      start: msg.start,
      end: msg.end,
      playing: msg.playing,
      focused: msg.focused,
      title: iv.title,
      page_kind: null,
      tz: msg.tz,
      tz_offset_min: msg.tz_offset_min,
    };
  }
  msg.page_kind = iv.pageKind ?? 'other';
  return msg;
}

/**
 * Merges a new revision into the entry. Returns null when the entry should
 * be removed: a zero-length interval that closed before it was ever sent.
 */
export function upsertEntry(
  prev: OutboxEntry | undefined,
  msg: SiteMessage,
  closed: boolean,
): OutboxEntry | null {
  if (closed && msg.end <= msg.start && (!prev || prev.sentRev === 0)) return null;
  if (prev && msg.rev < prev.msg.rev) return prev;
  return {
    msg,
    closed: closed || (prev?.closed ?? false),
    sentRev: prev?.sentRev ?? 0,
    sentAt: prev?.sentAt ?? 0,
    inflight: prev?.inflight ?? [],
    ackedRev: prev?.ackedRev ?? 0,
  };
}

/** Whether the entry should be posted now on an open port. */
export function isDue(e: OutboxEntry, now: number): boolean {
  if (e.msg.end <= e.msg.start) return false; // nothing measured yet
  if (e.sentRev === 0) return true;
  if (e.msg.rev > e.sentRev) return e.closed || now - e.sentAt >= SEND_EVERY_MS;
  return e.ackedRev < e.msg.rev && now - e.sentAt >= RESEND_AFTER_MS;
}

export function markSent(e: OutboxEntry, now: number): OutboxEntry {
  return {
    ...e,
    sentRev: e.msg.rev,
    sentAt: now,
    inflight: [...e.inflight, e.msg.rev],
  };
}

/**
 * Applies one acknowledgement of this id. The host acknowledges in the
 * order it received, so the ack belongs to the oldest in-flight rev.
 * Returns null when the newest revision is acknowledged and the entry can
 * be dropped.
 */
export function applyAck(e: OutboxEntry): OutboxEntry | null {
  const inflight = [...e.inflight];
  const rev = inflight.shift();
  if (rev === undefined) return e;
  const ackedRev = Math.max(e.ackedRev, rev);
  if (ackedRev >= e.msg.rev) return null;
  return { ...e, inflight, ackedRev };
}

/** A new port knows nothing of what earlier ports carried. */
export function resetForNewPort(e: OutboxEntry): OutboxEntry {
  return { ...e, sentRev: 0, sentAt: 0, inflight: [] };
}
