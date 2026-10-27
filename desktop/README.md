# beConscient desktop agent

`beconscient-agent` measures active screen time on a GNOME 46 Wayland desktop (Ubuntu 24.04) and sends it to your beConscient server inside HPKE envelopes. The same binary is the native messaging host for the browser extension.

It reports time intervals and nothing about what was on screen. Window titles, application names, inhibitor ids and reasons, and URLs never enter an event. From the browser it forwards YouTube and LinkedIn intervals as described in [`contract/events.md`](../contract/events.md): a video title for YouTube, a coarse page kind for LinkedIn.

The contract in [`../contract`](../contract) is authoritative. The rules for turning activity into screen time live in [`../docs/design.md`](../docs/design.md).

## Build and test

Go 1.26 or newer (the HPKE suite comes from the standard library's `crypto/hpke`). SQLite is `modernc.org/sqlite`, pure Go, so no cgo is needed.

```sh
go vet ./...
go test ./...
go build -trimpath -o beconscient-agent ./cmd/beconscient-agent
```

The envelope vectors in `../contract/vectors` are checked by `go test ./internal/envelope` and by `go run ./cmd/vectorcheck` from `../tools`. To regenerate this agent's own vector file, `contract/vectors/go-desktop.json`:

```sh
BECONSCIENT_WRITE_VECTORS=1 go test -run TestWriteDesktopVectors ./internal/upload
```

It freezes a real batch (a `desktop.activity` interval, a `browser.site` interval, a health record) with the agent's own code and seals it with the test key.

## Install

`packaging/install.sh` installs for the current user:

| What | Where |
|---|---|
| Binary | `~/.local/bin/beconscient-agent` (or `$BINDIR`) |
| systemd user unit | `~/.config/systemd/user/beconscient-agent.service` (`After=` and `WantedBy=graphical-session.target`, `Restart=on-failure`) |
| Native messaging manifest `org.beconscient.agent.json` | `~/.config/google-chrome/NativeMessagingHosts/`, `~/.config/BraveSoftware/Brave-Browser-Nightly/NativeMessagingHosts/`, `~/.config/BraveSoftware/Brave-Browser/NativeMessagingHosts/` |
| State and config directories | `$XDG_STATE_HOME/beconscient`, `$XDG_CONFIG_HOME/beconscient`, mode 0700 |

```sh
packaging/install.sh            # builds with go, writes everything, runs daemon-reload
packaging/install.sh --enable   # also enables and starts the unit
packaging/install.sh --binary ./beconscient-agent   # install a prebuilt binary
packaging/install.sh --uninstall                    # keeps the queue and config
```

The manifests allow only `chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok/`, and their `path` is the installed binary. `BECONSCIENT_NO_SYSTEMCTL=1` skips every `systemctl` call, which helps when trying the script in a scratch `HOME`.

## Commands

```
beconscient-agent [run] [--dev]
beconscient-agent pair --server https://<server> --key <public key json> [--name <name>] [--dev]
beconscient-agent status
beconscient-agent replay --since <YYYY-MM-DD | RFC 3339>
beconscient-agent probe [--seconds 30]
beconscient-agent version
```

- `run` is the default and what systemd starts. It measures and queues even before pairing; uploads start once `agent.json` exists. It refuses to start when another agent holds the socket.
- `pair` reads the one-time code (`XXXX-XXXX`) from standard input and refuses one passed as an argument. It generates the installation id (UUIDv7) and a 32-byte secret, pairs inside an HPKE envelope, checks the server's fingerprint against its own and saves `$XDG_CONFIG_HOME/beconscient/agent.json` with mode 0600. Compare the printed fingerprint with the one the page shows. `--key` takes the public key file written by `tools/cmd/keygen` (or the JSON itself).
- `status` works without the server and without a running agent: whether the agent runs, its last state, pairing, queue depth and age, the frozen batch, the last upload and its error, and the sensors.
- `replay --since` re-queues acknowledged events (kept 14 days) whose end is at or after the date. They go out in new batches and the server answers `duplicate` for those it already has. Use it after a server restore.
- `probe` is a read-only D-Bus check. It prints the idle time, the screensaver and `LockedHint` state and the idle-inhibitor flag every 5 seconds, plus any signals, then exits. It takes no inhibitor.

### The test key

`contract/keys` holds a public, test-only key pair. `pair`, `run` and the uploader refuse it unless `--dev` is given. The check looks at the `test_only` flag, the kid and the key bytes, so renaming the kid does not get around it. `--dev` also lets `pair` use an `http://` server URL. The systemd unit runs without `--dev`.

## How time is measured

The state machine follows `docs/design.md`: Active, Passive, Idle, Locked, Suspended.

| Input | Source |
|---|---|
| Idle time, every 5 s | `org.gnome.Mutter.IdleMonitor.GetIdletime` (session bus) |
| Lock | `org.gnome.ScreenSaver` `GetActive` at start and `ActiveChanged`. If it is unavailable, logind `LockedHint` (polled and via `PropertiesChanged`) |
| Idle inhibitor | `org.gnome.SessionManager.IsInhibited(8)`, re-read on `InhibitorAdded` and `InhibitorRemoved` and on every poll. Only the flag is read |
| Media playing | every `org.mpris.MediaPlayer2.*` name on the session bus, `PlaybackStatus` read on every poll. Only whether one says `Playing` is kept |
| Playing video | the extension's `playing` message, tracked per browser connection |
| Suspend and shutdown | logind `PrepareForSleep` and `PrepareForShutdown` (system bus), with a `delay` inhibitor for `sleep:shutdown` |

Rules as implemented:

- Active counts while the last input is less than 5 minutes old. Interval reason `input`.
- After 5 minutes without input, the input interval ends at the last input. With an idle inhibitor held while a media player plays, or a playing video from the extension, the agent goes Passive and a new interval starts at that last input, reason `playing` when the extension reports a video and `inhibitor` otherwise. Otherwise it goes Idle, and the open interval is trimmed back to the last input as a new revision. An idle inhibitor with nothing playing (a keep-awake tool, an agent left working overnight) goes Idle.
- A Passive stretch ends when the video ends, the media player stops or the inhibitor goes away, or 2 hours after the last input (AE9: a 3-hour video counts 2 hours).
- A change of reason closes the interval and starts a new one.
- Idle has no way into Passive: a video that starts while idle counts only after input.
- Locking closes the open interval at the lock time. Unlocking counts as input and starts a new interval.
- Suspend and shutdown close the open interval. After a resume the state is Locked, and nothing counts until an unlock or, without a lock screen, new input.
- A wall-clock jump of more than 15 s between two polls (compared with the monotonic clock) closes the open interval at its last known end and restarts measuring at the new time. A suspend is excluded from this check.
- Every revision goes to the queue on each state change and every 5 minutes, so a crash loses at most 5 minutes. Each interval has a stable UUIDv7 id, a growing `rev`, `tz` and `tz_offset_min`. The zone is re-read from `/etc/localtime` at most once a minute.
- D-Bus failures are logged at error level once per outage. A failing idle monitor is treated as "no input", so nothing is counted that cannot be seen, and the `activity` sensor reports `stopped`.

## Browser intervals

The extension's messages follow [`contract/native-messaging.md`](../contract/native-messaging.md).

- A focused `site` interval is uploaded as `browser.site` with its own id and revision, without `focused`.
- An unfocused interval counts only while a video plays, and only for the parts where the agent was Passive. Each such part becomes its own `browser.site` interval. Its id is derived from the site interval id and the Passive interval id, so it stays stable across revisions. When a Passive interval appears or grows after a site revision was processed (a Passive interval starts at the last input, which may be in the past), the stored unfocused intervals that overlap it are recomputed.
- An unfocused interval without a playing video never counts.
- Only whitelisted fields are copied. Sites other than YouTube and LinkedIn are dropped, LinkedIn never carries a title, `page_kind` outside the closed list becomes `other`, titles are cut to 300 characters and an unknown browser becomes `other`.
- Dropped messages are still acknowledged, so the extension stops resending them. Valid ones are acknowledged only after the write is committed (`synchronous=FULL`).
- `heartbeat` messages become the `extension_chrome` and `extension_brave` sensors: `ok` within 15 minutes of the last heartbeat, then `stopped`, and `unknown` before the first one.

## Native messaging host

When the first argument starts with `chrome-extension://`, the binary runs as the host. It refuses any origin other than the pinned one, reads length-prefixed (little-endian) JSON from stdin and refuses messages over 64 KiB. A refused message up to 1 MiB is skipped; a larger length stops the host.

The host forwards each message to the agent over `$XDG_RUNTIME_DIR/beconscient/agent.sock` and relays the agent's ack. The socket directory is 0700 and the socket 0600. The agent checks every peer's uid with `SO_PEERCRED`, the host checks the agent's, and both cap messages at 64 KiB. When the agent is down, the host writes site intervals and heartbeats straight into the queue and acks them itself; `playing` messages are dropped then. It retries the agent every 30 seconds and, on reconnecting, replays the last `playing` state.

## Queue and upload

The queue is `$XDG_STATE_HOME/beconscient/queue.db` (SQLite in WAL mode; the database and its `-wal` and `-shm` files are 0600, the directory 0700). The agent and host processes can use it at the same time.

- `events`: every event with its latest revision, the highest acknowledged revision and when it became pending. A lower or equal revision never overwrites a stored one.
- `batches` and `batch_items`: the frozen batch. A batch takes the next sequence number and the exact event JSON, ids and revisions before its first send, and is resent byte for byte until a valid receipt arrives, even if newer revisions were written meanwhile. Sequence numbers are per installation, start at 1 and are never reused.
- `rejected`: events the server rejected. They are never resent.
- `heartbeats`, `site_raw`, `site_pieces`: extension heartbeats and the unfocused site intervals used to compute their Passive parts.
- Acknowledged events are kept 14 days, raw site intervals 2 days, rejected rows 30 days.

Uploads run every minute while something is pending: at most 2000 events and about 700 KiB of events per batch, sealed for `beconscient/v1/batch` with aad = kid. A receipt counts only if it opens with `Export("beconscient/v1/response", 32)` from this request's HPKE context and names this installation and sequence number. Then only ids sent in that batch are touched: `accepted` and `duplicate` mark the sent revision acknowledged, `rejected` moves it to the rejected table. Failures back off exponentially from 30 seconds to 30 minutes with jitter. A `413` drops the frozen batch (the server never logged it) and halves the limits. A `409 sequence_conflict` or `401` keeps the batch frozen and is logged as an error, because it means the installation was revoked or cloned and needs a new pairing.

## Health records

A `health` event every 5 minutes, at start, after a resume, and one with `expected_stop: true` before a suspend, before a shutdown and when the agent stops. Before a suspend or shutdown the agent closes the open interval and writes that record before it releases the delay inhibitor. Sensors: `activity`, `extension_chrome`, `extension_brave`. The record also carries `queue_depth`, `oldest_unsent_age_ms` and `client_version`.

## Files

| File | Mode |
|---|---|
| `$XDG_CONFIG_HOME/beconscient/agent.json` (server, key, installation id, secret) | 0600 in 0700 |
| `$XDG_STATE_HOME/beconscient/queue.db*` | 0600 in 0700 |
| `$XDG_RUNTIME_DIR/beconscient/agent.sock` | 0600 in 0700 |

The process umask is 077 in every mode.

## Package layout

| Package | What |
|---|---|
| `cmd/beconscient-agent` | CLI and host-mode dispatch |
| `internal/activity` | State machine (`state.go`), monitor with jump detection (`monitor.go`), D-Bus source (`dbus.go`), fakes in `activitytest` |
| `internal/agent` | The event loop that ties everything together, and `status` |
| `internal/queue` | SQLite queue |
| `internal/upload` | Batches, receipts, backoff |
| `internal/envelope` | HPKE seal and response open |
| `internal/pairing` | Pairing, code handling, fingerprint |
| `internal/nativehost` | Framing, host mode, agent socket |
| `internal/sites` | Site message validation and the Passive filter |
| `internal/health`, `internal/events` | Health records and the allowed event fields |
| `internal/testserver` | Fake ingest server for tests, using the contract's test key |

## Known gaps

- The plan's 10-minute experiment (YouTube and a Meet call in Chrome and Brave while watching `gdbus monitor`) has not been run. The inhibitor rule assumes the browsers take a GNOME idle inhibitor during playback and calls, which the 2-hour cap bounds either way.
- Firefox and Chromium get no manifest.
- While the agent is down, `playing` messages are lost. Unfocused videos then count only through stored Passive intervals, and the GNOME inhibitor still keeps the agent Passive after it restarts.
- A `409 sequence_conflict` is not resolved automatically. Pair again.
