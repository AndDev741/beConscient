// Pure interval tracking. The background loads a TrackerState from
// chrome.storage, applies one event with the functions below, and stores it
// back. Nothing here touches chrome.* or keeps module state.
//
// Counting rule (KTD14, docs/design.md "Sites"): a tab counts while it is
// the active tab of its window and either its window has focus (and the
// page is visible) or a video is playing in it. A new interval starts when
// site, title, page kind, playing or focused changes.

import type { PageKind, PageState, Site } from './types';

/** A tab whose content script has been silent this long is treated as gone. */
export const STALE_MS = 20_000;
/** Tab records with no interval and no report for this long are pruned. */
export const FORGET_MS = 60 * 60_000;
/** chrome.windows.WINDOW_ID_NONE */
export const WINDOW_ID_NONE = -1;

export interface OpenInterval {
  id: string;
  rev: number;
  site: Site;
  start: number;
  end: number;
  playing: boolean;
  focused: boolean;
  title: string | null;
  pageKind: PageKind | null;
  tz: string;
  tzOffsetMin: number;
}

export interface TabRecord {
  windowId: number;
  page: PageState;
  /** Background time of the last report from this tab. */
  seenAt: number;
  interval?: OpenInterval;
}

export interface TrackerState {
  /** null = unknown yet, WINDOW_ID_NONE = no browser window has focus. */
  focusedWindowId: number | null;
  /** windowId -> active tab and when that was learned. */
  activeTabs: Record<string, { tabId: number; at: number }>;
  /** tabId -> what its content script last reported. */
  tabs: Record<string, TabRecord>;
  /** Last aggregate playing state reported to the agent. */
  playing: boolean;
}

/** One revision of an interval to hand to the outbox. */
export interface IntervalChange {
  interval: OpenInterval;
  closed: boolean;
  /** The rev grew. False when an interval closes at the end it already had. */
  revised: boolean;
}

export interface TrackerEnv {
  newId(now: number): string;
  zone(at: number): { tz: string; tzOffsetMin: number };
}

export function emptyTracker(): TrackerState {
  return { focusedWindowId: null, activeTabs: {}, tabs: {}, playing: false };
}

interface Observation {
  site: Site;
  playing: boolean;
  focused: boolean;
  title: string | null;
  pageKind: PageKind | null;
}

function isActive(st: TrackerState, tabId: number, r: TabRecord): boolean {
  return st.activeTabs[String(r.windowId)]?.tabId === tabId;
}

/** The window holding this tab has focus. */
function windowFocused(st: TrackerState, r: TabRecord): boolean {
  // The page's own document.hasFocus() is a sure sign of focus. The
  // background's view covers moments when focus sits in the browser UI
  // (address bar, find bar) and the page reports false.
  return r.page.focused || (st.focusedWindowId !== null && st.focusedWindowId === r.windowId);
}

/** What should be counted for this tab right now, or null for nothing. */
function observe(st: TrackerState, tabId: number, r: TabRecord, now: number): Observation | null {
  if (now - r.seenAt > STALE_MS) return null;
  if (!isActive(st, tabId, r)) return null;
  const focused = windowFocused(st, r) && r.page.visible;
  const playing = r.page.playing;
  if (!focused && !playing) return null;
  return {
    site: r.page.site,
    playing,
    focused,
    title: r.page.site === 'youtube' ? r.page.title : null,
    pageKind: r.page.site === 'linkedin' ? (r.page.pageKind ?? 'other') : null,
  };
}

function sameKey(iv: OpenInterval, o: Observation): boolean {
  return (
    iv.site === o.site &&
    iv.playing === o.playing &&
    iv.focused === o.focused &&
    iv.title === o.title &&
    iv.pageKind === o.pageKind
  );
}

function closeInterval(iv: OpenInterval, end: number): IntervalChange {
  const revised = end > iv.end;
  if (revised) {
    iv.end = end;
    iv.rev += 1;
  }
  return { interval: { ...iv }, closed: true, revised };
}

/**
 * Re-evaluates one tab at `now`. `own` is true when the event is a report
 * from this tab; only such reports extend an interval's end. Other events
 * (focus, tab switch) can only split or close it.
 */
export function evaluateTab(
  st: TrackerState,
  tabId: number,
  now: number,
  own: boolean,
  env: TrackerEnv,
): IntervalChange[] {
  const r = st.tabs[String(tabId)];
  if (!r) return [];
  const out: IntervalChange[] = [];
  const fresh = now - r.seenAt <= STALE_MS;
  const obs = observe(st, tabId, r, now);
  const iv = r.interval;

  if (iv) {
    if (!fresh) {
      // The content script went quiet: end at the last confirmed moment.
      out.push(closeInterval(iv, iv.end));
      delete r.interval;
    } else if (obs && sameKey(iv, obs)) {
      if (own && now > iv.end) {
        iv.end = now;
        iv.rev += 1;
        out.push({ interval: { ...iv }, closed: false, revised: true });
      }
      return out;
    } else {
      out.push(closeInterval(iv, now));
      delete r.interval;
    }
  }

  if (obs) {
    const z = env.zone(now);
    const next: OpenInterval = {
      id: env.newId(now),
      rev: 1,
      site: obs.site,
      start: now,
      end: now,
      playing: obs.playing,
      focused: obs.focused,
      title: obs.title,
      pageKind: obs.pageKind,
      tz: z.tz,
      tzOffsetMin: z.tzOffsetMin,
    };
    r.interval = next;
    out.push({ interval: { ...next }, closed: false, revised: true });
  }
  return out;
}

