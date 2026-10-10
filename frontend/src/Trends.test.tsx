import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Trends as TrendsData } from './api';
import { Trends } from './Trends';
import { error, freezeNow, mockApi, pending, reply } from './test/api';

const base: TrendsData = {
  period: 'week',
  current: {
    from: '2026-10-05', to: '2026-10-07', screenMinutes: 1100, awayMinutes: 1700,
    byCategory: [{ categoryId: 'w', name: 'Work', color: '#5b6cff', minutes: 700 }, { categoryId: 's', name: 'Social', color: '#c2416b', minutes: 400 }],
  },
  previous: {
    from: '2026-09-28', to: '2026-09-30', screenMinutes: 1200, awayMinutes: 1620,
    byCategory: [{ categoryId: 'w', name: 'Work', color: '#5b6cff', minutes: 650 }, { categoryId: 's', name: 'Social', color: '#c2416b', minutes: 550 }],
  },
  series: [
    { date: '2026-10-05', screenMinutes: 380, awayMinutes: 580, complete: true },
    { date: '2026-10-06', screenMinutes: 360, awayMinutes: 560, complete: true },
    { date: '2026-10-07', screenMinutes: 360, awayMinutes: 560, complete: true },
    { date: '2026-10-08', screenMinutes: 420, awayMinutes: 500, complete: false },
  ],
};

beforeEach(() => freezeNow('2026-10-08T15:00:00'));

describe('Trends', () => {
  it('compares a period in progress with the same span of the previous one', async () => {
    const { calls } = mockApi({ 'GET /api/trends': reply(base) });
    render(<Trends revision={0} />);
    expect(await screen.findByText(/Compared with 28 Sept? – 30 Sept?/)).toBeInTheDocument();
    expect(calls[0].query.get('period')).toBe('week');
    expect(calls[0].query.get('anchor')).toBe('2026-10-08');
    // Away: 1700 vs 1620 → +1 h 20 min; screen: 1100 vs 1200 → −1 h 40 min
    expect(screen.getByText('+1 h 20 min')).toBeInTheDocument();
    expect(screen.getAllByText('−1 h 40 min').length).toBeGreaterThan(0);
    expect(screen.getByText(/1 day is left out of both sides/)).toBeInTheDocument();
  });

  it('shows an empty comparison when there is no previous period', async () => {
    mockApi({ 'GET /api/trends': reply({ ...base, previous: null }) });
    render(<Trends revision={0} />);
    expect(await screen.findByText('No previous period', { selector: 'b' })).toBeInTheDocument();
    expect(screen.getByText(/no full previous span of closed days/)).toBeInTheDocument();
    expect(screen.queryByText(/^[+−]\d/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Compared with/)).not.toBeInTheDocument();
  });

  it('asks for the month when the period changes', async () => {
    const { calls } = mockApi({ 'GET /api/trends': reply(base) });
    render(<Trends revision={0} />);
    await screen.findByText(/Compared with/);
    fireEvent.click(screen.getByRole('button', { name: 'Month' }));
    await screen.findByText(/Compared with/);
    expect(calls.at(-1)?.query.get('period')).toBe('month');
  });

  it('never shows a number while loading', () => {
    mockApi({ 'GET /api/trends': pending });
    render(<Trends revision={0} />);
    expect(screen.getAllByText('Loading…').length).toBeGreaterThan(0);
    expect(screen.queryByText(/\d+\s*(min|h)\b/)).not.toBeInTheDocument();
  });

  it('shows a retryable error instead of zeros', async () => {
    mockApi({ 'GET /api/trends': error(503, 'unavailable', 'Backend is down') });
    render(<Trends revision={0} />);
    expect(await screen.findByText('Backend is down')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Try again/ })).toBeInTheDocument();
    expect(screen.queryByText(/\b0 min\b/)).not.toBeInTheDocument();
  });
});
