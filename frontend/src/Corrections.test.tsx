import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CorrectionsView } from './Corrections';
import { CORRECTIONS, DEVICES } from './fixtures';
import { mockApi, reply } from './test/api';

function setup() {
  const api = mockApi({
    'GET /api/devices': reply(DEVICES),
    'GET /api/corrections': reply(CORRECTIONS),
    'POST /api/corrections/redactions': reply({ id: 'r-2' }),
    'POST /api/corrections/exclusions': reply({ id: 'x-2' }),
    'DELETE /api/corrections/exclusions/*': reply(),
  });
  render(<CorrectionsView />);
  return api;
}

const card = (title: string) => screen.getByRole('heading', { name: title }).closest('section') as HTMLElement;

function fillRange(scope: HTMLElement, type = 'browser.site') {
  fireEvent.change(within(scope).getByLabelText('Device'), { target: { value: 'd-desk' } });
  fireEvent.change(within(scope).getByLabelText('Event type'), { target: { value: type } });
  fireEvent.change(within(scope).getByLabelText('From'), { target: { value: '2026-10-01T10:00' } });
  fireEvent.change(within(scope).getByLabelText('To'), { target: { value: '2026-10-01T12:00' } });
}

describe('Corrections', () => {
  it('redacts a field only after typing the confirmation word', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Redaction' });
    const redaction = card('Redaction');
    fillRange(redaction);
    expect(within(redaction).getByRole('radio', { name: /title field/ })).toBeChecked();
    fireEvent.click(within(redaction).getByRole('button', { name: /Review redaction/ }));

    const dialog = screen.getByRole('dialog', { name: 'Redact for good?' });
    expect(within(dialog).getByText(/Database dumps in the backups and the collectors' local queues/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Redact' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Type REDACT to confirm/), { target: { value: 'redact' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Type REDACT to confirm/), { target: { value: 'REDACT' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(find('POST', '/api/corrections/redactions')).toHaveLength(1));
    expect(find('POST', '/api/corrections/redactions')[0].body).toEqual({
      deviceId: 'd-desk',
      type: 'browser.site',
      from: new Date('2026-10-01T10:00').getTime(),
      to: new Date('2026-10-01T12:00').getTime(),
      field: 'title',
    });
  });

  it('sends a null field to delete whole events', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Redaction' });
    const redaction = card('Redaction');
    fillRange(redaction, 'desktop.activity');
    expect(within(redaction).getByRole('radio', { name: 'The whole events' })).toBeChecked();
    fireEvent.click(within(redaction).getByRole('button', { name: /Review redaction/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Type REDACT/), { target: { value: 'REDACT' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Redact' }));
    await waitFor(() => expect(find('POST', '/api/corrections/redactions')).toHaveLength(1));
    expect((find('POST', '/api/corrections/redactions')[0].body as { field: unknown }).field).toBeNull();
  });

  it('refuses a period that ends before it starts', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Redaction' });
    const redaction = card('Redaction');
    fillRange(redaction);
    fireEvent.change(within(redaction).getByLabelText('To'), { target: { value: '2026-10-01T09:00' } });
    fireEvent.click(within(redaction).getByRole('button', { name: /Review redaction/ }));
    expect(within(redaction).getByText('The period must end after it starts.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(find('POST', /redactions/)).toHaveLength(0);
  });

  it('lists exclusions with an undo', async () => {
    const { find } = setup();
    expect(await screen.findByText('Phone lent to a friend')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Undo/ }));
    await waitFor(() => expect(find('DELETE', '/api/corrections/exclusions/x-1')).toHaveLength(1));
  });

  it('creates an exclusion with a note', async () => {
    const { find } = setup();
    await screen.findByRole('heading', { name: 'Exclusions' });
    const exclusions = card('Exclusions');
    fillRange(exclusions, 'android.usage');
    fireEvent.change(within(exclusions).getByLabelText('Note'), { target: { value: 'Testing a launcher' } });
    fireEvent.click(within(exclusions).getByRole('button', { name: 'Exclude' }));
    await waitFor(() => expect(find('POST', '/api/corrections/exclusions')).toHaveLength(1));
    expect(find('POST', '/api/corrections/exclusions')[0].body).toMatchObject({ deviceId: 'd-desk', type: 'android.usage', note: 'Testing a launcher' });
  });

  it('shows the redaction audit without content', async () => {
    setup();
    await screen.findByRole('heading', { name: 'Audit' });
    const audit = screen.getByRole('heading', { name: 'Audit' }).nextElementSibling as HTMLElement;
    const item = within(audit).getByRole('listitem');
    expect(item).toHaveTextContent(/^Desk · browser\.site · field title cleared/);
  });
});
