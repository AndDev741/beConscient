# beConscient web page

The page you open to see your screen time. React 19, Vite and TypeScript (strict), no router, hand-written CSS and hand-built SVG charts. It talks only to the backend API in [`../contract/api.md`](../contract/api.md).

## Run it

```bash
npm install
npm run dev        # http://localhost:5173, proxies /api to the backend on localhost:8080
npm run dev:mock   # same page, answered from fixtures in the browser: no backend needed
npx vitest run     # tests (also: npm test)
npm run build      # tsc --noEmit && vite build, output in dist/
```

`VITE_MOCK=1 npm run dev` is the same as `npm run dev:mock`. Mock mode keeps its state in memory: mappings, categories, windows and days off change as you edit them, a new pairing code "pairs" after about eight seconds so you can see the fingerprint step, and a reload starts over. A normal build drops the mock code entirely.

## Views

| Tab | What it shows |
|---|---|
| **Today** | Default after sign-in. The headline: awake hours away from screens, with "in progress" for today and "incomplete" naming the silent sensors. Screen time while awake and outside the awake window on their own lines. Focus periods and pause screens from the phone's launcher, with focus periods per day over the last week, once the launcher has been used. Then the hour-by-hour chart stacked by category, time by category (work against the rest), by device with the overlap, top apps and sites, unlocks with first apps, and notifications per app. "Data still arriving" and "Recalculating" banners when they apply. |
| **Trends** | Day, week or month against the previous period, per-day chart with left-out days drawn dashed, and categories against the previous period. Says "No previous period" when there is none. |
| **Categories** | Create, rename, recolour and delete categories (409 `category_in_use` is explained). A searchable list of apps and sites sorted by time, Uncategorized first, each with its category picker and an inline "new category". |
| More › **Devices** | Add an Android phone or a Linux computer, the pairing code shown once with its expiry and a copy button, the new installation's fingerprint once the collector pairs, installations with fingerprints, revoke with confirmation, re-pair, a confirmation before a second phone, and the pairing lockout. |
| More › **Settings** | Work window (weekdays, start, end), awake window (may cross midnight), days off with undo, version history, and the notice that changes apply from today. |
| More › **Backstage** | One status row per device and sensor that expands into the details: events per day, lag p50 and p95, duplicates, rejected, dropped fields, interval conflicts, invariant violations, clock skew, gaps (expected ones labelled), Shorts confirmation. Database size against budget with an alert from 70%, derivation progress, blind spots, and the trace of a segment. |
| More › **Validation** | Enter the Android Settings total in hours and minutes for a closed day; see measured, raw and entered side by side with the difference. Days more than 10% apart are highlighted. Today and days not closed are refused. |
| More › **Corrections** | Exclusions with undo. Redaction of a field or of whole events, behind a dialog that needs the word REDACT typed and reminds you that backups and collector queues hold copies too. The redaction audit. |

Every view has a loading skeleton with no digits in it and a failure state with a retry. Neither ever shows a zero.

## Layout

```
src/
  api.ts          types from api.md and one method per endpoint; any 401 sends you to sign-in
  hooks.ts        useLoad (data only for the current key), useTheme, useInterval, …
  ui.tsx          cards, notices, skeleton, error with retry, confirm dialog, undo bar
  charts.tsx      hourly stacked chart, trend chart, bar list
  format.ts       durations, dates, colour check
  App.tsx         shell, tabs, More menu
  Day.tsx Trends.tsx Categories.tsx Devices.tsx Settings.tsx
  Backstage.tsx Validation.tsx Corrections.tsx Login.tsx
  fixtures.ts     typed fixtures shared by tests and mock mode
  mock.ts         the in-browser fake backend for mock mode
  *.test.ts(x)    Vitest + Testing Library (jsdom)
nginx.conf                   template rendered at container start
nginx/security-headers.conf  CSP and friends, included per location
Dockerfile                   node build, then nginx
```

## Phone first

The page is laid out for a phone and widens on a computer. Below 768 px the tabs move to a bottom bar and More opens as a sheet. The hourly chart keeps a minimum column width and scrolls sideways, starting at the latest hour with data. Values appear when you tap a bar (or press Enter on it); nothing depends on hover. Every colour in a chart has a text label next to it in the legend and in the tapped detail, and sensor states and flags carry words as well as colour.

Light and dark follow the system. The moon or sun button overrides it and the choice is kept in `localStorage` (wrapped so a private window still works).

## Security

Video titles and package names come from events, and anyone can choose a YouTube title. So:

- nothing is ever inserted as HTML: no `dangerouslySetInnerHTML`, no `innerHTML`, and the charts are SVG built from React elements. A test scans the source for these;
- category colours reach the drawing only if they match `#rrggbb`;
- the page loads no third-party script, style or font;
- nginx sends `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; … frame-ancestors 'none'` plus `nosniff`, `no-referrer` and `X-Frame-Options: DENY`.

Tests render titles like `<img src=x onerror=alert(1)>` and `</svg><script>alert(1)</script>` in the day view, the categories list, a chart label and the trace view, and check they stay text.

## Deploying

```bash
docker build -t beconscient-frontend .
docker run -p 8080:8080 -e BACKEND_UPSTREAM=beconscient-backend:80 beconscient-frontend
```

`BACKEND_UPSTREAM` (host:port, default `beconscient-backend:80`) is where `/api/` and `/ingest/` go. The nginx image renders `nginx.conf` with only that variable substituted.

| Path | Behaviour |
|---|---|
| `/api/` | proxied to the backend, `no-store` |
| `/ingest/` | proxied with the body untouched, `client_max_body_size 1m` (413 above it) |
| `/assets/` | hashed files, cached for a year, `immutable` |
| `/healthz` | `200 ok` |
| anything else | `index.html`, `no-cache` |

## Contract notes

The page is built against `api.md` with typed fixtures. Where the contract leaves a shape or a unit open, the page does this:

- **Trace from a segment.** No endpoint returns segment ids, so nothing on the day view can name one. The page reads an optional `hours[].segments: [{ id, label, minutes }]` and, when present, lists them in the tapped hour with a link to the trace. Backstage also has a field to trace an id by hand. The trace response is rendered generically as a key/value tree because its shape is not specified.
- **Timestamps** for installations (`createdAt`, `lastSeenAt`, `revokedAt`), the pairing `expiresAt`, and correction rows are read as epoch milliseconds or ISO strings. Redactions and exclusions are sent with `from` and `to` in epoch milliseconds.
- **`silentSensors`** is read as a list of display strings, such as `"activity (Desk)"`.
- **`diffPct`** has no stated unit, so the page computes the percentage from `measuredMinutes` and `settingsMinutes` and uses `flagged` from the server for the highlight.
- **Settings `history[].value`** is read as `{ days?, start, end }`.
- **Correction rows** are read as `{ id, deviceId, type, from, to, field | note, createdAt? }`.
- **Built-in categories** keep their name in the UI (only the colour changes) and cannot be deleted. `Uncategorized` and `Unattributed` are not offered as mapping targets: "no mapping" (`categoryId: null`) is how a subject becomes Uncategorized.
- **Shorts.** `shorts.unconfirmedSinceVersion` says since which YouTube version; there is no field for since when.
- **Work window** must end after it starts on the same day; the awake window may cross midnight.
