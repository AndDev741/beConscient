import { beforeEach, describe, expect, it } from 'vitest';
import { createChromeMock, LINKEDIN_ORIGIN, YOUTUBE_ORIGIN, type ChromeMock, type TabInfo } from '../test/chromeMock';
import { STALE_MS } from './intervals';
import type { ContentMessage, PageState } from './types';
import { BACKOFF_MAX_MS, HEARTBEAT_ALARM, HOST_NAME, startBackground, type BackgroundOptions } from './worker';

// 14:00 on a Tuesday in Lisbon (AE2).
const T0 = Date.UTC(2026, 9, 13, 13, 0, 0);
const SEC = 1000;
const MIN = 60 * SEC;

const SITE_KEYS_YOUTUBE = [
  't', 'id', 'rev', 'site', 'browser', 'start', 'end', 'playing', 'focused', 'title', 'page_kind', 'tz', 'tz_offset_min',
];
const SITE_KEYS_LINKEDIN = SITE_KEYS_YOUTUBE.filter((k) => k !== 'title');

let clock = T0;

function boot(mock: ChromeMock, opts: BackgroundOptions = {}) {
  return startBackground(mock.chrome, { now: () => clock, navigator: {}, ...opts });
}

function youtube(over: Partial<PageState> = {}): ContentMessage {
  return {
    kind: 'page',
    sentAt: clock,
    state: { site: 'youtube', visible: true, focused: true, playing: true, title: 'Big Buck Bunny', pageKind: null, ...over },
  };
}

function linkedin(over: Partial<PageState> = {}): ContentMessage {
  return {
    kind: 'page',
    sentAt: clock,
    state: { site: 'linkedin', visible: true, focused: true, playing: false, title: null, pageKind: 'feed', ...over },
  };
}

/** Sends a report every 5 seconds from `from` to `to` (inclusive), in ms after T0. */
async function reportEvery5s(
  mock: ChromeMock,
  bg: { idle(): Promise<void> },
  tab: TabInfo,
  from: number,
  to: number,
  make: () => ContentMessage,
  origin = YOUTUBE_ORIGIN,
) {
  for (let t = from; t <= to; t += 5 * SEC) {
    clock = T0 + t;
    mock.sendFromTab(tab, make(), origin);
    await bg.idle();
  }
}

function byId(messages: any[]): Map<string, any[]> {
  const out = new Map<string, any[]>();
  for (const m of messages) out.set(m.id, [...(out.get(m.id) ?? []), m]);
  return out;
}

function expectIncreasingRevs(messages: any[]) {
  for (let i = 1; i < messages.length; i++) expect(messages[i].rev).toBeGreaterThan(messages[i - 1].rev);
}

beforeEach(() => {
  clock = T0;
});

