// YouTube page reading. Sends the video title and whether a video plays.
// The URL, video id, channel and search terms never leave this script.

import { cleanYoutubeTitle, playbackState, videoKey } from '../youtube';
import type { PageState } from '../types';

/**
 * After an in-page navigation to another video, YouTube updates
 * document.title a moment later. Until the title changes (or this much time
 * passes) the reader holds back, so the old title is not sent for the new
 * video.
 */
export const SETTLE_MS = 3_000;

/** Hover previews on the home page play muted clips; they are not watching. */
const PREVIEW_SELECTOR = 'ytd-video-preview, #video-preview, #inline-preview-player';

export interface LocationLike {
  pathname: string;
  search: string;
}

/** Whether a video plays. A stall keeps the previous answer. */
export function readPlaying(root: ParentNode, previous: boolean): boolean {
  let buffering = false;
  for (const video of Array.from(root.querySelectorAll('video'))) {
    if (video.closest(PREVIEW_SELECTOR)) continue;
    const state = playbackState(video);
    if (state === 'playing') return true;
    if (state === 'buffering') buffering = true;
  }
  return buffering && previous;
}

export function createYoutubeReader(
  doc: Document,
  loc: LocationLike,
  now: () => number = () => Date.now(),
): () => PageState | null {
  let key: string | null | undefined; // undefined until the first read
  let navAt = -Infinity;
  let titleAtNav = '';
  let playing = false;

  return () => {
    const k = videoKey(loc.pathname, loc.search);
    const raw = doc.title;
    const t = now();
    if (k !== key) {
      // On the first read the title already belongs to the page.
      navAt = key === undefined ? -Infinity : t;
      key = k;
      titleAtNav = raw;
    }
    if (k !== null && raw === titleAtNav && t - navAt < SETTLE_MS) return null;

    playing = readPlaying(doc, playing);
    return {
      site: 'youtube',
      visible: doc.visibilityState === 'visible',
      focused: doc.hasFocus(),
      playing,
      // Only video pages carry a title; home, search and channel pages do not.
      title: k !== null ? cleanYoutubeTitle(raw) : null,
      pageKind: null,
    };
  };
}

/** Calls `check` on YouTube's SPA navigation and on <title> changes. */
export function watchYoutube(doc: Document, check: () => void): () => void {
  const onNavigate = (): void => check();
  doc.addEventListener('yt-navigate-finish', onNavigate);

  let titleEl: Element | null = null;
  const titleObserver = new MutationObserver(() => check());
  const attach = (): void => {
    const el = doc.querySelector('title');
    if (!el || el === titleEl) return;
    titleObserver.disconnect();
    titleEl = el;
    titleObserver.observe(el, { childList: true, characterData: true, subtree: true });
  };
  attach();
  const headObserver = new MutationObserver(() => attach());
  if (doc.head) headObserver.observe(doc.head, { childList: true });

  return () => {
    doc.removeEventListener('yt-navigate-finish', onNavigate);
    titleObserver.disconnect();
    headObserver.disconnect();
  };
}
