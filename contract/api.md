# Page API

The web page talks to the backend under `/api/*`. Every endpoint except login, session and logout needs the session cookie. JSON uses camelCase. Dates are local dates `YYYY-MM-DD` in the user timeline zone (the phone's zone at that time, `Europe/Lisbon` as fallback). Durations are whole minutes unless the field name says otherwise. Times of day are `HH:MM`.

Errors: `{ "error": "<code>", "message": "<human text>" }` with a matching HTTP status.

Every timestamp in requests and responses (for example `createdAt`, `expiresAt`, `lastSeenAt`, `lastContact`, correction `from` and `to`) is epoch milliseconds.

## Session

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/login` | form `j_username`, `j_password` | 200 and the session cookie, or 401 |
| GET | `/api/session` | | `{ "username": "owner" }` or 401 |
| POST | `/api/logout` | | 204, cookie expired |

Login and pairing share a global rate limiter with exponential backoff capped at 30 seconds.

## Day

`GET /api/day?date=2026-10-10`

```json
{
  "date": "2026-10-10",
  "zone": "Europe/Lisbon",
  "state": "closed",
  "derivation": { "rebuilding": false, "progress": 1.0 },
  "headline": {
    "awakeMinutes": 960,
    "awayMinutes": 610,
    "screenMinutes": 350,
    "outsideAwakeScreenMinutes": 35,
    "elapsedOnly": false,
    "incomplete": false,
    "silentSensors": []
  },
  "totals": {
    "screenMinutes": 385,
    "overlapMinutes": 22,
    "byDevice": [ { "deviceId": "…", "name": "Phone", "kind": "android", "minutes": 240 } ]
  },
  "byCategory": [ { "categoryId": "…", "name": "Work", "color": "#5b6cff", "minutes": 120 } ],
  "hours": [
    { "label": "09:00", "start": 1760083200000, "byCategory": [ { "categoryId": "…", "minutes": 45 } ],
      "segments": [ { "id": "…", "label": "com.whatsapp", "minutes": 12 } ] }
  ],
  "topSubjects": [ { "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "categoryId": "…", "minutes": 41 } ],
  "unlocks": { "count": 57, "firstApps": [ { "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "count": 19 } ] },
  "notifications": [ { "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "posted": 88, "tapped": 21, "dismissed": 30 } ],
  "computerLabels": [ { "id": "…", "categoryId": "…", "from": 1760122800000, "to": 1760129100000, "minutes": 98 } ],
  "launcher": {
    "focus": { "periods": 3, "byKind": { "manual": 2, "scheduled": 1, "rest": 1 }, "minutes": 155, "blockedOpens": 6, "heldNotifications": 14 },
    "pauses": { "shown": 9, "byOutcome": { "opened": 5, "backedOut": 2, "alternative": 1, "rest": 1 }, "byIntent": { "reply": 2, "specific": 1, "habit": 1 } },
    "recent": [ { "date": "2026-10-04", "focusPeriods": 2, "focusMinutes": 75 } ]
  }
}
```

- `state`: `open` (today), `arriving` (a past day not yet closed: some device has not caught up), `closed`.
- `headline.elapsedOnly`: true for today. Only awake hours that already passed count.
- `headline.incomplete` and `silentSensors` follow the health-gap rule in `events.md`. Each silent sensor is a display string, `"<sensor> (<device name>)"`.
- `hours[].segments` lists the top 20 segments of the hour by minutes, so the page can open a trace.
- `hours` has one entry per local hour; a day with a daylight-saving change has 23 or 25 entries.
- `totals.screenMinutes` is the union across devices. In `byCategory` and `hours`, overlapping time is attributed to the phone.
- Built-in categories: `Work`, `Personal computer`, `Uncategorized` (an app or site without a mapping) and `Unattributed` (phone screen on and unlocked with no attributable app).
- `computerLabels` lists the stretches marked by hand that touch the day (see Computer labels). `minutes` is the computer time each one took on this day.
- `launcher` counts the phone's `android.focus` and `android.pause` events (`events.md`) at read time. They are never screen time. Each event belongs to the local day of its `at`, so a focus period that runs past midnight counts, minutes included, on the day it started. Suspect events and exclusions are left out.
  - `focus.periods` counts the focus rounds (`manual` and `scheduled`), and `minutes` is their sum of `ended_at − at`, as the phone's home screen counts them. Rests appear only in `byKind`. `blockedOpens` and `heldNotifications` are sums over every period, rests included.
  - `pauses.shown` is every pause screen and `byOutcome` splits it. `byIntent` counts the openings that gave a reason, so it can add up to less than `byOutcome.opened`.
  - `recent` has the seven days ending on `date`, oldest first, with each day's `focus.periods` and `focus.minutes`.

## Trends

`GET /api/trends?period=week&anchor=2026-10-10` (`period`: `day`, `week`, `month`; weeks start on Monday)

```json
{
  "period": "week",
  "current": { "from": "2026-10-05", "to": "2026-10-10", "screenMinutes": 1900, "awayMinutes": 2900, "byCategory": [ ] },
  "previous": { "from": "2026-09-28", "to": "2026-10-03", "screenMinutes": 2100, "awayMinutes": 2700, "byCategory": [ ] },
  "series": [ { "date": "2026-10-05", "screenMinutes": 380, "awayMinutes": 580, "complete": true } ]
}
```

- A period in progress is compared with the same elapsed span of the previous period.
- Days that are not closed, partial (before a sensor's coverage start) or incomplete are left out of both sides and flagged in `series`.
- `previous` is `null` when no full previous span of closed days exists.

## Categories and subjects

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/categories` | | `[ { "id", "name", "color", "builtin" } ]` |
| POST | `/api/categories` | `{ "name", "color" }` | 201 category |
| PUT | `/api/categories/{id}` | `{ "name", "color" }` | category |
| DELETE | `/api/categories/{id}` | | 204, or 409 `category_in_use` |
| GET | `/api/subjects?q=&unmapped=&limit=` | | `[ { "key", "kind", "label", "categoryId", "minutes30d" } ]`, sorted by `minutes30d` descending |
| PUT | `/api/subjects/{key}/category` | `{ "categoryId": "…" \| null }` | subject |

Subject keys: `app:<package>`, `app:<package>#shorts`, `site:youtube`, `site:linkedin`. Desktop activity is not a subject. It is `Work` or `Personal computer` by the work window, unless it falls in a stretch marked by hand. Reclassifying a subject applies to all past days.

## Computer labels

The desktop agent reports when the computer was in use and never what was on screen. A label marks a stretch of local time with a category, and the computer time inside it takes that category in place of `Work` or `Personal computer`. Site time (YouTube, LinkedIn) and phone time keep their own categories. Labels are applied when reading, so marking a past stretch changes that day.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/computer-labels` | `{ "date": "2026-10-12", "start": "20:00", "end": "21:45", "categoryId": "…" }` | 201 `{ "id", "categoryId", "from", "to" }` |
| DELETE | `/api/computer-labels/{id}` | | 204 |

- `start` and `end` are local times on `date` in the user timeline. An `end` before the `start` belongs to the next day.
- Labels never overlap. One that would is refused with 409 `label_overlap`; stretches that only touch are fine.
- `Uncategorized` and `Unattributed` are refused with 400.
- A category a label still uses cannot be deleted: 409 `category_in_use`.

## Devices

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/devices` | | `[ { "id", "name", "kind", "installations": [ { "id", "fingerprint", "createdAt", "lastSeenAt", "revokedAt", "cloneSuspected" } ] } ]` |
| POST | `/api/devices` | `{ "name", "kind", "confirmSecondPhone": false }` | `{ "device", "code", "expiresAt" }`, or 409 `second_phone` when another active Android device exists and the flag is false |
| POST | `/api/devices/{id}/pairing-code` | | `{ "code", "expiresAt" }` (re-pair a reinstalled collector) |
| DELETE | `/api/installations/{id}` | | 204 (revoke; past events stay) |

The pairing code is shown once. `GET /api/pairing-codes/state` returns `{ "lockedOut": bool, "failures": n }` so the page can show the ten-failure lockout.

## Settings

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/settings` | | see below |
| PUT | `/api/settings/work-window` | `{ "days": [1,2,3,4,5], "start": "09:00", "end": "18:00" }` | settings |
| PUT | `/api/settings/awake-window` | `{ "start": "07:00", "end": "23:00" }` | settings |
| POST | `/api/non-workdays` | `{ "date": "2026-12-25" }` | settings |
| DELETE | `/api/non-workdays/{date}` | | settings |

```json
{
  "workWindow": { "days": [1,2,3,4,5], "start": "09:00", "end": "18:00", "validFrom": "2026-10-10" },
  "awakeWindow": { "start": "07:00", "end": "23:00", "validFrom": "2026-10-10" },
  "history": [ { "kind": "work", "value": { "days": [1,2,3,4,5], "start": "09:00", "end": "18:00" }, "validFrom": "2026-10-10" } ],
  "nonWorkdays": [ "2026-12-25" ]
}
```

- Days use ISO numbering: 1 = Monday.
- A window edit creates a version valid from the current local date. A second edit on the same day replaces that day's version. Past days keep their own version.
- An awake window may cross midnight (`"start": "08:00", "end": "01:00"`); the tail belongs to the day it started.

## Backstage

`GET /api/backstage`

```json
{
  "devices": [
    {
      "id": "…", "name": "Phone", "kind": "android",
      "sensors": [ { "name": "usage", "state": "active", "lastContact": 1760090000000, "detail": null } ],
      "eventsPerDay": [ { "date": "2026-10-10", "count": 5210 } ],
      "lagMs": { "p50": 420000, "p95": 1800000 },
      "duplicates": 12, "rejected": 0, "droppedFields": 0, "intervalConflicts": 0,
      "invariantViolations": 0, "clockSkewMs": 1200,
      "gaps": [ { "sensor": "usage", "from": 1760000000000, "to": 1760003600000, "expected": false } ],
      "shorts": { "unconfirmedSinceVersion": null, "unconfirmedSince": null, "lastConfirmedVersion": "19.40.33" }
    }
  ],
  "database": { "sizeBytes": 123456789, "budgetBytes": 10737418240 },
  "derivation": { "version": 1, "rebuilding": false, "progress": 1.0 },
  "blindSpots": [ "Firefox", "YouTube in the phone browser", "Computer leisure outside the browser" ]
}
```

Sensor states: `active`, `expected_stop`, `late`, `stopped` (permission or service off), `silent` (no health record beyond the gap rule).

`GET /api/backstage/trace?segmentId=…` returns the raw events behind one segment and the rules that produced it:

```json
{
  "segment": { "id": "…", "deviceId": "…", "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "categoryId": "…", "start": 1760089001000, "end": 1760089301000, "derivationVersion": 1 },
  "events": [ { "id": "…", "type": "android.usage", "at": 1760089001000, "kind": "ACTIVITY_RESUMED", "package": "com.whatsapp" } ],
  "rules": [ "Clipped to screen on and unlocked", "Merged intervals 2 s apart" ]
}
```

## Validation

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/validation?from=&to=` | | `[ { "date", "closed", "measuredMinutes", "rawMinutes", "settingsMinutes", "diffPct", "flagged" } ]` |
| PUT | `/api/validation/{date}` | `{ "minutes": 245 }` | row, or 409 `day_not_closed` |

`diffPct` is the signed percentage `(measured − settings) / settings × 100`, or null without a Settings value. `flagged` is true when `|diffPct| > 10`. The flag is recomputed on every read. Only closed days accept input, and a day closes for validation when the phone has caught up.

## Corrections

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/corrections` | | `{ "redactions": [ { "id", "deviceId", "type", "from", "to", "field", "createdAt" } ], "exclusions": [ { "id", "deviceId", "type", "from", "to", "note", "createdAt" } ] }` (`field` null means the events were deleted) |
| POST | `/api/corrections/redactions` | `{ "deviceId", "type", "from", "to", "field" }` (`field` null deletes the events) | audit row |
| POST | `/api/corrections/exclusions` | `{ "deviceId", "type", "from", "to", "note" }` | exclusion |
| DELETE | `/api/corrections/exclusions/{id}` | | 204 |

Redaction is destructive and audited without content. Exclusion is reversible and applies at derivation.
