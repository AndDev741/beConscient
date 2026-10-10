// Popup: connection status only. It shows no measured data.
import type { ConnectionStatus } from '../types';

const STATUS_KEY = 'status';

function formatTime(at: number | null | undefined): string {
  if (!at) return 'never';
  return new Date(at).toLocaleTimeString();
}

function render(status: Partial<ConnectionStatus> | undefined): void {
  const agent = document.getElementById('agent');
  const heartbeat = document.getElementById('heartbeat');
  const error = document.getElementById('error');
  if (!agent || !heartbeat || !error) return;
  const connected = status?.connected === true;
  agent.textContent = connected ? 'reachable' : 'not reachable';
  agent.className = connected ? 'ok' : 'bad';
  heartbeat.textContent = formatTime(status?.lastHeartbeatAt);
  error.textContent = !connected && status?.lastError ? status.lastError : '';
}

async function load(): Promise<void> {
  const got = await chrome.storage.local.get(STATUS_KEY);
  render(got[STATUS_KEY] as Partial<ConnectionStatus> | undefined);
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes[STATUS_KEY]) {
    render(changes[STATUS_KEY].newValue as Partial<ConnectionStatus> | undefined);
  }
});

void load();
