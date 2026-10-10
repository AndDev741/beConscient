/** A fake backend for tests: routes keyed by "METHOD /path", with `*` matching one path segment. */
import { vi } from 'vitest';

export interface Call {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
}

export interface Reply {
  status?: number;
  body?: unknown;
}

export type Handler = (call: Call) => Reply | Promise<Reply>;

/** A 2xx reply with this JSON body. */
export const reply = (body?: unknown, status = 200): Reply => ({ status: body === undefined && status === 200 ? 204 : status, body });
export const error = (status: number, code: string, message = code): Reply => ({ status, body: { error: code, message } });
/** A reply that never comes, to look at loading states. */
export const pending: Handler = () => new Promise<Reply>(() => {});

function matcher(key: string) {
  const [method, path] = key.split(' ');
  const re = new RegExp(`^${path.split('*').map((p) => p.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('[^/]+')}$`);
  return (m: string, p: string) => m === method && re.test(p);
}

export function mockApi(routes: Record<string, Handler | Reply>) {
  const calls: Call[] = [];
  const table = Object.entries(routes).map(([key, h]) => ({ match: matcher(key), h }));

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input instanceof Request ? input.url : input), 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    let body: unknown = init?.body;
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body);
      } catch {
        // form bodies stay as strings
      }
    }
    const call: Call = { method, path: decodeURIComponent(url.pathname), query: url.searchParams, body };
    calls.push(call);
    const hit = table.find((r) => r.match(method, url.pathname) || r.match(method, call.path));
    if (!hit) {
      return new Response(JSON.stringify({ error: 'no_route', message: `No test route for ${method} ${url.pathname}` }), { status: 404 });
    }
    const out = typeof hit.h === 'function' ? await hit.h(call) : hit.h;
    const status = out.status ?? 200;
    if (status === 204 || out.body === undefined) return new Response(null, { status: status === 200 ? 204 : status });
    return new Response(JSON.stringify(out.body), { status, headers: { 'content-type': 'application/json' } });
  });

  vi.stubGlobal('fetch', fetchMock);
  const find = (method: string, path: string | RegExp) =>
    calls.filter((c) => c.method === method && (typeof path === 'string' ? c.path === path : path.test(c.path)));
  return { calls, fetchMock, find };
}

/** Freeze "now" without faking timers, so testing-library's waits keep working. */
export function freezeNow(iso = '2026-10-10T14:30:00') {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

export const XSS_IMG = '<img src=x onerror=alert(1)>';
export const XSS_SVG = '</svg><script>alert(1)</script>';
