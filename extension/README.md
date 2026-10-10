# beConscient browser extension

Manifest V3 extension for Chrome and Brave. It measures time with YouTube or LinkedIn in the active tab and hands it to the beConscient desktop agent over native messaging. It never sends a URL. YouTube intervals carry the video title. LinkedIn intervals carry only a coarse page kind (`feed`, `messaging`, `profile`, `jobs`, `other`).

The wire format is owned by [`contract/native-messaging.md`](../contract/native-messaging.md) and the `browser.site` fields in [`contract/events.md`](../contract/events.md). The counting rules are in [`docs/design.md`](../docs/design.md).

## Build

Requires Node 20 or newer.

```sh
cd extension
npm install
npm run build   # type check, then bundle into dist/
npm test        # Vitest with chrome.* mocks
```

`npm run build` writes the unpacked extension to `extension/dist/`:

```text
dist/
  manifest.json
  background.js          service worker
  content/youtube.js     content script for https://www.youtube.com/*
  content/linkedin.js    content script for https://www.linkedin.com/*
  popup/popup.html       connection status
  popup/popup.js
```

## Load it

1. Open `chrome://extensions` (in Brave, `brave://extensions`).
2. Turn on **Developer mode** in the top right corner.
3. Click **Load unpacked** and pick the `extension/dist` folder.
4. Check that the card shows the id `hhddmdmnpejagnnmenjdgdifpimneeok`.
5. Reload any YouTube or LinkedIn tab that was open before. Chrome injects content scripts only into pages loaded after the extension.

After a rebuild, click the reload arrow on the extension card.

## How the id is fixed

Chrome derives an extension's id from its public key: the first 128 bits of the SHA-256 of the DER key, written with the letters `a` to `p` instead of hex digits. `manifest.json` carries the public key from `contract/native-messaging.md` in its `key` field, so every unpacked copy, on any machine, gets the id `hhddmdmnpejagnnmenjdgdifpimneeok`. The native host manifests installed by the desktop agent allow only `chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok/`. `src/manifest.test.ts` recomputes the id from the key and fails if it drifts.

## Permissions

| Permission | Why |
|---|---|
| `nativeMessaging` | Talk to the desktop agent. |
| `storage` | Keep state across service worker restarts and buffer unacknowledged intervals. |
| `alarms` | The 5-minute heartbeat. |
| `https://www.youtube.com/*`, `https://www.linkedin.com/*` | Run the content scripts on those two sites. |

There is no `tabs` permission, no `<all_urls>` and no `externally_connectable`. The tests check the built manifest for that. Tab and window focus come from `chrome.tabs.onActivated`, `chrome.windows.onFocusChanged` and the `sender.tab` of content script messages, none of which need `tabs`.

## What counts

A tab counts while it is the active tab of its window and either its window has focus or a video plays in it.

- The window has focus when `chrome.windows.onFocusChanged` says so, or when the page's own `document.hasFocus()` is true. `WINDOW_ID_NONE` means no browser window has focus.
- Playing means a `<video>` on YouTube with `!paused && !ended && readyState > 2`. A short buffering stall keeps the previous answer. Hover previews on the YouTube home page are ignored. LinkedIn never reports playing, because its feed videos autoplay muted.
- A new interval starts when site, title, page kind, playing or focused changes. A playing video in an unfocused window therefore becomes its own interval with `playing: true, focused: false`, which the agent keeps only while it is Passive.
- A tab whose content script has been silent for 20 seconds is closed at its last report.

The agent clips these intervals to its own active time, so lock, idle and the 2-hour cap apply to them too.

## Parts

| File | What |
|---|---|
| `src/content/reporter.ts` | Shared content script loop. Reports page state on change (visibility, focus, blur, media events, SPA navigation, title changes) and every 5 seconds while the page is visible or a video plays. Says `gone` on `pagehide`. |
| `src/content/youtubePage.ts` | Reads the cleaned video title (only on `/watch`, `/shorts/` and `/live/` pages) and the playing state. After an in-page navigation it waits up to 3 seconds for YouTube to update `document.title`, so the old title is not sent for the new video. |
| `src/content/linkedinPage.ts` | Maps `location.pathname` to a page kind. Never reads the title. |
| `src/intervals.ts` | Pure interval tracker: the counting rule, splits and closes. |
| `src/outbox.ts` | Pure buffer and ack logic for unacknowledged intervals. |
| `src/worker.ts` | Service worker: wires browser events to the tracker, stores state, runs the native port. |
| `src/sanitize.ts` | Validates content script messages. The site comes from `sender.origin`; LinkedIn messages lose any title. |
| `src/popup/` | Shows whether the agent's host is reachable and when the last heartbeat went out. No measured data. |

## Talking to the agent

The service worker opens `chrome.runtime.connectNative("org.beconscient.agent")`. The browser starts the agent binary in host mode for that connection, and the host forwards each message to the running agent.

Messages to the host:

- `site`: one revision of an interval. Each interval has a UUIDv7 `id` that never changes and a `rev` that grows with every rewrite. The first revision goes out once the interval has any length, a closing revision goes out at once, and a growing interval is resent at most every 30 seconds. Every message carries `tz` (the IANA zone) and `tz_offset_min` (UTC offset in minutes, positive east) from the moment the interval started.
- `playing`: sent when video playback starts or stops in an active tab, and on every new connection.
- `heartbeat`: sent on every new connection and every 5 minutes from the `heartbeat` alarm. The agent reports it as the `extension_chrome` or `extension_brave` sensor. The browser is `brave` when `navigator.brave` exists (or the UA client hints list a Brave brand), otherwise `chrome`.

Every `site` revision is first written to `chrome.storage.local` under `ob:<id>`. The host answers `{ "t": "ack", "ids": [...] }` after it has written the intervals to the agent's queue, and the extension drops an entry once its newest revision is acknowledged. When the port closes, the entries stay. The next connection resends all of them. Reconnects back off from 5 seconds to 5 minutes; the heartbeat alarm also retries. Entries sent but not acknowledged within 5 minutes are sent again. The buffer holds at most 5000 closed intervals while the agent is unreachable; the oldest go first.

## Service worker lifetime

Chrome stops an idle service worker after 30 seconds. Nothing measured lives in memory:

- `chrome.storage.session` holds the tracker: open intervals, the focused window, the active tab of each window, and the last state of each tab.
- `chrome.storage.local` holds the outbox and the connection status.

Every event loads that state, applies itself and saves it back, one event at a time. After a restart the next report from a tab continues the same interval id with a higher `rev`. The only things kept in memory are the native port and the event queue. An open native port also keeps the worker alive.
