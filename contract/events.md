# Events

Contract version: **1**. This file owns the allowed fields of every event type. The server drops any field not listed here before storing an event and counts the drops per device. String fields are truncated to their listed maximum length. Unknown event types are rejected with reason `unknown_type`.

## Common fields

| Field | Type | Notes |
|---|---|---|
| `id` | string | UUIDv7 generated on the device when the fact was observed. |
| `type` | string | One of the types below. |
| `at` | integer | Epoch milliseconds (UTC). Point events only. |
| `start`, `end` | integer | Epoch milliseconds (UTC). Interval events only. `end >= start`. |
| `rev` | integer | Interval events only. Starts at 1 and grows with each rewrite. |
| `tz` | string | Optional IANA zone of the device when the fact was observed (max 64). |
| `tz_offset_min` | integer | Optional UTC offset in minutes at that instant. |

Validation that rejects an event: missing `id`, `type` or time fields; `end < start`; a string enum outside its list. An event more than 1 day in the future of the server clock is not rejected: it is stored, marked suspect and excluded from derivation.

## Interval revisions

Intervals that grow while they are open (`desktop.activity`, `browser.site`) keep a stable `id`. The device rewrites them with a higher `rev`:

- The `start` never changes. A rewrite with a different `start` is recorded as a conflict and ignored.
- A higher `rev` replaces `end` and the other fields, in either direction (the end may move back, for example when the agent trims idle time).
- A lower or equal `rev` is acknowledged as `duplicate` and ignored.

## Natural keys

Besides the `id`, some types carry a natural key so that re-reading the same fact with a new id is detected as a duplicate:

| Type | Natural key |
|---|---|
| `android.usage` | device, `kind`, `at`, `package`, `class` |
| `android.notification_posted` | device, `ref`, `at` (an update reuses the `ref` with a later `at`) |
| `android.notification_removed` | device, `ref` |

## Types

### `android.usage` (point)

A raw record from `UsageStatsManager.queryEvents`.

| Field | Type | Notes |
|---|---|---|
| `kind` | enum | `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`, `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN`, `DEVICE_SHUTDOWN`, `DEVICE_STARTUP` |
| `package` | string | Max 255. Required for activity kinds. |
| `class` | string | Max 255. Optional. Activity class name. |
| `instance` | integer | Optional. Activity instance id (API 31+). |

### `android.tz_change` (point)

| Field | Type | Notes |
|---|---|---|
| `zone` | string | IANA zone, max 64. |
| `offset_min` | integer | UTC offset at `at`. |
| `reason` | enum | `INITIAL`, `TIMEZONE_CHANGED`, `TIME_SET`, `BOOT` |

### `android.notification_posted` (point)

| Field | Type | Notes |
|---|---|---|
| `package` | string | Max 255. Source app. |
| `ref` | string | Random UUID made on the device for this notification. The notification key and tag never leave the device. |
| `is_update` | boolean | The same notification was posted again. |
| `is_group_summary` | boolean | |
| `is_ongoing` | boolean | |
| `importance` | integer | -1 to 5, or absent. |
| `category` | string | Optional `Notification.category`, max 32. |

Never sent: title, text, sender, conversation, key, tag, channel name.

### `android.notification_removed` (point)

| Field | Type | Notes |
|---|---|---|
| `package` | string | Max 255. |
| `ref` | string | The `ref` of the posted event. |
| `reason` | enum | `click`, `cancel`, `cancel_all`, `app_cancel`, `other` |
| `posted_at` | integer | Epoch ms of the matching post. |

### `android.shorts` (point)

Markers from the accessibility service that watches only the YouTube app.

| Field | Type | Notes |
|---|---|---|
| `state` | enum | `enter`, `exit`, `unclassified` |
| `youtube_version` | string | versionName, max 64. |
| `youtube_version_code` | integer | |
| `rules_version` | integer | Version of the detector rules in the app. |

`unclassified` is emitted when YouTube is in the foreground and the detector cannot classify the screen, including when only part of the required signals is recognised beyond the stability window. The server treats a YouTube version with no `enter` marker yet as "unconfirmed" and shows it in the backstage view.

### `android.pause` (point)

One pause screen of the launcher, shown when the owner opens an app they chose to control. `at` is when the pause screen appeared.

| Field | Type | Notes |
|---|---|---|
| `package` | string | Max 255. The app being opened. |
| `outcome` | enum | `opened` (opened the app after the wait), `backed_out` (left without opening anything), `alternative` (opened the block's alternative app instead) |
| `block` | string | Optional. Name of the block in force, as the owner typed it on the phone, max 60. Absent outside any block. |
| `alternative` | string | Optional. Package of the alternative app the pause screen offered, max 255. |
| `wait_ms` | integer | Time from the pause screen appearing to the owner's choice. |

Never sent: the block's message, apps the owner did not choose to control.

### `desktop.activity` (interval)

Active screen time on the computer, as decided by the agent's state machine.

| Field | Type | Notes |
|---|---|---|
| `reason` | enum | `input`, `inhibitor`, `playing`. `inhibitor` is time without input while a GNOME idle inhibitor is held and a media player is playing. A new interval starts when the reason changes. |

Never sent: window titles, application names, inhibitor app ids or reasons, media player names, URLs.

### `browser.site` (interval)

Time with YouTube or LinkedIn in the browser, reported by the extension through the agent.

| Field | Type | Notes |
|---|---|---|
| `site` | enum | `youtube`, `linkedin` |
| `browser` | enum | `chrome`, `brave`, `other` |
| `playing` | boolean | A video was playing during the interval. |
| `title` | string | YouTube only, max 300. The server drops it for LinkedIn. |
| `page_kind` | enum | LinkedIn only: `feed`, `messaging`, `profile`, `jobs`, `other`. |

Never sent: URLs, LinkedIn titles, any other site.

### `health` (point)

A health record. The phone sends one every 15 minutes, the agent every 5 minutes, and the agent also sends one right before suspend or shutdown with `expected_stop` set.

| Field | Type | Notes |
|---|---|---|
| `client_version` | string | Max 32. |
| `sensors` | object | Map from sensor name to `{ "state": "ok" \| "stopped" \| "disabled" \| "unknown", "detail": string (max 120, optional), "last_event_at": integer (optional) }`. |
| `watermark` | integer | Android: last usage event time read. |
| `queue_depth` | integer | Unsent events. |
| `oldest_unsent_age_ms` | integer | |
| `home_package` | string | Android: current default launcher package, max 255. |
| `expected_stop` | boolean | Desktop: the machine is about to suspend or shut down. |

Sensor names:

| Collector | Sensors |
|---|---|
| Android | `usage`, `notifications`, `shorts`, `battery_exemption` |
| Desktop | `activity`, `extension_chrome`, `extension_brave` |

A gap is a stretch longer than three expected periods without a health record while the device is on: 45 minutes for the phone, 15 minutes for the agent. Shutdown, startup, suspend and `expected_stop` mark expected gaps.
