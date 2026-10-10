// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContentMessage } from '../types';
import { createLinkedinReader } from './linkedinPage';
import { HEARTBEAT_MS, startReporter, type Reporter } from './reporter';
import { createYoutubeReader, readPlaying, SETTLE_MS, watchYoutube } from './youtubePage';

let visibility: DocumentVisibilityState = 'visible';
let focus = true;
let sent: ContentMessage[] = [];
let reporter: Reporter | null = null;

const send = (m: ContentMessage): boolean => {
  sent.push(JSON.parse(JSON.stringify(m)));
  return true;
};

function pages() {
  return sent.filter((m): m is Extract<ContentMessage, { kind: 'page' }> => m.kind === 'page').map((m) => m.state);
}

function setVisibility(v: DocumentVisibilityState) {
  visibility = v;
  document.dispatchEvent(new Event('visibilitychange'));
}

function addVideo(opts: { paused: boolean; readyState?: number; parent?: Element }) {
  const video = document.createElement('video');
  const s = { paused: opts.paused, ended: false, readyState: opts.readyState ?? 4 };
  Object.defineProperties(video, {
    paused: { configurable: true, get: () => s.paused },
    ended: { configurable: true, get: () => s.ended },
    readyState: { configurable: true, get: () => s.readyState },
  });
  (opts.parent ?? document.body).appendChild(video);
  return { video, s };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-13T13:00:00Z'));
  visibility = 'visible';
  focus = true;
  sent = [];
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility });
  vi.spyOn(document, 'hasFocus').mockImplementation(() => focus);
  document.head.innerHTML = '<title></title>';
  document.body.innerHTML = '';
});

afterEach(() => {
  reporter?.stop();
  reporter = null;
  vi.restoreAllMocks();
  vi.useRealTimers();
  history.replaceState(null, '', '/');
});

