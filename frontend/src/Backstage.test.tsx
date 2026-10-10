import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Backstage } from './api';
import { BackstageView } from './Backstage';
import { makeBackstage } from './fixtures';
import { XSS_IMG, XSS_SVG, error, mockApi, pending, reply } from './test/api';

const NOW = Date.now();

function setup(b: Backstage = makeBackstage(NOW), trace?: string) {
  const api = mockApi({
    'GET /api/backstage': reply(b),
    'GET /api/backstage/trace': (c) =>
      reply({ segment: { id: c.query.get('segmentId') }, events: [{ type: 'browser.site', title: XSS_SVG }, { type: 'android.usage', package: XSS_IMG }], rules: ['Clipped to screen on'] }),
  });
  render(<BackstageView traceSegmentId={trace ?? null} onTrace={() => {}} />);
  return api;
}

const sensorRow = (name: string) => screen.getByText(name, { selector: '.sname' }).closest('button') as HTMLElement;

describe('Backstage', () => {
  it('summarises one status row per device and sensor', async () => {
    setup();
    await screen.findByText('usage', { selector: '.sname' });
    expect(screen.getAllByRole('button', { expanded: false })).toHaveLength(7);
    expect(within(sensorRow('usage')).getByText('Active')).toBeInTheDocument();
    expect(within(sensorRow('shorts')).getByText('Late')).toBeInTheDocument();
    expect(within(sensorRow('extension_chrome')).getByText('Silent')).toBeInTheDocument();
    expect(within(sensorRow('extension_brave')).getByText('Stopped as expected')).toBeInTheDocument();
    expect(within(sensorRow('usage')).getByText('6 min ago')).toBeInTheDocument();
    expect(screen.getByText('2 need attention')).toBeInTheDocument();
  });

  it('shows a stopped sensor as stopped', async () => {
    const b = makeBackstage(NOW);
    b.devices[0].sensors[1] = { name: 'notifications', state: 'stopped', lastContact: NOW - 60_000, detail: 'Notification access off' };
    setup(b);
    await screen.findByText('notifications', { selector: '.sname' });
    expect(within(sensorRow('notifications')).getByText('Stopped')).toBeInTheDocument();
    fireEvent.click(sensorRow('notifications'));
    expect(screen.getByText('A permission or a service is off on the device.')).toBeInTheDocument();
    expect(screen.getByText('Notification access off')).toBeInTheDocument();
  });

  it('expands a row into the device details', async () => {
    setup();
    fireEvent.click(await screen.findByText('usage', { selector: '.sname' }));
    expect(sensorRow('usage')).toHaveAttribute('aria-expanded', 'true');
    const stat = (label: string) => screen.getByText(label, { selector: 'dt' }).nextElementSibling;
    expect(stat('Lag p50')).toHaveTextContent('7 min');
    expect(stat('Lag p95')).toHaveTextContent('30 min');
    expect(stat('Duplicates')).toHaveTextContent('12');
    expect(stat('Rejected')).toHaveTextContent('0');
    expect(stat('Dropped fields')).toHaveTextContent('0');
    expect(stat('Interval conflicts')).toHaveTextContent('0');
    expect(stat('Invariant violations')).toHaveTextContent('0');
    expect(stat('Clock skew')).toHaveTextContent('1.2 s');
    expect(screen.getByText('Events per day')).toBeInTheDocument();
    expect(screen.getByText('5,210')).toBeInTheDocument();
    // A shutdown is an expected gap, not a hole in the data
    expect(screen.getByText('Expected (off or suspended)')).toBeInTheDocument();
    expect(screen.getAllByText('Gap')).toHaveLength(1);
  });

  it('says since which YouTube version Shorts are not classified', async () => {
    setup();
    fireEvent.click(await screen.findByText('shorts', { selector: '.sname' }));
    expect(screen.getByText('Shorts not classified since YouTube 19.41.36')).toBeInTheDocument();
    expect(screen.getByText(/Last confirmed on 19.40.33/)).toBeInTheDocument();
  });

  it('alerts when the database passes 70% of its budget', async () => {
    setup();
    expect(await screen.findByText('Database at 72% of its budget')).toBeInTheDocument();
  });

  it('does not alert below 70%', async () => {
    const b = makeBackstage(NOW);
    b.database = { sizeBytes: 5 * 1024 ** 3, budgetBytes: 10 * 1024 ** 3 };
    setup(b);
    await screen.findByText('Blind spots');
    expect(screen.queryByText(/Database at/)).not.toBeInTheDocument();
    expect(screen.getByText('of 10 GB · 50%')).toBeInTheDocument();
  });

  it('lists the blind spots and the derivation progress', async () => {
    const b = makeBackstage(NOW);
    b.derivation = { version: 2, rebuilding: true, progress: 0.25 };
    setup(b);
    expect(await screen.findByText('YouTube in the phone browser')).toBeInTheDocument();
    expect(screen.getByText('Rules version 2')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Recalculation progress' })).toHaveAttribute('aria-valuenow', '25');
  });

  it('opens the trace of a segment and shows event fields as text', async () => {
    const { find } = setup(makeBackstage(NOW), 'seg-42');
    expect(await screen.findByText(XSS_SVG)).toBeInTheDocument();
    expect(screen.getByText(XSS_IMG)).toBeInTheDocument();
    expect(find('GET', '/api/backstage/trace')[0].query.get('segmentId')).toBe('seg-42');
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
  });

  it('never shows a number while loading, and offers a retry on error', async () => {
    mockApi({ 'GET /api/backstage': pending });
    const { unmount } = render(<BackstageView />);
    expect(screen.queryByText(/\d+\s*(min|%|GB|MB)/)).not.toBeInTheDocument();
    unmount();

    mockApi({ 'GET /api/backstage': error(500, 'boom', 'Health query failed') });
    render(<BackstageView />);
    expect(await screen.findByText('Health query failed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Try again/ })).toBeInTheDocument();
  });
});
