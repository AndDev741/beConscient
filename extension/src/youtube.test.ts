import { describe, expect, it } from 'vitest';
import { cleanYoutubeTitle, playbackState, TITLE_MAX, truncateTitle, videoKey } from './youtube';

describe('cleanYoutubeTitle', () => {
  it.each([
    ['Big Buck Bunny - YouTube', 'Big Buck Bunny'],
    ['(3) Big Buck Bunny - YouTube', 'Big Buck Bunny'],
    ['(99+) Big Buck Bunny - YouTube', 'Big Buck Bunny'],
    ['  Spaced   out  - YouTube ', 'Spaced out'],
    ['A - B - YouTube', 'A - B'],
    ['No suffix', 'No suffix'],
    ['YouTube', null],
    ['(1) YouTube', null],
    ['', null],
  ])('%j -> %j', (raw, clean) => {
    expect(cleanYoutubeTitle(raw)).toBe(clean);
  });

  it('caps the title at the contract limit', () => {
    const long = 'x'.repeat(500) + ' - YouTube';
    expect(cleanYoutubeTitle(long)).toHaveLength(TITLE_MAX);
  });

  it('does not split a surrogate pair when truncating', () => {
    const title = 'a'.repeat(TITLE_MAX - 1) + '\u{1F600}';
    const out = truncateTitle(title);
    expect(out).toBe('a'.repeat(TITLE_MAX - 1));
  });
});

describe('videoKey', () => {
  it('identifies video pages only', () => {
    expect(videoKey('/watch', '?v=abc123&t=10')).toBe('watch:abc123');
    expect(videoKey('/shorts/xyz', '')).toBe('shorts:xyz');
    expect(videoKey('/live/qqq', '')).toBe('live:qqq');
    expect(videoKey('/', '')).toBeNull();
    expect(videoKey('/results', '?search_query=cats')).toBeNull();
    expect(videoKey('/@channel/videos', '')).toBeNull();
  });
});

describe('playbackState', () => {
  it('is playing only when not paused, not ended and readyState > 2', () => {
    expect(playbackState({ paused: false, ended: false, readyState: 4 })).toBe('playing');
    expect(playbackState({ paused: false, ended: false, readyState: 3 })).toBe('playing');
    expect(playbackState({ paused: false, ended: false, readyState: 2 })).toBe('buffering');
    expect(playbackState({ paused: true, ended: false, readyState: 4 })).toBe('stopped');
    expect(playbackState({ paused: false, ended: true, readyState: 4 })).toBe('stopped');
  });
});
