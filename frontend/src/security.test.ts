/**
 * Static checks on the deployment config and the source: the CSP and proxy rules
 * in nginx, and no path that turns a string into markup.
 */
import { describe, expect, it } from 'vitest';
import nginxConf from '../nginx.conf?raw';
import headers from '../nginx/security-headers.conf?raw';
import dockerfile from '../Dockerfile?raw';

const sources = import.meta.glob(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}', '!./test/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** The body of `location <path> { … }` (no nested blocks in this file). */
function block(path: string): string {
  const start = nginxConf.indexOf(`location ${path} {`);
  expect(start, `location ${path}`).toBeGreaterThan(-1);
  return nginxConf.slice(start, nginxConf.indexOf('\n    }', start));
}

describe('nginx', () => {
  it('sends a Content-Security-Policy with scripts from this origin and no framing', () => {
    const csp = headers.match(/Content-Security-Policy "([^"]+)"/)?.[1] ?? '';
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).not.toContain('unsafe-inline');
    expect(csp).not.toContain('unsafe-eval');
  });

  it('applies the headers wherever the page or its assets are served', () => {
    for (const path of ['/', '/assets/', '/api/']) {
      expect(block(path)).toContain('include /etc/nginx/snippets/security-headers.conf;');
    }
  });

  it('proxies /api/ and /ingest/ to a configurable backend', () => {
    expect(block('/api/')).toContain('proxy_pass http://${BACKEND_UPSTREAM};');
    expect(block('/ingest/')).toContain('proxy_pass http://${BACKEND_UPSTREAM};');
    expect(dockerfile).toContain('BACKEND_UPSTREAM=beconscient-backend:80');
    expect(dockerfile).toContain('NGINX_ENVSUBST_FILTER=^BACKEND_UPSTREAM$');
    expect(dockerfile).toContain('/etc/nginx/templates/default.conf.template');
  });

  it('caps ingest bodies at 1 MB and passes them through untouched', () => {
    const ingest = block('/ingest/');
    expect(ingest).toContain('client_max_body_size 1m;');
    expect(ingest).not.toMatch(/proxy_set_body|sub_filter|gunzip/);
  });

  it('caches assets for good, revalidates the page, and answers /healthz', () => {
    expect(block('/assets/')).toContain('immutable');
    expect(block('/')).toContain('Cache-Control "no-cache"');
    expect(block('/')).toContain('try_files $uri $uri/ /index.html;');
    expect(block('= /healthz')).toContain('return 200');
  });

  it('names no real host', () => {
    const hosts = nginxConf.match(/https?:\/\/[^\s;$]+/g) ?? [];
    expect(hosts).toEqual([]);
  });
});

describe('source', () => {
  it('has sources to check', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(10);
  });

  it('never turns a string into markup or code', () => {
    const banned = /dangerouslySetInnerHTML|\.innerHTML|\.outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function\(/;
    const offenders = Object.entries(sources).filter(([, text]) => banned.test(text)).map(([file]) => file);
    expect(offenders).toEqual([]);
  });

  it('loads no third-party script, style or font', () => {
    const offenders = Object.entries(sources)
      .filter(([, text]) => /https?:\/\/(?!localhost)/.test(text))
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