describe('counting', () => {
  it('YouTube active in a focused window for 2 minutes is one 2-minute interval with the title', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 10, windowId: 1, active: true };

    await reportEvery5s(mock, bg, tab, 0, 2 * MIN, () => youtube());
    mock.removeTab(10, 1);
    await bg.idle();

    const sites = mock.siteMessages();
    expect(sites.length).toBeGreaterThan(0);
    const ids = byId(sites);
    expect(ids.size).toBe(1);
    expectIncreasingRevs(sites);

    const last = sites.at(-1);
    expect(last.start).toBe(T0);
    expect(last.end - last.start).toBe(2 * MIN);
    expect(last).toMatchObject({
      t: 'site',
      site: 'youtube',
      browser: 'chrome',
      title: 'Big Buck Bunny',
      page_kind: null,
      playing: true,
      focused: true,
    });
    expect(Object.keys(last)).toEqual(SITE_KEYS_YOUTUBE);
    expect(last.tz).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(last.tz_offset_min).toBe(-new Date(T0).getTimezoneOffset() || 0);
    expect(last.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

    // Acknowledged by the host: nothing stays buffered.
    mock.openPort()!.hostSend({ t: 'ack', ids: sites.map((m) => m.id) });
    await bg.idle();
    expect(mock.outbox()).toEqual([]);
  });

  it('LinkedIn in the active tab of an unfocused window without video counts nothing', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: false } });
    const bg = boot(mock);
    await bg.idle();
    mock.focusWindow(-1);
    await bg.idle();

    const tab = { id: 20, windowId: 2, active: true };
    await reportEvery5s(mock, bg, tab, 0, 3 * MIN, () => linkedin({ focused: false }), LINKEDIN_ORIGIN);
    clock += 5 * MIN;
    mock.fireAlarm(HEARTBEAT_ALARM);
    await bg.idle();

    expect(mock.siteMessages()).toEqual([]);
    expect(mock.outbox()).toEqual([]);
  });

  it('AE2: a video playing in the active tab of an unfocused window counts with playing=true, focused=false', async () => {
    const mock = createChromeMock();
    const bg = boot(mock);
    await bg.idle();
    mock.focusWindow(-1); // the user is in another application
    await bg.idle();

    const tab = { id: 30, windowId: 3, active: true };
    await reportEvery5s(mock, bg, tab, 0, 20 * MIN, () => youtube({ focused: false }));
    mock.removeTab(30, 3);
    await bg.idle();

    const sites = mock.siteMessages();
    expect(byId(sites).size).toBe(1);
    const last = sites.at(-1);
    expect(last).toMatchObject({ site: 'youtube', playing: true, focused: false, title: 'Big Buck Bunny' });
    expect(last.end - last.start).toBe(20 * MIN);

    const playing = mock.delivered().filter((m) => m.t === 'playing');
    expect(playing.map((m) => m.playing)).toEqual([false, true, false]); // state on connect, start, stop
    expect(playing[1].at).toBe(T0);
    expect(playing[2].at).toBe(T0 + 20 * MIN);
  });

  it('a video playing in a background tab does not count', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    await reportEvery5s(mock, bg, { id: 31, windowId: 1, active: false }, 0, MIN, () =>
      youtube({ visible: false, focused: false }),
    );
    expect(mock.outbox()).toEqual([]);
    expect(mock.siteMessages()).toEqual([]);
  });

  it('a LinkedIn profile path sends page_kind "profile" with no title and no URL', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 40, windowId: 1, active: true };
    // Even if a page tried to pass a title or a URL, the background drops them.
    const make = () => {
      const msg = linkedin({ pageKind: 'profile' }) as any;
      msg.state.title = 'Jane Doe | LinkedIn';
      msg.state.url = 'https://www.linkedin.com/in/jane-doe/';
      msg.url = 'https://www.linkedin.com/in/jane-doe/';
      return msg as ContentMessage;
    };
    await reportEvery5s(mock, bg, tab, 0, MIN, make, LINKEDIN_ORIGIN);
    mock.removeTab(40, 1);
    await bg.idle();

    const sites = mock.siteMessages();
    expect(sites.length).toBeGreaterThan(0);
    for (const m of sites) {
      expect(m.site).toBe('linkedin');
      expect(m.page_kind).toBe('profile');
      expect('title' in m).toBe(false);
      expect(Object.keys(m)).toEqual(SITE_KEYS_LINKEDIN);
      const json = JSON.stringify(m);
      expect(json).not.toMatch(/jane|linkedin\.com|https?:|\/in\//i);
    }
    expect(sites.at(-1).end - sites.at(-1).start).toBe(MIN);
  });

  it('starts a new interval when the title, playing or focus changes', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 50, windowId: 1, active: true };

    await reportEvery5s(mock, bg, tab, 0, 30 * SEC, () => youtube({ title: 'First' }));
    await reportEvery5s(mock, bg, tab, 35 * SEC, 60 * SEC, () => youtube({ title: 'Second' }));
    await reportEvery5s(mock, bg, tab, 65 * SEC, 90 * SEC, () => youtube({ title: 'Second', playing: false }));
    // The user switches to another application: the browser reports the
    // focus change and the page reports its blur. A paused video in an
    // unfocused window does not count.
    clock = T0 + 90 * SEC;
    mock.focusWindow(-1);
    mock.sendFromTab(tab, youtube({ title: 'Second', playing: false, focused: false }));
    await bg.idle();

    const ids = byId(mock.siteMessages());
    const finals = [...ids.values()].map((list) => list.at(-1));
    expect(finals.map((m) => [m.title, m.playing, m.focused, (m.start - T0) / SEC, (m.end - T0) / SEC])).toEqual([
      ['First', true, true, 0, 35],
      ['Second', true, true, 35, 65],
      ['Second', false, true, 65, 90],
    ]);
  });

  it('losing window focus while a video plays splits into focused and unfocused intervals', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 51, windowId: 1, active: true };

    await reportEvery5s(mock, bg, tab, 0, 30 * SEC, () => youtube());
    clock = T0 + 32 * SEC;
    mock.focusWindow(-1);
    mock.sendFromTab(tab, youtube({ focused: false })); // the page's blur report
    await bg.idle();
    await reportEvery5s(mock, bg, tab, 35 * SEC, 60 * SEC, () => youtube({ focused: false }));
    mock.removeTab(51, 1);
    await bg.idle();

    const finals = [...byId(mock.siteMessages()).values()].map((l) => l.at(-1));
    expect(finals.map((m) => [m.focused, m.playing, (m.start - T0) / SEC, (m.end - T0) / SEC])).toEqual([
      [true, true, 0, 32],
      [false, true, 32, 60],
    ]);
  });

  it('focus in the address bar still counts as a focused window', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 52, windowId: 1, active: true };
    // document.hasFocus() is false while the address bar has focus, but the
    // browser window is still the focused one.
    await reportEvery5s(mock, bg, tab, 0, 30 * SEC, () => youtube({ focused: false, playing: false }));
    mock.removeTab(52, 1);
    await bg.idle();
    const finals = [...byId(mock.siteMessages()).values()].map((l) => l.at(-1));
    expect(finals).toHaveLength(1);
    expect(finals[0]).toMatchObject({ focused: true, playing: false });
    expect(finals[0].end - finals[0].start).toBe(30 * SEC);
  });

  it('switching to another tab ends the interval, and a late report from the old tab does not revive it', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 60, windowId: 1, active: true };

    await reportEvery5s(mock, bg, tab, 0, 20 * SEC, () => youtube());
    clock = T0 + 22 * SEC;
    const late = youtube({ title: 'Big Buck Bunny' });
    (late as { sentAt: number }).sentAt = T0 + 21 * SEC; // sent before the switch, delivered after
    mock.activateTab(61, 1);
    await bg.idle();
    clock = T0 + 22.5 * SEC;
    mock.sendFromTab(tab, late);
    await bg.idle();
    clock = T0 + 23 * SEC;
    mock.sendFromTab({ ...tab, active: false }, youtube({ visible: false, focused: false, playing: false }));
    await bg.idle();
    clock = T0 + 5 * MIN;
    mock.fireAlarm(HEARTBEAT_ALARM);
    await bg.idle();

    const ids = byId(mock.siteMessages());
    expect(ids.size).toBe(1);
    const last = [...ids.values()][0]!.at(-1);
    expect((last.end - last.start) / SEC).toBe(22);
  });

  it('a tab that goes silent is closed at its last report', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 70, windowId: 1, active: true };
    await reportEvery5s(mock, bg, tab, 0, 40 * SEC, () => youtube());

    clock = T0 + 40 * SEC + STALE_MS + 1;
    mock.fireAlarm(HEARTBEAT_ALARM);
    await bg.idle();
    // The page comes back later: a new interval, not one stretched over the gap.
    await reportEvery5s(mock, bg, tab, 2 * MIN, 2 * MIN + 10 * SEC, () => youtube());
    mock.removeTab(70, 1);
    await bg.idle();

    const finals = [...byId(mock.siteMessages()).values()].map((l) => l.at(-1));
    expect(finals.map((m) => [(m.start - T0) / SEC, (m.end - T0) / SEC])).toEqual([
      [0, 40],
      [120, 130],
    ]);
  });

  it('ignores messages from other origins and from subframes', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 80, windowId: 1, active: true };
    await reportEvery5s(mock, bg, tab, 0, 30 * SEC, () => youtube(), 'https://example.com');
    mock.api.runtime.onMessage.emit(youtube(), { id: 'hhddmdmnpejagnnmenjdgdifpimneeok', frameId: 3, origin: YOUTUBE_ORIGIN, tab: { ...tab } }, () => undefined);
    await bg.idle();
    expect(mock.tracker().tabs).toEqual({});
    expect(mock.outbox()).toEqual([]);
  });
});

