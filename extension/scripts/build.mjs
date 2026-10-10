// Bundles the extension into dist/ (or another directory) with esbuild.
// Usage: node scripts/build.mjs [outdir]

import { build as esbuild } from 'esbuild';
import { copyFile, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Builds the unpacked extension into `outdir` and returns its absolute path. */
export async function build(outdir = path.join(ROOT, 'dist')) {
  const out = path.resolve(outdir);
  await rm(out, { recursive: true, force: true });
  await mkdir(path.join(out, 'popup'), { recursive: true });

  await esbuild({
    absWorkingDir: ROOT,
    entryPoints: {
      background: 'src/background.ts',
      'content/youtube': 'src/content/youtube.ts',
      'content/linkedin': 'src/content/linkedin.ts',
      'popup/popup': 'src/popup/popup.ts',
    },
    outdir: out,
    bundle: true,
    // Content scripts are classic scripts; one format for every entry keeps it simple.
    format: 'iife',
    target: 'chrome116',
    platform: 'browser',
    legalComments: 'none',
    logLevel: 'warning',
  });

  const manifest = JSON.parse(await readFile(path.join(ROOT, 'manifest.json'), 'utf8'));
  const pkg = JSON.parse(await readFile(path.join(ROOT, 'package.json'), 'utf8'));
  if (manifest.version !== pkg.version) {
    throw new Error(`manifest.json version ${manifest.version} differs from package.json ${pkg.version}`);
  }
  await writeFile(path.join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  await copyFile(path.join(ROOT, 'src/popup/popup.html'), path.join(out, 'popup/popup.html'));
  return out;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const out = await build(process.argv[2]);
  console.log(`built ${path.relative(process.cwd(), out) || out}`);
}
