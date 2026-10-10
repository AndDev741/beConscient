import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { ValidationRow } from './api';
import { Validation, diffPercent } from './Validation';
import { error, freezeNow, mockApi, pending, reply } from './test/api';

const row = (date: string, closed: boolean, measured: number | null, settings: number | null): ValidationRow => {
  const diff = measured !== null && settings ? ((measured - settings) / settings) * 100 : null;
  return { date, closed, measuredMinutes: measured, rawMinutes: measured === null ? null : measured + 10, settingsMinutes: settings, diffPct: diff, flagged: diff !== null && Math.abs(diff) > 10 };
};

const ROWS: ValidationRow[] = [
  row('2026-10-10', false, null, null),
  row('2026-10-09', false, null, null),
  row('2026-10-08', true, 270, 240),
  row('2026-10-07', true, 260, 240),
  row('2026-10-06', true, 200, null),
];

beforeEach(() => freezeNow('2026-10-10T12:00:00'));

describe('Validation', () => {
  it('computes the difference against the Settings total', () => {
    expect(diffPercent({ measuredMinutes: 270, settingsMinutes: 240 })).toBeCloseTo(12.5);
    expect(diffPercent({ measuredMinutes: 260, settingsMinutes: 240 })).toBeCloseTo(8.33, 1);
    expect(diffPercent({ measuredMinutes: 260, settingsMinutes: null })).toBeNull();
  });

  it('flags a day more than 10% apart and not one within 10%', async () => {
    mockApi({ 'GET /api/validation': reply(ROWS) });
    render(<Validation />);
    const flagged = await screen.findByTestId('vrow-2026-10-08');
    expect(flagged).toHaveClass('flagged');
    expect(flagged).toHaveTextContent('+12.5%');
    expect(within(flagged).getByText('Over 10%')).toBeInTheDocument();

    const fine = screen.getByTestId('vrow-2026-10-07');
    expect(fine).not.toHaveClass('flagged');
    expect(fine).toHaveTextContent('+8.3%');
    expect(within(fine).queryByText('Over 10%')).not.toBeInTheDocument();
  });

  it('only offers closed days, never today', async () => {
    mockApi({ 'GET /api/validation': reply(ROWS) });
    render(<Validation />);
    const select = await screen.findByLabelText('Day');
    const option = (re: RegExp) => within(select).getByRole('option', { name: re });
    expect(option(/today, not closed/)).toBeDisabled();
    expect(option(/9 Oct · not closed/)).toBeDisabled();
    expect(option(/8 Oct/)).toBeEnabled();
    // The first closed day without a Settings value is picked
    expect(select).toHaveValue('2026-10-06');
  });

  it('saves hours and minutes as minutes and shows measured against entered', async () => {
    const { find } = mockApi({
      'GET /api/validation': reply(ROWS),
      'PUT /api/validation/*': (c) => reply(row('2026-10-06', true, 200, (c.body as { minutes: number }).minutes)),
    });
    render(<Validation />);
    fireEvent.change(await screen.findByLabelText('Hours'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Minutes'), { target: { value: '5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(find('PUT', '/api/validation/2026-10-06')).toHaveLength(1));
    expect(find('PUT', '/api/validation/2026-10-06')[0].body).toEqual({ minutes: 185 });
    const result = await screen.findByRole('status');
    expect(result).toHaveTextContent('measured 3 h 20 min against 3 h 5 min in Settings (+8.1%)');
    expect(result).toHaveTextContent('Within 10%.');
    expect(screen.getByTestId('vrow-2026-10-06')).toHaveTextContent('3 h 5 min');
  });

  it('explains when the server refuses a day that is not closed', async () => {
    mockApi({
      'GET /api/validation': reply(ROWS),
      'PUT /api/validation/*': error(409, 'day_not_closed'),
    });
    render(<Validation />);
    fireEvent.change(await screen.findByLabelText('Hours'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/This day is not closed yet/)).toBeInTheDocument();
  });

  it('rejects impossible minutes before sending', async () => {
    const { find } = mockApi({ 'GET /api/validation': reply(ROWS) });
    render(<Validation />);
    fireEvent.change(await screen.findByLabelText('Minutes'), { target: { value: '75' } });
    expect(screen.getByText('Minutes must be a whole number from 0 to 59.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(find('PUT', /validation/)).toHaveLength(0);
  });

  it('never shows a number while loading', () => {
    mockApi({ 'GET /api/validation': pending });
    render(<Validation />);
    expect(screen.queryByText(/\d+\s*(min|h)\b/)).not.toBeInTheDocument();
    expect(screen.queryByText(/[+−]\d/)).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
