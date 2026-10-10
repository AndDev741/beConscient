import { describe, expect, it } from 'vitest';
import { detectBrowser, zoneAt } from './browser';
import { uuidv7 } from './uuidv7';

describe('detectBrowser', () => {
  it('detects Brave by navigator.brave', () => {
    expect(detectBrowser({ brave: { isBrave: async () => true } })).toBe('brave');
  });

  it('detects Brave by its UA client hints brand', () => {
    expect(
      detectBrowser({ userAgentData: { brands: [{ brand: 'Chromium' }, { brand: 'Brave' }] } }),
    ).toBe('brave');
  });

  it('reports chrome otherwise', () => {
    expect(detectBrowser({})).toBe('chrome');
    expect(detectBrowser({ userAgentData: { brands: [{ brand: 'Google Chrome' }, { brand: 'Chromium' }] } })).toBe(
      'chrome',
    );
    expect(detectBrowser(undefined)).toBe('chrome');
  });
});

describe('zoneAt', () => {
  it('returns the IANA zone and the UTC offset east of Greenwich', () => {
    const at = Date.UTC(2026, 6, 1, 12);
    const z = zoneAt(at);
    expect(z.tz).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(z.tzOffsetMin).toBe(-new Date(at).getTimezoneOffset() || 0);
  });
});

describe('uuidv7', () => {
  it('has version 7, variant 10 and the time in the first 48 bits', () => {
    const at = Date.UTC(2026, 9, 10, 12, 0, 0);
    const id = uuidv7(at);
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    const ms = parseInt(id.replace(/-/g, '').slice(0, 12), 16);
    expect(ms).toBe(at);
  });

  it('sorts by time', () => {
    const a = uuidv7(1_760_000_000_000);
    const b = uuidv7(1_760_000_000_001);
    expect(a < b).toBe(true);
    expect(uuidv7()).not.toBe(uuidv7());
  });
});
