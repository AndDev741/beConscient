// Shared types. Wire formats follow contract/native-messaging.md and the
// `browser.site` fields in contract/events.md.

export type Site = 'youtube' | 'linkedin';
export type PageKind = 'feed' | 'messaging' | 'profile' | 'jobs' | 'other';
export type BrowserName = 'chrome' | 'brave';

export const SITES: readonly Site[] = ['youtube', 'linkedin'];
export const PAGE_KINDS: readonly PageKind[] = ['feed', 'messaging', 'profile', 'jobs', 'other'];

/** What a content script observes on its page. Never carries a URL. */
export interface PageState {
  site: Site;
  /** document.visibilityState === 'visible' */
  visible: boolean;
  /** document.hasFocus() */
  focused: boolean;
  /** A <video> is playing. Always false on LinkedIn. */
  playing: boolean;
  /** Cleaned video title. YouTube video pages only, otherwise null. */
  title: string | null;
  /** LinkedIn only, otherwise null. */
  pageKind: PageKind | null;
}

/** Content script to background. */
export type ContentMessage =
  | { kind: 'page'; sentAt: number; state: PageState }
  | { kind: 'gone'; sentAt: number };

/** Extension to native host: one revision of a site interval. */
export interface SiteMessage {
  t: 'site';
  id: string;
  rev: number;
  site: Site;
  browser: BrowserName;
  start: number;
  end: number;
  playing: boolean;
  focused: boolean;
  /** Present for YouTube only. LinkedIn messages never carry this key. */
  title?: string | null;
  page_kind: PageKind | null;
  tz: string;
  tz_offset_min: number;
}

export interface PlayingMessage {
  t: 'playing';
  playing: boolean;
  at: number;
}

export interface HeartbeatMessage {
  t: 'heartbeat';
  browser: BrowserName;
  version: string;
  at: number;
}

export type HostBoundMessage = SiteMessage | PlayingMessage | HeartbeatMessage;

/** Native host to extension. */
export interface AckMessage {
  t: 'ack';
  ids: string[];
}

/** Connection status shown by the popup. Holds no measured data. */
export interface ConnectionStatus {
  connected: boolean;
  connectedAt: number | null;
  disconnectedAt: number | null;
  lastError: string | null;
  lastHeartbeatAt: number | null;
  lastAckAt: number | null;
  /** Do not try to connect again before this time. */
  retryAt: number;
  backoffMs: number;
}
