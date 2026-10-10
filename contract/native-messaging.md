# Native messaging

The browser extension talks to the desktop agent through Chrome native messaging. The browser starts the agent binary as a host process for each connection; that process forwards messages to the running agent over its Unix socket, or writes them straight into the agent's queue when the agent is down.

## Identity

- Host name: `org.beconscient.agent`
- Extension id: `hhddmdmnpejagnnmenjdgdifpimneeok`, fixed by the public key in the extension manifest:

```
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyB+nV8XuzG1fYo/udwaaCAcZ0iZH3qfRRJL5Jr/EpbIn0+1iQQZ3+JqovVmVti6K0mpOwzuVuwoBzlWxuzefstKNm7QAsEeLZWDRrOTJ6x0KcZY7mU5peAjfk569g1ZKtErDhtk5ZpRqu8FblHYMfEfKmCeEtjwdiZMLZH2Y81UWORaXdf5OAvDTZXEqSbNNoFjWFB5fpZS2syBmiRZUblzGBbKj523qkEY+MOut19ja3PWGeFPjRumYLbDHFUjVr6A79/GuhcwxkFPgEH3HgLnHsxobiRZJ0PpiiQh3cpTJk7HQCfgXCVDMD7MsOJu+Jf1dHZR29zSRt7GzD6eq8QIDAQAB
```

- Host manifests list only `chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok/` in `allowed_origins`. The host also checks that the origin the browser passes as its first argument matches it.
- The agent binary runs in host mode when its first argument starts with `chrome-extension://`. Any other start runs the agent.

## Framing

Standard native messaging framing: a 32-bit little-endian length followed by UTF-8 JSON. The host refuses messages larger than 64 KiB.

## Extension to host

```json
{ "t": "site", "id": "<uuidv7>", "rev": 3, "site": "youtube", "browser": "chrome",
  "start": 1760090000000, "end": 1760091200000, "playing": true, "focused": false,
  "title": "A video title", "page_kind": null, "tz": "Europe/Lisbon", "tz_offset_min": 60 }
```

- `focused`: the browser window had focus for the whole interval. A new interval (new id) starts when `focused` or `playing` changes.
- The agent keeps an unfocused, playing interval only for the parts where it was Passive (no input for 5 minutes), so music playing behind another window while you type stays as computer time. It strips `focused` before upload.

```json
{ "t": "playing", "playing": true, "at": 1760090000000 }
```

Sent when video playback starts or stops in the active tab. The agent uses it as an activity reason, so a playing video keeps it Passive and not Idle.

```json
{ "t": "heartbeat", "browser": "chrome", "version": "0.1.0", "at": 1760090000000 }
```

Sent every 5 minutes. The agent reports it as the `extension_chrome` or `extension_brave` sensor in its health record.

## Host to extension

```json
{ "t": "ack", "ids": [ "<uuidv7>", "..." ] }
```

Sent after the site intervals are durably written to the agent's queue. The extension then drops its buffered copies. Intervals that are never acknowledged are resent when the port reconnects.
