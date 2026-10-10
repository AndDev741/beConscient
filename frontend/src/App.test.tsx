import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from './App';
import { CATEGORIES, DEVICES, SUBJECTS, makeBackstage, makeDay } from './fixtures';
import { error, freezeNow, mockApi, reply, type Call } from './test/api';

beforeEach(() => freezeNow('2026-10-10T14:30:00'));

const nav = (name: string) => screen.getAllByRole('button', { name })[0];

function backend(opts: { signedIn?: boolean } = {}) {
  let signedIn = opts.signedIn ?? true;
  const mapping: Record<string, string | null> = { 'app:org.example.reader': null };
  const day = () => {
    const d = makeDay('2026-10-10', { state: 'open', elapsedUntilHour: 15 });
    d.topSubjects = [{ subjectKey: 'app:org.example.reader', label: 'org.example.reader', categoryId: mapping['app:org.example.reader'], minutes: 30 }];
    return d;
  };
  return mockApi({
    'GET /api/session': () => (signedIn ? reply({ username: 'owner' }) : error(401, 'unauthorized')),
    'POST /api/login': () => {
      signedIn = true;
      return reply();
    },
    'GET /api/day': () => (signedIn ? reply(day()) : error(401, 'unauthorized')),
    'GET /api/categories': reply(CATEGORIES),
    'GET /api/subjects': () => reply(SUBJECTS.map((s) => (s.key in mapping ? { ...s, categoryId: mapping[s.key] } : s))),
    'PUT /api/subjects/*/category': (c: Call) => {
      const key = decodeURIComponent(c.path.split('/')[3]);
      mapping[key] = (c.body as { categoryId: string | null }).categoryId;
      return reply({});
    },
    'GET /api/devices': reply(DEVICES),
    'GET /api/pairing-codes/state': reply({ lockedOut: false, failures: 0 }),
    'GET /api/backstage': reply(makeBackstage(Date.now())),
    'GET /api/backstage/trace': (c: Call) => reply({ segment: { id: c.query.get('segmentId') }, rules: ['Clipped to screen on'] }),
    'POST /api/logout': () => {
      signedIn = false;
      return reply();
    },
  });
}

describe('App', () => {
  it('signs in and lands on Today', async () => {
    const { find } = backend({ signedIn: false });
    render(<App />);
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'owner' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByTestId('headline')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Today' })).toBeInTheDocument();
    expect(find('GET', '/api/day')[0].query.get('date')).toBe('2026-10-10');
  });

  it('goes back to the sign-in screen on a 401 from any request', async () => {
    mockApi({
      'GET /api/session': reply({ username: 'owner' }),
      'GET /api/day': error(401, 'unauthorized'),
    });
    render(<App />);
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('says so when the server does not answer at boot, and retries', async () => {
    let up = false;
    mockApi({
      'GET /api/session': () => (up ? reply({ username: 'owner' }) : error(502, 'bad_gateway', 'Bad gateway')),
      'GET /api/day': reply(makeDay('2026-10-10')),
    });
    render(<App />);
    expect(await screen.findByText('The server did not answer.')).toBeInTheDocument();
    up = true;
    fireEvent.click(screen.getByRole('button', { name: /Try again/ }));
    expect(await screen.findByTestId('headline')).toBeInTheDocument();
  });

  it('keeps the secondary views behind More', async () => {
    backend();
    render(<App />);
    await screen.findByTestId('headline');
    expect(screen.queryByRole('button', { name: 'Devices' })).not.toBeInTheDocument();
    fireEvent.click(nav('More'));
    const menu = screen.getByRole('menu', { name: 'More' });
    expect(within(menu).getAllByRole('menuitem').map((b) => b.querySelector('.mlabel')?.textContent)).toEqual([
      'Devices', 'Settings', 'Backstage', 'Validation', 'Corrections',
    ]);
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Devices/ }));
    expect(await screen.findByRole('heading', { name: 'Devices' })).toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('shows a new mapping on the day view', async () => {
    const { find } = backend();
    render(<App />);
    await screen.findByTestId('headline');
    const row = () => screen.getByText('org.example.reader').closest('li') as HTMLElement;
    expect(within(row()).getByText('Uncategorized')).toBeInTheDocument();

    fireEvent.click(nav('Categories'));
    const select = await screen.findByRole('combobox', { name: 'Category for org.example.reader' });
    fireEvent.change(select, { target: { value: 'c-video' } });
    await waitFor(() => expect(find('PUT', /subjects/)).toHaveLength(1));

    const before = find('GET', '/api/day').length;
    fireEvent.click(nav('Today'));
    await waitFor(() => expect(find('GET', '/api/day').length).toBe(before + 1));
    await waitFor(() => expect(within(row()).getByText('Video')).toBeInTheDocument());
  });

  it('opens the trace of a segment from the hour chart', async () => {
    const { find } = backend();
    render(<App />);
    await screen.findByTestId('headline');
    fireEvent.click(screen.getByRole('button', { name: /^10:00, / }));
    const trace = within(document.querySelector('.traces') as HTMLElement).getAllByRole('button')[0];
    fireEvent.click(trace);
    expect(await screen.findByRole('heading', { name: 'Backstage' })).toBeInTheDocument();
    expect(await screen.findByText('Clipped to screen on')).toBeInTheDocument();
    expect(find('GET', '/api/backstage/trace')[0].query.get('segmentId')).toMatch(/^seg-2026-10-10-10-/);
  });

  it('signs out', async () => {
    backend();
    render(<App />);
    await screen.findByTestId('headline');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});
