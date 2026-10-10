# Page API

The web page talks to the backend under `/api/*`. Every endpoint except login, session and logout needs the session cookie. JSON uses camelCase. Dates are local dates `YYYY-MM-DD` in the user timeline zone (the phone's zone at that time, `Europe/Lisbon` as fallback). Durations are whole minutes unless the field name says otherwise. Times of day are `HH:MM`.

Errors: `{ "error": "<code>", "message": "<human text>" }` with a matching HTTP status.

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
    { "label": "09:00", "start": 1760083200000, "byCategory": [ { "categoryId": "…", "minutes": 45 } ] }
  ],
  "topSubjects": [ { "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "categoryId": "…", "minutes": 41 } ],
  "unlocks": { "count": 57, "firstApps": [ { "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "count": 19 } ] },
  "notifications": [ { "subjectKey": "app:com.whatsapp", "label": "com.whatsapp", "posted": 88, "tapped": 21, "dismissed": 30 } ]
}
```

- `state`: `open` (today), `arriving` (a past day not yet closed: some device has not caught up), `closed`.
- `headline.elapsedOnly`: true for today. Only awake hours that already passed count.
- `headline.incomplete` and `silentSensors` (sensor names with device names) follow the health-gap rule in `events.md`.
- `hours` has one entry per local hour; a day with a daylight-saving change has 23 or 25 entries.
- `totals.screenMinutes` is the union across devices. In `byCategory` and `hours`, overlapping time is attributed to the phone.
- Built-in categories: `Work`, `Personal computer`, `Uncategorized` (an app or site without a mapping) and `Unattributed` (phone screen on and unlocked with no attributable app).

## Trends

`GET /api/trends?period=week&anchor=2026-10-10` (`period`: `day`, `week`, `month`; weeks start on Monday)

```json
{
  "period": "week",
  "current": { "from": "2026-10-06", "to": "2026-10-10", "screenMinutes": 1900, "awayMinutes": 2900, "byCategory": [ ] },
  "previous": { "from": "2026-09-29", "to": "2026-10-03", "screenMinutes": 2100, "awayMinutes": 2700, "byCategory": [ ] },
  "series": [ { "date": "2026-10-06", "screenMinutes": 380, "awayMinutes": 580, "complete": true } ]
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

Subject keys: `app:<package>`, `app:<package>#shorts`, `site:youtube`, `site:linkedin`. Desktop activity is always `Work` or `Personal computer` and is not a subject. Reclassifying a subject applies to all past days.

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
  "history": [ { "kind": "work", "value": { }, "validFrom": "2026-10-10" } ],
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
      "shorts": { "unconfirmedSinceVersion": null, "lastConfirmedVersion": "19.40.33" }
    }
  ],
  "database": { "sizeBytes": 123456789, "budgetBytes": 10737418240 },
  "derivation": { "version": 1, "rebuilding": false, "progress": 1.0 },
  "blindSpots": [ "Firefox", "YouTube in the phone browser", "Computer leisure outside the browser" ]
}
```

Sensor states: `active`, `expected_stop`, `late`, `stopped` (permission or service off), `silent` (no health record beyond the gap rule).

`GET /api/backstage/trace?segmentId=…` returns the raw events behind one segment and the rules that produced it.

## Validation

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/validation?from=&to=` | | `[ { "date", "closed", "measuredMinutes", "rawMinutes", "settingsMinutes", "diffPct", "flagged" } ]` |
| PUT | `/api/validation/{date}` | `{ "minutes": 245 }` | row, or 409 `day_not_closed` |

`flagged` is true when `|measured − settings| / settings > 10%`. The flag is recomputed on every read. Only closed days accept input, and a day closes for validation when the phone has caught up.

## Corrections

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/corrections` | | `{ "redactions": [ ], "exclusions": [ ] }` |
| POST | `/api/corrections/redactions` | `{ "deviceId", "type", "from", "to", "field" }` (`field` null deletes the events) | audit row |
| POST | `/api/corrections/exclusions` | `{ "deviceId", "type", "from", "to", "note" }` | exclusion |
| DELETE | `/api/corrections/exclusions/{id}` | | 204 |

Redaction is destructive and audited without content. Exclusion is reversible and applies at derivation.
