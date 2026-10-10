// Validation of messages that reach the background from content scripts.
// The background trusts the sender origin over what the page reports, and
// keeps only the fields each site may carry.

import { truncateTitle } from './youtube';
import { PAGE_KINDS, type ContentMessage, type PageKind, type PageState, type Site } from './types';

const ORIGIN_SITES: Record<string, Site> = {
  'https://www.youtube.com': 'youtube',
  'https://www.linkedin.com': 'linkedin',
};

export function siteFromOrigin(origin: string | undefined): Site | null {
  if (!origin) return null;
  return ORIGIN_SITES[origin] ?? null;
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

/**
 * Parses a content script message. Returns null for anything malformed or
 * coming from a page that is not one of the two sites.
 */
export function parseContentMessage(raw: unknown, origin: string | undefined): ContentMessage | null {
  if (!isRecord(raw)) return null;
  const sentAt = typeof raw.sentAt === 'number' && Number.isFinite(raw.sentAt) ? raw.sentAt : 0;

  const originSite = siteFromOrigin(origin);
  if (origin !== undefined && originSite === null) return null;

  if (raw.kind === 'gone') return { kind: 'gone', sentAt };
  if (raw.kind !== 'page' || !isRecord(raw.state)) return null;

  const s = raw.state;
  const claimed = s.site === 'youtube' || s.site === 'linkedin' ? s.site : null;
  const site = originSite ?? claimed;
  if (!site) return null;

  const visible = s.visible === true;
  const focused = s.focused === true;
  let state: PageState;
  if (site === 'youtube') {
    const title = typeof s.title === 'string' && s.title.trim() !== '' ? truncateTitle(s.title.trim()) : null;
    state = { site, visible, focused, playing: s.playing === true, title, pageKind: null };
  } else {
    const pageKind: PageKind = PAGE_KINDS.includes(s.pageKind as PageKind) ? (s.pageKind as PageKind) : 'other';
    // LinkedIn never carries a title, and its autoplaying feed videos do not count as playing.
    state = { site, visible, focused, playing: false, title: null, pageKind };
  }
  return { kind: 'page', sentAt, state };
}
