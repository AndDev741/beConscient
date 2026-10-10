import type { BrowserName } from './types';

export interface NavigatorLike {
  brave?: unknown;
  userAgentData?: { brands?: ReadonlyArray<{ brand: string }> } | null;
}

/**
 * Brave exposes `navigator.brave`. Its UA client hints also list a "Brave"
 * brand, which covers contexts where `navigator.brave` is missing. Every
 * other Chromium browser reports as chrome.
 */
export function detectBrowser(nav: NavigatorLike | undefined | null): BrowserName {
  if (!nav) return 'chrome';
  if (nav.brave !== undefined && nav.brave !== null) return 'brave';
  const brands = nav.userAgentData?.brands ?? [];
  if (brands.some((b) => b.brand === 'Brave')) return 'brave';
  return 'chrome';
}

/** IANA zone and UTC offset in minutes (positive east of UTC) at an instant. */
export function zoneAt(at: number): { tz: string; tzOffsetMin: number } {
  let tz = 'UTC';
  try {
    tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    // Keep UTC.
  }
  const offset = -new Date(at).getTimezoneOffset();
  return { tz, tzOffsetMin: offset === 0 ? 0 : offset };
}