/** Re-evaluates every known tab; `ownTabId` marks the one that reported. */
export function evaluateAll(
  st: TrackerState,
  now: number,
  env: TrackerEnv,
  ownTabId: number | null = null,
): IntervalChange[] {
  const out: IntervalChange[] = [];
  for (const key of Object.keys(st.tabs)) {
    const tabId = Number(key);
    out.push(...evaluateTab(st, tabId, now, tabId === ownTabId, env));
    const r = st.tabs[key];
    if (r && !r.interval && now - r.seenAt > FORGET_MS) delete st.tabs[key];
  }
  return out;
}

export interface PageReport {
  tabId: number;
  windowId: number;
  /** sender.tab.active when the message was sent. */
  active: boolean;
  /** Time the content script sent the report (same clock as the background). */
  sentAt: number;
  page: PageState;
}

/** A content script reported its page state. */
export function applyPageReport(
  st: TrackerState,
  rep: PageReport,
  now: number,
  env: TrackerEnv,
): IntervalChange[] {
  const key = String(rep.tabId);
  const prev = st.tabs[key];
  const out: IntervalChange[] = [];
  let interval = prev?.interval;
  if (prev && interval && now - prev.seenAt > STALE_MS) {
    // The tab was silent for a while: never stretch an interval over the gap.
    out.push(closeInterval(interval, interval.end));
    interval = undefined;
  }
  const record: TabRecord = { windowId: rep.windowId, page: rep.page, seenAt: now };
  if (interval) record.interval = interval;
  st.tabs[key] = record;

  // tabs.onActivated is the authority on which tab is active. A report sent
  // before the latest activation in its window must not undo it.
  const w = String(rep.windowId);
  const cur = st.activeTabs[w];
  if (!cur || rep.sentAt >= cur.at) {
    if (rep.active) st.activeTabs[w] = { tabId: rep.tabId, at: rep.sentAt };
    else if (cur?.tabId === rep.tabId) delete st.activeTabs[w];
  }
  // A tab dragged to another window is no longer active in the old one.
  if (prev && prev.windowId !== rep.windowId) {
    const old = st.activeTabs[String(prev.windowId)];
    if (old?.tabId === rep.tabId) delete st.activeTabs[String(prev.windowId)];
  }
  return out.concat(evaluateAll(st, now, env, rep.tabId));
}

/** chrome.tabs.onActivated */
export function applyTabActivated(
  st: TrackerState,
  tabId: number,
  windowId: number,
  now: number,
  env: TrackerEnv,
): IntervalChange[] {
  st.activeTabs[String(windowId)] = { tabId, at: now };
  return evaluateAll(st, now, env);
}

/** chrome.windows.onFocusChanged; WINDOW_ID_NONE when no browser window has focus. */
export function applyWindowFocus(
  st: TrackerState,
  windowId: number,
  now: number,
  env: TrackerEnv,
): IntervalChange[] {
  st.focusedWindowId = windowId;
  return evaluateAll(st, now, env);
}

function closeTab(st: TrackerState, key: string, now: number): IntervalChange[] {
  const r = st.tabs[key];
  if (!r) return [];
  const out: IntervalChange[] = [];
  if (r.interval) {
    const fresh = now - r.seenAt <= STALE_MS;
    out.push(closeInterval(r.interval, fresh ? now : r.interval.end));
  }
  delete st.tabs[key];
  return out;
}

/** chrome.tabs.onRemoved, or the page said it is going away. */
export function applyTabGone(
  st: TrackerState,
  tabId: number,
  now: number,
  env: TrackerEnv,
): IntervalChange[] {
  const out = closeTab(st, String(tabId), now);
  for (const [w, a] of Object.entries(st.activeTabs)) {
    if (a.tabId === tabId) delete st.activeTabs[w];
  }
  return out.concat(evaluateAll(st, now, env));
}

/** chrome.windows.onRemoved */
export function applyWindowRemoved(
  st: TrackerState,
  windowId: number,
  now: number,
  env: TrackerEnv,
): IntervalChange[] {
  const out: IntervalChange[] = [];
  for (const [key, r] of Object.entries(st.tabs)) {
    if (r.windowId === windowId) out.push(...closeTab(st, key, now));
  }
  delete st.activeTabs[String(windowId)];
  if (st.focusedWindowId === windowId) st.focusedWindowId = WINDOW_ID_NONE;
  return out.concat(evaluateAll(st, now, env));
}

/** A video is playing in the active tab of some window. */
export function anyPlaying(st: TrackerState, now: number): boolean {
  return Object.entries(st.tabs).some(
    ([key, r]) => r.page.playing && now - r.seenAt <= STALE_MS && isActive(st, Number(key), r),
  );
}
