import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { DayView } from './api';
import { Day } from './Day';
import { makeDay } from './fixtures';
import { duration } from './format';
import { XSS_IMG, XSS_SVG, error, freezeNow, mockApi, pending, reply, type Handler } from './test/api';

const DATE = '2026-10-08';

function renderDay(day: DayView | Handler, date = DATE, onTrace?: (id: string) => void) {
  const api = mockApi({ 'GET /api/day': typeof day === 'function' ? day : reply(day) });
  const view = render(<Day date={date} onDate={() => {}} revision={0} onTrace={onTrace} />);
  return { ...api, ...view };
}

const hourColumns = () => screen.getAllByRole('button', { name: /^\d\d:\d\d, / });

beforeEach(() => freezeNow());

describe('Day', () => {
  it('draws one column per hour and a total equal to the sum of the categories', async () => {
    const day = makeDay(DATE);
    renderDay(day);
    await screen.findByTestId('headline');

    expect(hourColumns()).toHaveLength(24);
    const sum = day.byCategory.reduce((s, c) => s + c.minutes, 0);
    expect(sum).toBe(day.totals.screenMinutes);
    expect(screen.getByTestId('day-total')).toHaveTextContent(duration(sum));
    expect(screen.getByTestId('category-sum')).toHaveTextContent(duration(sum));
  });

  it('shows the headline as awake time away from screens, with screen time as detail', async () => {
    const day = makeDay(DATE);
    renderDay(day);
    const headline = await screen.findByTestId('headline');
    expect(headline).toHaveTextContent(/6\s*h\s*34\s*min/);
    expect(screen.getByText('Awake hours away from screens')).toBeInTheDocument();
    expect(screen.getByText('Screen time while awake').nextSibling).toHaveTextContent(duration(day.headline.screenMinutes));
  });

  it('puts screen time outside the awake window on its own line', async () => {
    renderDay(makeDay(DATE));
    await screen.findByTestId('headline');
    expect(screen.getByText('Screen time outside the awake window').nextSibling).toHaveTextContent('20 min');
  });

  it('shows per-device time and the overlap counted once', async () => {
    renderDay(makeDay(DATE));
    await screen.findByTestId('headline');
    expect(screen.getByText('Phone')).toBeInTheDocument();
    expect(screen.getByText('Desk')).toBeInTheDocument();
    expect(screen.getByText('Overlap, used at the same time').nextSibling).toHaveTextContent('−22 min');
  });

  it('shows an empty day as empty, without an error', async () => {
    renderDay(makeDay(DATE, { empty: true }));
    expect(await screen.findByText('No screen time recorded on this day.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says data is still arriving for a day that is not closed', async () => {
    renderDay(makeDay(DATE, { state: 'arriving' }));
    expect(await screen.findByText('Data still arriving')).toBeInTheDocument();
  });

  it('shows the recalculation progress while a rebuild runs', async () => {
    renderDay(makeDay(DATE, { rebuilding: 0.4 }));
    expect(await screen.findByText('Recalculating · 40%')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Recalculation progress' })).toHaveAttribute('aria-valuenow', '40');
  });

  it('marks today as in progress and counts only elapsed awake hours', async () => {
    renderDay(makeDay('2026-10-10', { state: 'open', elapsedUntilHour: 15 }), '2026-10-10');
    await screen.findByTestId('headline');
    expect(screen.getByText('In progress')).toBeInTheDocument();
    expect(screen.getByText(/Counting the 8 h of awake time that already passed today/)).toBeInTheDocument();
  });

  it('marks an incomplete day and names the silent sensors', async () => {
    renderDay(makeDay(DATE, { silentSensors: ['activity (Desk)', 'usage (Phone)'] }));
    await screen.findByTestId('headline');
    expect(screen.getByText('Incomplete')).toBeInTheDocument();
    expect(screen.getByText(/Silent while you were awake: activity \(Desk\), usage \(Phone\)/)).toBeInTheDocument();
  });

  it('draws 25 columns on the day the clocks go back', async () => {
    renderDay(makeDay('2026-10-25', { hourCount: 25 }), '2026-10-25');
    await screen.findByTestId('headline');
    expect(hourColumns()).toHaveLength(25);
  });

  it('reveals an hour on tap, with names and minutes as text', async () => {
    renderDay(makeDay(DATE));
    await screen.findByTestId('headline');
    fireEvent.click(screen.getByRole('button', { name: '10:00, 52 min' }));
    const detail = document.querySelector('.hdetail') as HTMLElement;
    expect(within(detail).getByText('10:00 – 11:00')).toBeInTheDocument();
    expect(within(detail).getByText('Work')).toBeInTheDocument();
    expect(within(detail).getAllByText('52 min').length).toBeGreaterThan(0);
  });

  it('opens the trace of a segment from the hour detail', async () => {
    const onTrace = vi.fn();
    renderDay(makeDay(DATE), DATE, onTrace);
    await screen.findByTestId('headline');
    fireEvent.click(screen.getByRole('button', { name: '10:00, 52 min' }));
    fireEvent.click(screen.getByRole('button', { name: 'Work · 52 min' }));
    expect(onTrace).toHaveBeenCalledWith('seg-2026-10-08-10-0');
  });

  it('renders event-derived strings as plain text, never as markup', async () => {
    const day = makeDay(DATE);
    day.topSubjects[0].label = XSS_IMG;
    day.unlocks.firstApps[0].label = XSS_SVG;
    day.notifications[0].label = XSS_IMG;
    day.byCategory[0].name = XSS_SVG; // Work: shows in the chart detail, the legend and the bars
    renderDay(day);
    await screen.findByTestId('headline');
    fireEvent.click(screen.getByRole('button', { name: '10:00, 52 min' }));

    expect(screen.getAllByText(XSS_IMG)).toHaveLength(2);
    expect(screen.getAllByText(XSS_SVG).length).toBeGreaterThanOrEqual(4);
    const titles = [...document.querySelectorAll('svg title')].map((t) => t.textContent);
    expect(titles).toContain(`${XSS_SVG}: 52 min`);
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
    expect(document.querySelector('[onerror]')).toBeNull();
  });

  it('never shows a number while loading', () => {
    renderDay(pending);
    expect(screen.getAllByText('Loading…').length).toBeGreaterThan(0);
    expect(screen.queryByTestId('headline')).not.toBeInTheDocument();
    expect(screen.queryByText(/\d+\s*(min|h)\b/)).not.toBeInTheDocument();
  });

  it('shows a retryable error instead of zeros, then the data after a retry', async () => {
    let calls = 0;
    renderDay(() => (++calls === 1 ? error(500, 'boom', 'Server exploded') : reply(makeDay(DATE))));
    expect(await screen.findByText('Server exploded')).toBeInTheDocument();
    expect(screen.queryByTestId('headline')).not.toBeInTheDocument();
    expect(screen.queryByText(/\b0 min\b/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Try again/ }));
    expect(await screen.findByTestId('headline')).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  it('asks for the new day when navigating', async () => {
    const onDate = vi.fn();
    mockApi({ 'GET /api/day': reply(makeDay(DATE)) });
    render(<Day date={DATE} onDate={onDate} revision={0} />);
    await screen.findByTestId('headline');
    fireEvent.click(screen.getByRole('button', { name: 'Previous day' }));
    expect(onDate).toHaveBeenCalledWith('2026-10-07');
    fireEvent.click(screen.getByRole('button', { name: 'Next day' }));
    expect(onDate).toHaveBeenCalledWith('2026-10-09');
  });

  it('cannot move past today', async () => {
    mockApi({ 'GET /api/day': reply(makeDay('2026-10-10', { state: 'open', elapsedUntilHour: 15 })) });
    render(<Day date="2026-10-10" onDate={() => {}} revision={0} />);
    await screen.findByTestId('headline');
    expect(screen.getByRole('button', { name: 'Next day' })).toBeDisabled();
  });
});
