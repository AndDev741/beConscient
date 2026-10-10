import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Settings } from './api';
import { makeSettings } from './fixtures';
import { SettingsView } from './Settings';
import { freezeNow, mockApi, reply, type Call } from './test/api';

const TODAY = '2026-10-10';

function setup() {
  let state: Settings = makeSettings(TODAY);
  const save = (next: Partial<Settings>) => {
    state = { ...state, ...next };
    return reply(state);
  };
  const api = mockApi({
    'GET /api/settings': () => reply(state),
    'PUT /api/settings/work-window': (c: Call) => {
      const v = c.body as { days: number[]; start: string; end: string };
      return save({ workWindow: { ...v, validFrom: TODAY }, history: [...state.history, { kind: 'work', value: v, validFrom: TODAY }] });
    },
    'PUT /api/settings/awake-window': (c: Call) => {
      const v = c.body as { start: string; end: string };
      return save({ awakeWindow: { ...v, validFrom: TODAY }, history: [...state.history, { kind: 'awake', value: v, validFrom: TODAY }] });
    },
    'POST /api/non-workdays': (c: Call) => save({ nonWorkdays: [...state.nonWorkdays, (c.body as { date: string }).date] }),
    'DELETE /api/non-workdays/*': (c: Call) => save({ nonWorkdays: state.nonWorkdays.filter((d) => d !== c.path.split('/').pop()) }),
  });
  render(<SettingsView />);
  return api;
}

const card = (title: string) => screen.getByRole('heading', { name: title }).closest('section') as HTMLElement;

beforeEach(() => freezeNow('2026-10-10T10:00:00'));

describe('Settings', () => {
  it('says that changes apply from today', async () => {
    setup();
    expect(screen.getByText('Changes apply from today')).toBeInTheDocument();
    await screen.findByRole('heading', { name: 'Work window' });
  });

  it('validates the work window before saving it', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Work window' });
    const work = card('Work window');
    const save = within(work).getByRole('button', { name: 'Save from today' });
    expect(save).toBeDisabled();

    for (const day of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday']) {
      fireEvent.click(within(work).getByRole('button', { name: day }));
    }
    expect(within(work).getByText('Pick at least one working day.')).toBeInTheDocument();
    expect(save).toBeDisabled();

    fireEvent.click(within(work).getByRole('button', { name: 'Tuesday' }));
    fireEvent.click(within(work).getByRole('button', { name: 'Monday' }));
    fireEvent.change(within(work).getByLabelText('Ends'), { target: { value: '08:00' } });
    expect(within(work).getByText(/must end after it starts/)).toBeInTheDocument();

    fireEvent.change(within(work).getByLabelText('Starts'), { target: { value: '10:00' } });
    fireEvent.change(within(work).getByLabelText('Ends'), { target: { value: '19:00' } });
    fireEvent.click(save);
    await waitFor(() => expect(find('PUT', '/api/settings/work-window')).toHaveLength(1));
    expect(find('PUT', '/api/settings/work-window')[0].body).toEqual({ days: [1, 2], start: '10:00', end: '19:00' });
    expect(await within(work).findByText('Saved. Applies from today.')).toBeInTheDocument();
  });

  it('accepts an awake window that crosses midnight and explains it', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Awake window' });
    const awake = card('Awake window');
    fireEvent.change(within(awake).getByLabelText('Wake up'), { target: { value: '08:00' } });
    fireEvent.change(within(awake).getByLabelText('Go to sleep'), { target: { value: '01:00' } });
    expect(within(awake).getByTestId('awake-length')).toHaveTextContent('17 h awake. Crosses midnight');
    fireEvent.click(within(awake).getByRole('button', { name: 'Save from today' }));
    await waitFor(() => expect(find('PUT', '/api/settings/awake-window')).toHaveLength(1));
    expect(find('PUT', '/api/settings/awake-window')[0].body).toEqual({ start: '08:00', end: '01:00' });
  });

  it('refuses an awake window that starts and ends at the same time', async () => {
    setup();
    await screen.findByRole('heading', { name: 'Awake window' });
    const awake = card('Awake window');
    fireEvent.change(within(awake).getByLabelText('Go to sleep'), { target: { value: '07:00' } });
    expect(within(awake).getByText('Start and end must differ.')).toBeInTheDocument();
    expect(within(awake).getByRole('button', { name: 'Save from today' })).toBeDisabled();
  });

  it('shows the version history after an edit', async () => {
    setup();
    await screen.findByRole('heading', { name: 'Awake window' });
    const awake = card('Awake window');
    fireEvent.change(within(awake).getByLabelText('Wake up'), { target: { value: '06:30' } });
    fireEvent.click(within(awake).getByRole('button', { name: 'Save from today' }));
    const history = card('Version history');
    expect(await within(history).findByText('06:30–23:00')).toBeInTheDocument();
    expect(within(history).getByText('Mon Tue Wed Thu Fri, 09:00–18:00')).toBeInTheDocument();
  });

  it('marks a day off, and undoes it', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Days off' });
    const days = card('Days off');
    fireEvent.change(within(days).getByLabelText('Date'), { target: { value: '2026-12-08' } });
    fireEvent.click(within(days).getByRole('button', { name: 'Mark as day off' }));
    expect(await within(days).findByText(/8 December/)).toBeInTheDocument();
    expect(find('POST', '/api/non-workdays')[0].body).toEqual({ date: '2026-12-08' });

    fireEvent.click(screen.getByRole('button', { name: /Undo/ }));
    await waitFor(() => expect(find('DELETE', '/api/non-workdays/2026-12-08')).toHaveLength(1));
    await waitFor(() => expect(within(days).queryByText(/8 December/)).not.toBeInTheDocument());
  });

  it('removes a day off and offers to undo', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Days off' });
    fireEvent.click(screen.getByRole('button', { name: 'Remove day off 2026-12-25' }));
    await waitFor(() => expect(find('DELETE', '/api/non-workdays/2026-12-25')).toHaveLength(1));
    fireEvent.click(await screen.findByRole('button', { name: /Undo/ }));
    await waitFor(() => expect(find('POST', '/api/non-workdays')).toHaveLength(1));
  });
});