describe('native messaging', () => {
  it('connects to org.beconscient.agent and sends a heartbeat on connect', async () => {
    const mock = createChromeMock();
    const bg = boot(mock);
    await bg.idle();
    expect(mock.state.ports[0]!.name).toBe(HOST_NAME);
    expect(mock.delivered()[0]).toEqual({ t: 'heartbeat', browser: 'chrome', version: '0.1.0', at: T0 });
    expect(mock.local.data.get('status')).toMatchObject({ connected: true, lastHeartbeatAt: T0 });
  });

  it('buffers intervals while the port is closed, sends them after reconnecting and drops them after the ack', async () => {
    const mock = createChromeMock({ hostAvailable: false, lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 90, windowId: 1, active: true };

    await reportEvery5s(mock, bg, tab, 0, MIN, () => youtube());
    mock.removeTab(90, 1);
    await bg.idle();

    expect(mock.siteMessages()).toEqual([]);
    const buffered = mock.outbox();
    expect(buffered).toHaveLength(1);
    expect(buffered[0].closed).toBe(true);
    expect(buffered[0].msg.end - buffered[0].msg.start).toBe(MIN);
    const status = mock.local.data.get('status') as any;
    expect(status.connected).toBe(false);
    expect(status.lastError).toMatch(/not found/);
    expect(mock.state.ports.length).toBeGreaterThan(1); // it kept retrying, with backoff
    expect(mock.state.ports.length).toBeLessThan(13); // not once per report

    // The agent gets installed; the next alarm reconnects and resends.
    mock.state.hostAvailable = true;
    clock = T0 + MIN + BACKOFF_MAX_MS;
    mock.fireAlarm(HEARTBEAT_ALARM);
    await bg.idle();

    const sites = mock.siteMessages();
    expect(sites).toHaveLength(1);
    expect(sites[0]).toEqual(buffered[0].msg);
    expect(mock.outbox()).toHaveLength(1);

    mock.openPort()!.hostSend({ t: 'ack', ids: [sites[0].id] });
    await bg.idle();
    expect(mock.outbox()).toEqual([]);
    expect(mock.local.data.get('status')).toMatchObject({ connected: true, lastAckAt: clock });
  });

  it('resends unacknowledged intervals when the port drops and comes back', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 91, windowId: 1, active: true };
    await reportEvery5s(mock, bg, tab, 0, 30 * SEC, () => youtube());
    const first = mock.openPort()!;
    expect(first.delivered.some((m) => m.t === 'site')).toBe(true);

    first.hostClose(); // the host process exits before acking
    await bg.idle();
    await reportEvery5s(mock, bg, tab, 35 * SEC, 40 * SEC, () => youtube());
    const second = mock.openPort()!;
    expect(second).not.toBe(first);
    const resent = second.delivered.filter((m) => m.t === 'site');
    expect(resent.length).toBeGreaterThan(0);
    expect(resent.at(-1).end).toBe(T0 + 35 * SEC);
  });

  it('an ack for an older revision keeps the newer one buffered', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock);
    await bg.idle();
    const tab = { id: 92, windowId: 1, active: true };
    await reportEvery5s(mock, bg, tab, 0, 10 * SEC, () => youtube());
    const port = mock.openPort()!;
    const sent = port.delivered.filter((m) => m.t === 'site');
    expect(sent).toHaveLength(1);

    mock.removeTab(92, 1); // closes with a newer rev, sent at once
    await bg.idle();
    expect(port.delivered.filter((m) => m.t === 'site')).toHaveLength(2);

    port.hostSend({ t: 'ack', ids: [sent[0].id] }); // for the first send
    await bg.idle();
    expect(mock.outbox()).toHaveLength(1);
    port.hostSend({ t: 'ack', ids: [sent[0].id] }); // for the closing send
    await bg.idle();
    expect(mock.outbox()).toEqual([]);
  });

  it('creates the heartbeat alarm every 5 minutes and sends a heartbeat when it fires', async () => {
    const mock = createChromeMock();
    const bg = boot(mock);
    await bg.idle();
    expect(mock.state.alarmCreates).toEqual([{ name: HEARTBEAT_ALARM, periodInMinutes: 5, delayInMinutes: 5 }]);

    for (let i = 1; i <= 3; i++) {
      clock = T0 + i * 5 * MIN;
      mock.fireAlarm(HEARTBEAT_ALARM);
      await bg.idle();
    }
    const beats = mock.delivered().filter((m) => m.t === 'heartbeat');
    expect(beats.map((m) => (m.at - T0) / MIN)).toEqual([0, 5, 10, 15]);
    expect(beats.at(-1)).toEqual({ t: 'heartbeat', browser: 'chrome', version: '0.1.0', at: T0 + 15 * MIN });
    expect(mock.local.data.get('status')).toMatchObject({ lastHeartbeatAt: T0 + 15 * MIN });

    // A restarted worker keeps the existing schedule.
    mock.restartWorker();
    const bg2 = boot(mock);
    await bg2.idle();
    expect(mock.state.alarmCreates).toHaveLength(1);
  });

  it('reports brave when navigator.brave exists', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg = boot(mock, { navigator: { brave: { isBrave: async () => true } } });
    await bg.idle();
    await reportEvery5s(mock, bg, { id: 93, windowId: 1, active: true }, 0, 10 * SEC, () => youtube());
    expect(mock.delivered()[0]).toMatchObject({ t: 'heartbeat', browser: 'brave' });
    expect(mock.siteMessages()[0]).toMatchObject({ browser: 'brave' });
  });
});

