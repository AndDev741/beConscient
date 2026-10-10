import { describe, expect, it } from 'vitest';
import {
  anyPlaying,
  applyPageReport,
  applyTabActivated,
  applyWindowFocus,
  applyWindowRemoved,
  emptyTracker,
  STALE_MS,
  WINDOW_ID_NONE,
  type TrackerEnv,
  type TrackerState,
} from './intervals';
import type { PageState } from './types';

const T0 = 1_760_090_000_000;
let n = 0;
const env: TrackerEnv = {
  newId: () => `id-${++n}`,
  zone: () => ({ tz: 'Europe/Lisbon', tzOffsetMin: 60 }),
};

function yt(over: Partial<PageState> = {}): PageState {
  return { site: 'youtube', visible: true, focused: true, playing: false, title: 'T', pageKind: null, ...over };
}

function report(st: TrackerState, at: number, page: PageState, tab = { tabId: 1, windowId: 1, active: true }) {
  return applyPageReport(st, { ...tab, sentAt: at, page }, at, env);
}

describe('counting rule', () => {
  const cases: Array<[string, { windowFocused: boolean; active: boolean; visible: boolean; playing: boolean }, boolean]> = [
    ['active, focused, visible', { windowFocused: true, active: true, visible: true, playing: false }, true],
    ['active, unfocused, no video', { windowFocused: false, active: true, visible: true, playing: false }, false],
    ['active, unfocused, video playing', { windowFocused: false, active: true, visible: true, playing: true }, true],
    ['active, minimized, video playing', { windowFocused: false, active: true, visible: false, playing: true }, true],
    ['background tab, video playing', { windowFocused: true, active: false, visible: false, playing: true }, false],
    ['background tab, no video', { windowFocused: true, active: false, visible: false, playing: false }, false],
  ];

  it.each(cases)('%s', (_name, c, counts) => {
    const st = emptyTracker();
    st.focusedWindowId = c.windowFocused ? 1 : WINDOW_ID_NONE;
    report(st, T0, yt({ visible: c.visible, focused: c.windowFocused && c.visible, playing: c.playing }), {
      tabId: 1,
      windowId: 1,
      active: c.active,
    });
    const changes = report(st, T0 + 5000, yt({ visible: c.visible, focused: c.windowFocused && c.visible, playing: c.playing }), {
      tabId: 1,
      windowId: 1,
      active: c.active,
    });
    expect(changes.length > 0).toBe(counts);
    if (counts) {
      expect(changes[0]!.interval).toMatchObject({ start: T0, end: T0 + 5000, rev: 2, playing: c.playing });
      expect(changes[0]!.interval.focused).toBe(c.windowFocused && c.visible);
    }
  });
});

describe('tracker events', () => {
  it('extends one interval with a growing rev while nothing changes', () => {
    const st = emptyTracker();
    st.focusedWindowId = 1;
    const revs: number[] = [];
    for (let t = 0; t <= 30_000; t += 5000) {
      for (const c of report(st, T0 + t, yt())) revs.push(c.interval.rev);
    }
    expect(revs).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(st.tabs['1']!.interval).toMatchObject({ start: T0, end: T0 + 30_000 });
  });

  it('focus and tab activation only split, never extend', () => {
    const st = emptyTracker();
    st.focusedWindowId = 1;
    report(st, T0, yt());
    report(st, T0 + 5000, yt());
    // Focus moves to another browser window: page report is still focused=true
    // until its blur arrives, so nothing changes yet.
    expect(applyWindowFocus(st, 2, T0 + 6000, env)).toEqual([]);
    expect(st.tabs['1']!.interval!.end).toBe(T0 + 5000);
    // Another tab becomes active in window 1: the interval closes now.
    const closed = applyTabActivated(st, 2, 1, T0 + 7000, env);
    expect(closed).toHaveLength(1);
    expect(closed[0]).toMatchObject({ closed: true, revised: true });
    expect(closed[0]!.interval.end).toBe(T0 + 7000);
    expect(st.tabs['1']!.interval).toBeUndefined();
  });

  it('closes a silent tab at its last report without a new rev', () => {
    const st = emptyTracker();
    st.focusedWindowId = 1;
    report(st, T0, yt());
    report(st, T0 + 5000, yt());
    const out = applyWindowFocus(st, 1, T0 + 5000 + STALE_MS + 1, env);
    expect(out).toEqual([
      expect.objectContaining({ closed: true, revised: false, interval: expect.objectContaining({ end: T0 + 5000, rev: 2 }) }),
    ]);
  });

  it('closing a window closes its tabs', () => {
    const st = emptyTracker();
    st.focusedWindowId = 1;
    report(st, T0, yt());
    report(st, T0 + 5000, yt());
    const out = applyWindowRemoved(st, 1, T0 + 6000, env);
    expect(out[0]).toMatchObject({ closed: true, interval: expect.objectContaining({ end: T0 + 6000 }) });
    expect(st.tabs).toEqual({});
    expect(st.focusedWindowId).toBe(WINDOW_ID_NONE);
  });

  it('anyPlaying looks at active tabs only', () => {
    const st = emptyTracker();
    report(st, T0, yt({ playing: true }), { tabId: 1, windowId: 1, active: false });
    expect(anyPlaying(st, T0)).toBe(false);
    report(st, T0 + 1000, yt({ playing: true }), { tabId: 1, windowId: 1, active: true });
    expect(anyPlaying(st, T0 + 1000)).toBe(true);
    expect(anyPlaying(st, T0 + 1000 + STALE_MS + 1)).toBe(false);
  });
});
