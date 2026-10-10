import { describe, expect, it } from 'vitest';
import type { OpenInterval } from './intervals';
import {
  applyAck,
  isDue,
  markSent,
  RESEND_AFTER_MS,
  resetForNewPort,
  SEND_EVERY_MS,
  toSiteMessage,
  upsertEntry,
  type OutboxEntry,
} from './outbox';

const T0 = 1_760_090_000_000;

function iv(over: Partial<OpenInterval> = {}): OpenInterval {
  return {
    id: 'id-1',
    rev: 1,
    site: 'youtube',
    start: T0,
    end: T0 + 10_000,
    playing: true,
    focused: true,
    title: 'A video title',
    pageKind: null,
    tz: 'Europe/Lisbon',
    tzOffsetMin: 60,
    ...over,
  };
}

describe('toSiteMessage', () => {
  it('matches the contract example for YouTube', () => {
    expect(toSiteMessage(iv({ rev: 3, end: T0 + 1_200_000, focused: false }), 'chrome')).toEqual({
      t: 'site',
      id: 'id-1',
      rev: 3,
      site: 'youtube',
      browser: 'chrome',
      start: T0,
      end: T0 + 1_200_000,
      playing: true,
      focused: false,
      title: 'A video title',
      page_kind: null,
      tz: 'Europe/Lisbon',
      tz_offset_min: 60,
    });
  });

  it('never puts a title key on LinkedIn', () => {
    const msg = toSiteMessage(iv({ site: 'linkedin', title: 'leak', pageKind: 'jobs', playing: false }), 'brave');
    expect('title' in msg).toBe(false);
    expect(msg).toMatchObject({ site: 'linkedin', page_kind: 'jobs', browser: 'brave' });
  });
});

describe('outbox entries', () => {
  const entry = (over: Partial<OpenInterval> = {}, closed = false): OutboxEntry =>
    upsertEntry(undefined, toSiteMessage(iv(over), 'chrome'), closed)!;

  it('sends a new interval once it has length, then at most every 30 s while it grows', () => {
    expect(isDue(entry({ end: T0 }), T0)).toBe(false);
    let e = entry();
    expect(isDue(e, T0 + 10_000)).toBe(true);
    e = markSent(e, T0 + 10_000);
    e = upsertEntry(e, toSiteMessage(iv({ rev: 2, end: T0 + 15_000 }), 'chrome'), false)!;
    expect(isDue(e, T0 + 15_000)).toBe(false);
    expect(isDue(e, T0 + 10_000 + SEND_EVERY_MS)).toBe(true);
  });

  it('sends a closing revision at once', () => {
    let e = markSent(entry(), T0 + 10_000);
    e = upsertEntry(e, toSiteMessage(iv({ rev: 2, end: T0 + 12_000 }), 'chrome'), true)!;
    expect(isDue(e, T0 + 12_000)).toBe(true);
  });

  it('drops a zero-length interval that closed before it was sent', () => {
    expect(upsertEntry(undefined, toSiteMessage(iv({ end: T0 }), 'chrome'), true)).toBeNull();
  });

  it('ignores an older revision', () => {
    const e = entry({ rev: 5 });
    expect(upsertEntry(e, toSiteMessage(iv({ rev: 4 }), 'chrome'), false)).toBe(e);
  });

  it('drops the entry only when its newest revision is acknowledged', () => {
    let e = markSent(entry(), T0);
    e = upsertEntry(e, toSiteMessage(iv({ rev: 2, end: T0 + 20_000 }), 'chrome'), true)!;
    e = markSent(e, T0 + 20_000);
    expect(e.inflight).toEqual([1, 2]);
    const afterFirst = applyAck(e);
    expect(afterFirst).not.toBeNull();
    expect(afterFirst!.ackedRev).toBe(1);
    expect(applyAck(afterFirst!)).toBeNull();
  });

  it('resends a revision that was never acknowledged', () => {
    const e = markSent(entry(), T0);
    expect(isDue(e, T0 + RESEND_AFTER_MS - 1)).toBe(false);
    expect(isDue(e, T0 + RESEND_AFTER_MS)).toBe(true);
  });

  it('a new port resends everything', () => {
    const e = resetForNewPort(markSent(entry(), T0));
    expect(e.sentRev).toBe(0);
    expect(e.inflight).toEqual([]);
    expect(isDue(e, T0)).toBe(true);
  });
});
