import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { build, ROOT } from '../scripts/build.mjs';

const EXTENSION_ID = 'hhddmdmnpejagnnmenjdgdifpimneeok';
const HOSTS = ['https://www.youtube.com/*', 'https://www.linkedin.com/*'];

/** Chrome's id: SHA-256 of the DER public key, first 32 hex digits mapped 0-f -> a-p. */
function extensionIdFromKey(key: string): string {
  const hex = createHash('sha256').update(Buffer.from(key, 'base64')).digest('hex').slice(0, 32);
  return [...hex].map((c) => String.fromCharCode('a'.charCodeAt(0) + parseInt(c, 16))).join('');
}

function checkManifest(m: Record<string, any>, dir: string) {
  expect(m.manifest_version).toBe(3);

  // Exactly these permissions, nothing optional.
  expect([...m.permissions].sort()).toEqual(['alarms', 'nativeMessaging', 'storage']);
  expect(m.host_permissions).toEqual(HOSTS);
  expect(m.permissions).not.toContain('tabs');
  expect(JSON.stringify(m)).not.toContain('<all_urls>');
  expect(m).not.toHaveProperty('externally_connectable');
  expect(m).not.toHaveProperty('optional_permissions');
  expect(m).not.toHaveProperty('optional_host_permissions');
  expect(m).not.toHaveProperty('web_accessible_resources');

  // Content scripts only on the two sites, top frame only.
  const matches = m.content_scripts.flatMap((c: any) => c.matches);
  expect([...matches].sort()).toEqual([...HOSTS].sort());
  for (const c of m.content_scripts) {
    expect(c.all_frames ?? false).toBe(false);
    expect(c.exclude_matches ?? []).toEqual([]);
  }

  // The fixed id from contract/native-messaging.md.
  expect(extensionIdFromKey(m.key)).toBe(EXTENSION_ID);

  // Every file the manifest points at exists.
  const files = [
    m.background.service_worker,
    m.action.default_popup,
    ...m.content_scripts.flatMap((c: any) => c.js),
  ];
  for (const f of files) expect(existsSync(path.join(dir, f)), f).toBe(true);
}

let outdir = '';

beforeAll(async () => {
  outdir = await mkdtemp(path.join(os.tmpdir(), 'beconscient-ext-'));
  await build(outdir);
}, 60_000);

afterAll(async () => {
  if (outdir) await rm(outdir, { recursive: true, force: true });
});

describe('built manifest', () => {
  it('requests exactly nativeMessaging, storage, alarms and the two host patterns', async () => {
    const m = JSON.parse(await readFile(path.join(outdir, 'manifest.json'), 'utf8'));
    checkManifest(m, outdir);
  });

  it('dist/manifest.json, when built, passes the same checks', async () => {
    const dist = path.join(ROOT, 'dist');
    if (!existsSync(path.join(dist, 'manifest.json'))) return;
    const m = JSON.parse(await readFile(path.join(dist, 'manifest.json'), 'utf8'));
    checkManifest(m, dist);
  });

  it('uses the key published in contract/native-messaging.md', async () => {
    const contract = path.join(ROOT, '..', 'contract', 'native-messaging.md');
    if (!existsSync(contract)) return;
    const text = await readFile(contract, 'utf8');
    const key = /```\s*\n(MII[A-Za-z0-9+/=]+)\s*\n```/.exec(text)?.[1];
    const m = JSON.parse(await readFile(path.join(outdir, 'manifest.json'), 'utf8'));
    expect(m.key).toBe(key);
    expect(text).toContain(EXTENSION_ID);
  });

  it('bundles no code that reads URLs or tab titles from the tabs API', async () => {
    for (const f of ['background.js', 'content/youtube.js', 'content/linkedin.js']) {
      const js = await readFile(path.join(outdir, f), 'utf8');
      expect(js).not.toMatch(/tabs\.(query|get)\b/);
      expect(js).not.toMatch(/\.url\b/);
      expect(js).not.toMatch(/location\.href/);
    }
    const linkedin = await readFile(path.join(outdir, 'content/linkedin.js'), 'utf8');
    expect(linkedin).not.toMatch(/document\.title/);
  });
});
