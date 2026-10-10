// YouTube helpers shared by the content script and the background.

/** Contract limit for `title` (contract/events.md). */
export const TITLE_MAX = 300;

/**
 * Turns a YouTube document title into a video title: drops the unread
 * notification counter ("(3) ") and the " - YouTube" suffix. Returns null
 * when nothing is left.
 */
export function cleanYoutubeTitle(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let title = raw.replace(/\s+/g, ' ').trim();
  title = title.replace(/^\(\d+\+?\)\s*/, '');
  title = title.replace(/\s*[-\u2013\u2014]\s*YouTube$/i, '');
  title = title.trim();
  if (title === '' || title.toLowerCase() === 'youtube') return null;
  return truncateTitle(title);
}

/** Cuts a title to TITLE_MAX characters without splitting a surrogate pair. */
export function truncateTitle(title: string): string {
  if (title.length <= TITLE_MAX) return title;
  const chars = Array.from(title);
  let out = '';
  for (const ch of chars) {
    if (out.length + ch.length > TITLE_MAX) break;
    out += ch;
  }
  return out;
}

/**
 * Identifies the video a YouTube location shows, or null when it is not a
 * video page. Used inside the content script only, to notice SPA
 * navigation between videos. It never leaves the page.
 */
export function videoKey(pathname: string, search: string): string | null {
  if (pathname === '/watch') {
    const v = new URLSearchParams(search).get('v');
    return v ? 'watch:' + v : 'watch:';
  }
  const m = /^\/(shorts|live)\/([^/?#]+)/.exec(pathname);
  if (m) return m[1] + ':' + m[2];
  return null;
}

export type PlaybackState = 'playing' | 'buffering' | 'stopped';

/** Minimal shape of HTMLMediaElement used here, so tests can pass plain objects. */
export interface MediaLike {
  paused: boolean;
  ended: boolean;
  readyState: number;
}

/**
 * Playing means !paused && !ended && readyState > 2 (HAVE_FUTURE_DATA).
 * A video that is not paused but waiting for data is "buffering"; the
 * caller keeps its previous answer then, so a short stall does not split
 * the interval.
 */
export function playbackState(video: MediaLike): PlaybackState {
  if (video.paused || video.ended) return 'stopped';
  return video.readyState > 2 ? 'playing' : 'buffering';
}
