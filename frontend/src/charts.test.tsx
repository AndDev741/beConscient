import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CategoryTotal, Hour, SeriesPoint } from './api';
import { BarList, HourlyChart, TrendChart } from './charts';
import { XSS_IMG, XSS_SVG } from './test/api';

const cats: CategoryTotal[] = [
  { categoryId: 'a', name: 'Work', color: '#5b6cff', minutes: 90 },
  { categoryId: 'b', name: XSS_SVG, color: 'red;}</style><script>alert(1)</script>', minutes: 30 },
];

const hours: Hour[] = Array.from({ length: 24 }, (_, i) => ({
  label: `${String(i).padStart(2, '0')}:00`,
  start: 1760050800000 + i * 3_600_000,
  byCategory: i === 9 ? [{ categoryId: 'a', minutes: 45 }, { categoryId: 'b', minutes: 10 }] : i === 10 ? [{ categoryId: 'a', minutes: 45 }, { categoryId: 'b', minutes: 20 }] : [],
}));

describe('HourlyChart', () => {
  it('draws one selectable column per hour', () => {
    render(<HourlyChart hours={hours} categories={cats} />);
    expect(screen.getAllByRole('button', { name: /^\d\d:00, / })).toHaveLength(24);
    expect(screen.getByRole('button', { name: '09:00, 55 min' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('reveals values on tap or keyboard, not on hover only', () => {
    render(<HourlyChart hours={hours} categories={cats} />);
    expect(screen.getByText('Tap a bar to see that hour.')).toBeInTheDocument();

    const col = screen.getByRole('button', { name: '09:00, 55 min' });
    fireEvent.keyDown(col, { key: 'Enter' });
    expect(col).toHaveAttribute('aria-pressed', 'true');
    const detail = document.querySelector('.hdetail') as HTMLElement;
    expect(within(detail).getByText('09:00 – 10:00')).toBeInTheDocument();
    expect(within(detail).getByText('45 min')).toBeInTheDocument();
    expect(within(detail).getByText('10 min')).toBeInTheDocument();

    fireEvent.click(col);
    expect(document.querySelector('.hdetail')).toBeNull();
  });

  it('labels every colour in the legend with text', () => {
    render(<HourlyChart hours={hours} categories={cats} />);
    const legend = screen.getByRole('list', { name: 'Legend' });
    expect(within(legend).getByText('Work')).toBeInTheDocument();
    expect(within(legend).getByText(XSS_SVG)).toBeInTheDocument();
  });

  it('keeps a hostile category name as text in labels and tooltips', () => {
    render(<HourlyChart hours={hours} categories={cats} />);
    fireEvent.click(screen.getByRole('button', { name: '10:00, 1 h 5 min' }));
    expect(screen.getAllByText(XSS_SVG).length).toBe(2);
    const titles = [...document.querySelectorAll('svg title')].map((t) => t.textContent);
    expect(titles).toContain(`${XSS_SVG}: 20 min`);
    expect(document.querySelector('script')).toBeNull();
  });

  it('never puts an unchecked colour string into the drawing', () => {
    const { container } = render(<HourlyChart hours={hours} categories={cats} />);
    const fills = [...container.querySelectorAll('rect[fill]')].map((r) => r.getAttribute('fill'));
    expect(fills).toContain('#5b6cff');
    expect(fills).toContain('var(--neutral-data)');
    expect(fills.some((f) => f?.includes('script'))).toBe(false);
  });

  it('keeps a minimum column width so the chart scrolls on a phone instead of squeezing', () => {
    const { container } = render(<HourlyChart hours={hours} categories={cats} />);
    const svg = container.querySelector('svg.hsvg') as SVGSVGElement;
    expect(Number(svg.getAttribute('width'))).toBeGreaterThanOrEqual(24 * 22);
    expect(container.querySelector('.hscroll')).not.toBeNull();
  });
});

describe('TrendChart', () => {
  const series: SeriesPoint[] = [
    { date: '2026-10-05', screenMinutes: 380, awayMinutes: 580, complete: true },
    { date: '2026-10-06', screenMinutes: 400, awayMinutes: 560, complete: false },
  ];

  it('marks days left out of the comparison in words, not only by style', () => {
    render(<TrendChart series={series} metric="awayMinutes" label="Away" />);
    const out = screen.getByRole('button', { name: '2026-10-06, 9 h 20 min, left out of the comparison' });
    fireEvent.click(out);
    expect(screen.getByText(/Left out of the comparison: the day is not closed/)).toBeInTheDocument();
    expect(screen.getByText('Left out (not closed, partial or incomplete)')).toBeInTheDocument();
  });
});

describe('BarList', () => {
  it('writes the name, the time and the share', () => {
    render(<BarList total={120} items={[{ id: 'x', name: XSS_IMG, minutes: 90, color: '#5b6cff' }, { id: 'y', name: 'Rest', minutes: 30 }]} />);
    expect(screen.getByText(XSS_IMG)).toBeInTheDocument();
    expect(screen.getByText('1 h 30 min')).toBeInTheDocument();
    expect(screen.getByText('75%')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });
});
