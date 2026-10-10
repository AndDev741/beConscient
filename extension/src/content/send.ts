import type { ContentMessage } from '../types';

/**
 * Sends a message to the background. Returns false once the extension was
 * reloaded or removed, after which this content script should stop.
 */
export function sendToBackground(msg: ContentMessage): boolean {
  try {
    if (!chrome.runtime?.id) return false;
    const pending = chrome.runtime.sendMessage(msg) as Promise<unknown> | undefined;
    pending?.catch(() => undefined);
    return true;
  } catch {
    return false;
  }
}