describe('LinkedIn content script', () => {
  it('sends the page kind of a profile and never its title or URL', () => {
    history.replaceState(null, '', '/in/jane-doe/');
    document.title = 'Jane Doe | LinkedIn';
    reporter = startReporter({ doc: document, win: window, read: createLinkedinReader(document, location), send });

    expect(pages()[0]).toEqual({
      site: 'linkedin',
      visible: true,
      focused: true,
      playing: false,
      title: null,
      pageKind: 'profile',
    });
    const json = JSON.stringify(sent);
    expect(json).not.toMatch(/jane|linkedin\.com|https?:|\/in\//i);
  });

  it('follows in-page navigation to another page kind', () => {
    history.replaceState(null, '', '/feed/');
    reporter = startReporter({ doc: document, win: window, read: createLinkedinReader(document, location), send });
    expect(pages().at(-1)!.pageKind).toBe('feed');

    history.pushState(null, '', '/messaging/thread/1/');
    vi.advanceTimersByTime(1000);
    expect(pages().at(-1)!.pageKind).toBe('messaging');

    history.pushState(null, '', '/jobs/view/1/');
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(pages().at(-1)!.pageKind).toBe('jobs');
  });

  it('heartbeats every 5 seconds while visible and stops once hidden', () => {
    history.replaceState(null, '', '/feed/');
    reporter = startReporter({ doc: document, win: window, read: createLinkedinReader(document, location), send });
    vi.advanceTimersByTime(HEARTBEAT_MS * 6);
    expect(pages()).toHaveLength(7);

    setVisibility('hidden');
    expect(pages().at(-1)!.visible).toBe(false);
    const count = sent.length;
    vi.advanceTimersByTime(HEARTBEAT_MS * 6);
    expect(sent).toHaveLength(count);

    setVisibility('visible');
    expect(pages().at(-1)!.visible).toBe(true);
  });

  it('reports window focus and blur at once', () => {
    history.replaceState(null, '', '/feed/');
    reporter = startReporter({ doc: document, win: window, read: createLinkedinReader(document, location), send });
    focus = false;
    window.dispatchEvent(new Event('blur'));
    expect(pages().at(-1)!.focused).toBe(false);
    focus = true;
    window.dispatchEvent(new Event('focus'));
    expect(pages().at(-1)!.focused).toBe(true);
  });

  it('says goodbye on pagehide and stops when the extension is gone', () => {
    history.replaceState(null, '', '/feed/');
    let alive = true;
    reporter = startReporter({
      doc: document,
      win: window,
      read: createLinkedinReader(document, location),
      send: (m) => (alive ? send(m) : false),
    });
    window.dispatchEvent(new Event('pagehide'));
    expect(sent.at(-1)!.kind).toBe('gone');

    alive = false;
    window.dispatchEvent(new Event('pageshow')); // send fails: the reporter stops
    const count = sent.length;
    alive = true;
    vi.advanceTimersByTime(HEARTBEAT_MS * 3);
    window.dispatchEvent(new Event('focus'));
    expect(sent).toHaveLength(count);
  });
});

describe('YouTube content script', () => {
  function startYoutube() {
    reporter = startReporter({
      doc: document,
      win: window,
      read: createYoutubeReader(document, location, () => Date.now()),
      send,
      watch: (check) => watchYoutube(document, check),
    });
  }

  it('sends the cleaned video title and no URL or video id', () => {
    history.replaceState(null, '', '/watch?v=dQw4w9WgXcQ&t=42');
    document.title = '(2) Big Buck Bunny - YouTube';
    startYoutube();
    expect(pages()[0]).toMatchObject({ site: 'youtube', title: 'Big Buck Bunny', pageKind: null });
    expect(JSON.stringify(sent)).not.toMatch(/dQw4w9WgXcQ|watch|youtube\.com|https?:/i);
  });

  it('sends no title outside video pages', () => {
    history.replaceState(null, '', '/results?search_query=cats');
    document.title = 'cats - YouTube';
    startYoutube();
    expect(pages()[0]!.title).toBeNull();
    expect(JSON.stringify(sent)).not.toMatch(/cats/);
  });

  it('tracks play, pause and end of the main video', () => {
    history.replaceState(null, '', '/watch?v=a');
    document.title = 'A - YouTube';
    const { video, s } = addVideo({ paused: true });
    startYoutube();
    expect(pages().at(-1)!.playing).toBe(false);

    s.paused = false;
    video.dispatchEvent(new Event('play'));
    expect(pages().at(-1)!.playing).toBe(true);

    // Hidden but playing: heartbeats continue.
    setVisibility('hidden');
    const before = sent.length;
    vi.advanceTimersByTime(HEARTBEAT_MS * 2);
    expect(sent.length).toBeGreaterThan(before);
    expect(pages().at(-1)).toMatchObject({ visible: false, playing: true });

    s.paused = true;
    video.dispatchEvent(new Event('pause'));
    expect(pages().at(-1)!.playing).toBe(false);
    const after = sent.length;
    vi.advanceTimersByTime(HEARTBEAT_MS * 3);
    expect(sent).toHaveLength(after);
  });

  it('a buffering stall keeps the playing state; hover previews never count', () => {
    const { s } = addVideo({ paused: false, readyState: 4 });
    expect(readPlaying(document, false)).toBe(true);
    s.readyState = 2;
    expect(readPlaying(document, true)).toBe(true);
    expect(readPlaying(document, false)).toBe(false);

    document.body.innerHTML = '';
    const preview = document.createElement('ytd-video-preview');
    document.body.appendChild(preview);
    addVideo({ paused: false, readyState: 4, parent: preview });
    expect(readPlaying(document, false)).toBe(false);
  });

  it('after in-page navigation, waits for the new title before reporting', () => {
    history.replaceState(null, '', '/watch?v=a');
    document.title = 'First - YouTube';
    startYoutube();
    expect(pages().at(-1)!.title).toBe('First');

    history.pushState(null, '', '/watch?v=b');
    document.dispatchEvent(new Event('yt-navigate-finish'));
    vi.advanceTimersByTime(1000);
    expect(pages().every((p) => p.title === 'First')).toBe(true);
    const held = sent.length;

    document.title = 'Second - YouTube';
    vi.advanceTimersByTime(1000);
    expect(sent.length).toBeGreaterThan(held);
    expect(pages().at(-1)!.title).toBe('Second');
  });

  it('gives up waiting after the settle time when the title stays the same', () => {
    history.replaceState(null, '', '/watch?v=a');
    document.title = 'Same - YouTube';
    startYoutube();
    history.pushState(null, '', '/watch?v=b');
    vi.advanceTimersByTime(SETTLE_MS + 1000);
    expect(pages().at(-1)!.title).toBe('Same');
    // Reports kept flowing after the hold.
    const count = sent.length;
    vi.advanceTimersByTime(HEARTBEAT_MS);
    expect(sent.length).toBeGreaterThan(count);
  });
});
