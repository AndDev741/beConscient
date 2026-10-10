import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Subject } from './api';
import { Categories } from './Categories';
import { CATEGORIES, SUBJECTS } from './fixtures';
import { XSS_IMG, XSS_SVG, error, mockApi, reply } from './test/api';

function setup(subjects: Subject[] = SUBJECTS, extra: Parameters<typeof mockApi>[0] = {}) {
  const onChanged = vi.fn();
  const api = mockApi({
    'GET /api/categories': reply(CATEGORIES),
    'GET /api/subjects': (c) => {
      const q = (c.query.get('q') ?? '').toLowerCase();
      return reply(subjects.filter((s) => !q || s.label.toLowerCase().includes(q)));
    },
    ...extra,
  });
  render(<Categories onChanged={onChanged} />);
  return { ...api, onChanged };
}

const groupList = (title: string) => {
  const heading = screen.getByRole('heading', { name: new RegExp(`^${title}`) });
  return heading.nextElementSibling as HTMLElement;
};

describe('Categories', () => {
  it('lists Uncategorized first, each group sorted by time', async () => {
    setup();
    await screen.findByRole('heading', { name: /^Uncategorized/ });
    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(headings[0]).toMatch(/^Uncategorized/);
    expect(headings[1]).toMatch(/^Categorized/);

    const unmapped = within(groupList('Uncategorized')).getAllByRole('listitem').map((li) => li.querySelector('.label')?.textContent);
    expect(unmapped).toEqual(['LinkedIn', 'org.example.reader', 'com.android.chrome', 'com.spotify.music', 'com.duolingo']);
  });

  it('searches on the server', async () => {
    const { calls } = setup();
    await screen.findByRole('heading', { name: /^Uncategorized/ });
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search apps and sites' }), { target: { value: 'spot' } });
    await waitFor(() => expect(screen.queryByText('LinkedIn')).not.toBeInTheDocument());
    expect(await screen.findByText('com.spotify.music')).toBeInTheDocument();
    expect(screen.queryByText('com.whatsapp')).not.toBeInTheDocument();
    expect(calls.some((c) => c.path === '/api/subjects' && c.query.get('q') === 'spot')).toBe(true);
  });

  it('maps a subject and tells the rest of the page', async () => {
    const { find, onChanged } = setup(SUBJECTS, {
      'PUT /api/subjects/*/category': (c) => reply({ ...SUBJECTS[5], categoryId: (c.body as { categoryId: string }).categoryId }),
    });
    const select = await screen.findByRole('combobox', { name: 'Category for LinkedIn' });
    // Uncategorized and Unattributed are not destinations; Work is
    const options = within(select).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Uncategorized', 'Work', 'Personal computer', 'Social', 'Video', 'Messaging', '+ New category…']);

    fireEvent.change(select, { target: { value: 'c-social' } });
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    const put = find('PUT', '/api/subjects/site:linkedin/category');
    expect(put).toHaveLength(1);
    expect(put[0].body).toEqual({ categoryId: 'c-social' });
    expect(select).toHaveValue('c-social');
  });

  it('creates a category inline from the mapping control', async () => {
    const created = { id: 'c-new', name: 'Reading', color: '#5b6cff', builtin: false };
    const { find } = setup(SUBJECTS, {
      'POST /api/categories': reply(created, 201),
      'PUT /api/subjects/*/category': reply({ ...SUBJECTS[6], categoryId: 'c-new' }),
    });
    const select = await screen.findByRole('combobox', { name: 'Category for org.example.reader' });
    const row = select.closest('li') as HTMLElement;
    fireEvent.change(select, { target: { value: '__new__' } });
    fireEvent.change(within(row).getByLabelText('Name'), { target: { value: 'Reading' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(find('PUT', '/api/subjects/app:org.example.reader/category')).toHaveLength(1));
    expect(find('POST', '/api/categories')[0].body).toEqual({ name: 'Reading', color: '#5b6cff' });
    expect(find('PUT', '/api/subjects/app:org.example.reader/category')[0].body).toEqual({ categoryId: 'c-new' });
  });

  it('explains why a category in use cannot be deleted', async () => {
    setup(SUBJECTS, { 'DELETE /api/categories/*': error(409, 'category_in_use') });
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Social' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/"Social" still has apps or sites mapped to it/)).toBeInTheDocument();
  });

  it('renames and recolours a category, but keeps built-in names', async () => {
    const { find } = setup(SUBJECTS, { 'PUT /api/categories/*': reply({ ...CATEGORIES[4], name: 'Feeds' }) });
    expect(screen.queryByRole('button', { name: 'Delete Work' })).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole('button', { name: 'Edit Social' }));
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Feeds' } });
    fireEvent.click(screen.getByRole('radio', { name: 'Colour 3' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(find('PUT', '/api/categories/c-social')).toHaveLength(1));
    expect(find('PUT', '/api/categories/c-social')[0].body).toEqual({ name: 'Feeds', color: '#d9822b' });

    fireEvent.click(screen.getByRole('button', { name: 'Edit Work' }));
    expect(screen.getByLabelText('Name')).toBeDisabled();
  });

  it('refuses a duplicate category name', async () => {
    const { find } = setup();
    fireEvent.click(await screen.findByRole('button', { name: /New category/ }));
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'social' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('There is already a category called "Social".')).toBeInTheDocument();
    expect(find('POST', '/api/categories')).toHaveLength(0);
  });

  it('shows hostile labels as plain text', async () => {
    setup([
      { key: 'site:youtube', kind: 'site', label: XSS_IMG, categoryId: null, minutes30d: 50 },
      { key: 'app:x', kind: 'app', label: XSS_SVG, categoryId: 'c-video', minutes30d: 10 },
    ]);
    expect(await screen.findByText(XSS_IMG)).toBeInTheDocument();
    expect(screen.getByText(XSS_SVG)).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: `Category for ${XSS_IMG}` })).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
  });
});