describe('service worker restart', () => {
  it('continues the open interval from stored state with a higher rev', async () => {
    const mock = createChromeMock({ lastFocused: { id: 1, focused: true } });
    const bg1 = boot(mock);
    await bg1.idle();
    const tab = { id: 100, windowId: 1, active: true };
    await reportEvery5s(mock, bg1, tab, 0, 30 * SEC, () => youtube());
    const before = mock.tracker().tabs['100'].interval;
    expect(before.end).toBe(T0 + 30 * SEC);

    mock.restartWorker();
    clock = T0 + 32 * SEC;
    const bg2 = boot(mock);
    await bg2.idle();
    await reportEvery5s(mock, bg2, tab, 35 * SEC, 70 * SEC, () => youtube());

    const after = mock.tracker().tabs['100'].interval;
    expect(after.id).toBe(before.id);
    expect(after.start).toBe(before.start);
    expect(after.rev).toBeGreaterThan(before.rev);
    expect(after.end).toBe(T0 + 70 * SEC);

    // The new worker's port carries the same interval with a higher rev.
    const newPort = mock.openPort()!;
    const resent = newPort.delivered.filter((m) => m.t === 'site');
    expect(resent.length).toBeGreaterThan(0);
    expect(resent.every((m) => m.id === before.id)).toBe(true);
    expect(resent.at(-1).rev).toBeGreaterThan(before.rev);
  });
});
