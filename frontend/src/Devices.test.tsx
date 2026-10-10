import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Device } from './api';
import { Devices } from './Devices';
import { DEVICES } from './fixtures';
import { error, mockApi, reply, type Handler } from './test/api';

const fresh: Device = { id: 'd-new', name: 'Tablet', kind: 'android', installations: [] };

function setup(routes: Record<string, Handler | ReturnType<typeof reply>> = {}, list: Device[] = DEVICES) {
  const api = mockApi({
    'GET /api/devices': reply(list),
    'GET /api/pairing-codes/state': reply({ lockedOut: false, failures: 0 }),
    ...routes,
  });
  const view = render(<Devices pollMs={20} />);
  return { ...api, ...view };
}

async function create(name: string, kind: 'Android phone' | 'Linux computer' = 'Android phone') {
  fireEvent.change(await screen.findByLabelText('Name'), { target: { value: name } });
  fireEvent.click(screen.getByRole('radio', { name: kind }));
  fireEvent.click(screen.getByRole('button', { name: 'Create a pairing code' }));
}

describe('Devices', () => {
  it('lists installations with their fingerprints and state', async () => {
    setup({}, [
      ...DEVICES,
      { id: 'd-x', name: 'Old phone', kind: 'android', installations: [{ id: 'i-x', fingerprint: 'ZZZZ-9999', createdAt: 1, lastSeenAt: null, revokedAt: null, cloneSuspected: true }] },
    ]);
    expect(await screen.findByText('R4WN-8HTC')).toBeInTheDocument();
    expect(screen.getByText('K7QX-M2PD')).toBeInTheDocument();
    expect(screen.getByText('B9FJ-2LZE')).toBeInTheDocument();
    expect(screen.getAllByText('Revoked').length).toBe(1);
    expect(screen.getByText('Possible clone')).toBeInTheDocument();
  });

  it('shows the pairing code once; a reload does not show it again', async () => {
    const { unmount } = setup({
      'POST /api/devices': reply({ device: { ...fresh, kind: 'desktop' }, code: 'QWER-TYUI', expiresAt: Date.now() + 15 * 60_000 }),
    });
    await create('Laptop', 'Linux computer');
    expect(await screen.findByTestId('pairing-code')).toHaveTextContent('QWER-TYUI');
    expect(screen.getByText(/Expires in 1[45]:\d\d/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy' })).toBeInTheDocument();

    unmount();
    setup();
    await screen.findByText('R4WN-8HTC');
    expect(screen.queryByTestId('pairing-code')).not.toBeInTheDocument();
    expect(screen.queryByText('QWER-TYUI')).not.toBeInTheDocument();
  });

  it('shows the new installation fingerprint when the collector pairs', async () => {
    let paired = false;
    setup({
      'POST /api/devices': () => {
        setTimeout(() => (paired = true), 30);
        return reply({ device: fresh, code: 'AAAA-BBBB', expiresAt: Date.now() + 15 * 60_000 });
      },
      'GET /api/devices': () =>
        reply(paired ? [...DEVICES, { ...fresh, installations: [{ id: 'i-new', fingerprint: 'NEW1-FP22', createdAt: 1, lastSeenAt: 2, revokedAt: null, cloneSuspected: false }] }] : DEVICES),
      'POST /api/devices/*/pairing-code': reply({}),
    }, []);
    await create('Tablet');
    await screen.findByTestId('pairing-code');
    expect(await screen.findByTestId('new-fingerprint')).toHaveTextContent('NEW1-FP22');
  });

  it('asks before pairing a second phone, and only then confirms it to the server', async () => {
    const { find } = setup({
      'POST /api/devices': (c) =>
        (c.body as { confirmSecondPhone: boolean }).confirmSecondPhone
          ? reply({ device: fresh, code: 'SECO-ND22', expiresAt: Date.now() + 900_000 })
          : error(409, 'second_phone'),
    });
    await create('Tablet');
    const dialog = await screen.findByRole('dialog', { name: 'Pair a second phone?' });
    expect(screen.queryByTestId('pairing-code')).not.toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Pair a second phone' }));
    expect(await screen.findByTestId('pairing-code')).toHaveTextContent('SECO-ND22');
    const posts = find('POST', '/api/devices');
    expect(posts.map((p) => (p.body as { confirmSecondPhone: boolean }).confirmSecondPhone)).toEqual([false, true]);
  });

  it('does nothing when the second phone is not confirmed', async () => {
    const { find } = setup({ 'POST /api/devices': error(409, 'second_phone') });
    await create('Tablet');
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(find('POST', '/api/devices')).toHaveLength(1);
    expect(screen.queryByTestId('pairing-code')).not.toBeInTheDocument();
  });

  it('shows the pairing lockout', async () => {
    setup({ 'GET /api/pairing-codes/state': reply({ lockedOut: true, failures: 10 }) });
    expect(await screen.findByText('Pairing is locked')).toBeInTheDocument();
    expect(screen.getByText(/10 pairing attempts in a row failed/)).toBeInTheDocument();
  });

  it('revokes an installation only after confirmation', async () => {
    const { find } = setup({ 'DELETE /api/installations/*': reply() });
    await screen.findByText('R4WN-8HTC');
    const revokeButtons = screen.getAllByRole('button', { name: 'Revoke' });
    fireEvent.click(revokeButtons[0]);
    let dialog = screen.getByRole('dialog', { name: 'Revoke this installation?' });
    expect(within(dialog).getByText('R4WN-8HTC')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(find('DELETE', /installations/)).toHaveLength(0);

    fireEvent.click(revokeButtons[0]);
    dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(find('DELETE', '/api/installations/i-phone-2')).toHaveLength(1));
  });

  it('re-pairs an existing device with a fresh code', async () => {
    const { find } = setup({
      'POST /api/devices/*/pairing-code': reply({ code: 'REPA-IR33', expiresAt: Date.now() + 900_000 }),
    });
    await screen.findByText('R4WN-8HTC');
    fireEvent.click(screen.getAllByRole('button', { name: 'Re-pair' })[0]);
    const dialog = screen.getByRole('dialog', { name: 'Re-pair Phone?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create a pairing code' }));
    expect(await screen.findByTestId('pairing-code')).toHaveTextContent('REPA-IR33');
    expect(find('POST', '/api/devices/d-phone/pairing-code')).toHaveLength(1);
  });

  it('asks for a name before creating', async () => {
    const { find } = setup();
    fireEvent.click(await screen.findByRole('button', { name: 'Create a pairing code' }));
    expect(screen.getByText(/Give the device a name/)).toBeInTheDocument();
    expect(find('POST', '/api/devices')).toHaveLength(0);
  });
});
