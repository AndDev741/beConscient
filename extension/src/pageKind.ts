import type { PageKind } from './types';

/**
 * Coarse LinkedIn page kind from a path. Only the kind leaves the browser;
 * the path itself never does.
 */
export function pageKindFromPath(pathname: string): PageKind {
  const path = pathname.toLowerCase();
  if (startsWithSegment(path, '/feed')) return 'feed';
  if (startsWithSegment(path, '/messaging')) return 'messaging';
  if (path.startsWith('/in/')) return 'profile';
  if (startsWithSegment(path, '/jobs')) return 'jobs';
  return 'other';
}

/** True for "/feed", "/feed/" and "/feed/..." but not for "/feedback". */
function startsWithSegment(path: string, segment: string): boolean {
  return path === segment || path.startsWith(segment + '/');
}
