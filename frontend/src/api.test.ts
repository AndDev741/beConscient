import { describe, expect, it, vi } from 'vitest';
import { ApiError, api, setUnauthorizedHandler } from './api';
import { error, mockApi, reply } from './test/api';

describe('api', () => {
  it('sends any 401 to the global handler, except the session probe', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    mockApi({ 'GET /api/day': error(401, 'unauthorized'), 'GET /api/session': error(401, 'unauthorized') });

    await expect(api.day('2026-10-10')).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledTimes(1);

    await expect(api.session()).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledTimes(1);
    setUnauthorizedHandler(() => {});
  });

  it('turns the contract error body into a code and a message', async () => {
    mockApi({ 'DELETE /api/categories/*': error(409, 'category_in_use', 'Still mapped.') });
    const e = await api.deleteCategory('c1').catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).status).toBe(409);
    expect((e as ApiError).code).toBe('category_in_use');
    expect((e as ApiError).message).toBe('Still mapped.');
  });

  it('reports a network failure as an error, never as data', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));
    const e = await api.day('2026-10-10').catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).status).toBe(0);
  });

  it('encodes subject keys, including the #shorts suffix', async () => {
    const { fetchMock } = mockApi({ 'PUT /api/subjects/*/category': reply({}) });
    await api.mapSubject('app:com.google.android.youtube#shorts', 'c-video');
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/subjects/app%3Acom.google.android.youtube%23shorts/category');
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ categoryId: 'c-video' });
  });

  it('logs in with a form body using the contract field names', async () => {
    const { calls } = mockApi({ 'POST /api/login': reply() });
    await api.login('owner', 's3cret');
    const body = String(calls[0].body);
    expect(new URLSearchParams(body).get('j_username')).toBe('owner');
    expect(new URLSearchParams(body).get('j_password')).toBe('s3cret');
  });

  it('says so when the password is wrong', async () => {
    mockApi({ 'POST /api/login': error(401, 'unauthorized') });
    await expect(api.login('owner', 'x')).rejects.toThrow('Wrong username or password.');
  });

  it('asks the server not to pair a second phone unless confirmed', async () => {
    const { calls } = mockApi({ 'POST /api/devices': reply({ device: {}, code: 'ABCD', expiresAt: 0 }) });
    await api.createDevice('Phone', 'android');
    await api.createDevice('Phone', 'android', true);
    expect(calls[0].body).toEqual({ name: 'Phone', kind: 'android', confirmSecondPhone: false });
    expect(calls[1].body).toEqual({ name: 'Phone', kind: 'android', confirmSecondPhone: true });
  });

  it('builds query strings for trends, subjects and validation', async () => {
    const { calls } = mockApi({
      'GET /api/trends': reply({}),
      'GET /api/subjects': reply([]),
      'GET /api/validation': reply([]),
    });
    await api.trends('week', '2026-10-10');
    await api.subjects({ q: 'what', limit: 50 });
    await api.validation('2026-09-27', '2026-10-10');
    expect(calls[0].query.toString()).toBe('period=week&anchor=2026-10-10');
    expect(calls[1].query.toString()).toBe('q=what&limit=50');
    expect(calls[2].query.toString()).toBe('from=2026-09-27&to=2026-10-10');
  });
});
