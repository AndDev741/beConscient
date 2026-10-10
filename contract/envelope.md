# Envelope

Every request from a collector to `/ingest/*` is an HPKE envelope. The transport (a TLS-terminating proxy such as a Cloudflare Tunnel) sees only sizes, timing and IP addresses. Responses are encrypted with a key exported from the same HPKE context, so a forged or replayed response cannot pass.

Contract version: **1**.

## Cipher suite

HPKE (RFC 9180), base mode:

| Part | Choice | IANA id |
|---|---|---|
| KEM | DHKEM(X25519, HKDF-SHA256) | `0x0020` |
| KDF | HKDF-SHA256 | `0x0001` |
| AEAD | ChaCha20-Poly1305 | `0x0003` |

The server holds one or more X25519 private keys, each identified by a key id (`kid`). Collectors embed the public key at build time. Each server profile has its own list of accepted kids. Production never accepts the test kid, and a release build of a collector fails when the embedded kid is the test kid.

## Purposes

The HPKE `info` parameter binds an envelope to one purpose:

| Purpose | `info` (UTF-8) | Endpoint |
|---|---|---|
| Pair an installation | `beconscient/v1/pair` | `POST /ingest/v1/pair` |
| Upload a batch | `beconscient/v1/batch` | `POST /ingest/v1/batches` |

The AEAD `aad` is the UTF-8 `kid`. An envelope sealed for one purpose never opens at the other endpoint.

## Request body

`Content-Type: application/json`, at most **1 MiB** (enforced by the proxy and the server before decryption).

```json
{ "v": 1, "kid": "prod-2026-10", "enc": "<base64url>", "ct": "<base64url>" }
```

- `enc`: the HPKE encapsulated key (32 bytes).
- `ct`: the single-shot HPKE ciphertext of the plaintext below (sequence number 0 of the context).
- base64url is unpadded (RFC 4648 §5).

## Response body

All `200` responses from `/ingest/*` are encrypted:

```json
{ "v": 1, "nonce": "<base64url, 12 bytes>", "ct": "<base64url>" }
```

- Key: `Export("beconscient/v1/response", 32)` from the request's HPKE context. The collector computes the same value from its sender context.
- AEAD: ChaCha20-Poly1305 with the random `nonce` and `aad` = `"beconscient/v1/response"`.
- Errors are plain JSON with no detail beyond a code: `{"error":"unauthorized"}` (401), `{"error":"bad_envelope"}` (400), `{"error":"too_large"}` (413), `{"error":"sequence_conflict"}` (409), `{"error":"rate_limited"}` (429), `{"error":"unavailable"}` (503).

## Pairing

The page creates a device and a one-time pairing code: 8 Crockford base32 characters (40 bits), shown as `XXXX-XXXX`, valid for 15 minutes, single use, stored only as a SHA-256 hash.

Pairing plaintext:

```json
{
  "code": "7KQ2-M9XD",
  "installation_id": "0192f3a0-1c2b-7cde-8f00-112233445566",
  "secret": "<base64url, 32 random bytes>",
  "kind": "android",
  "name": "Pixel 7",
  "client_version": "0.1.0"
}
```

- `kind`: `android` or `desktop`.
- The collector generates `installation_id` (UUIDv7) and `secret` itself. The secret never leaves the device again except inside batch envelopes.
- The server stores `SHA-256(secret)`, never the secret.

Pairing response plaintext:

```json
{ "device_id": "0192...", "device_name": "Phone", "fingerprint": "Q4XN-8T2C" }
```

The **fingerprint** is the first 8 Crockford base32 characters of `SHA-256("beconscient-fp" || installation_id || SHA-256(secret))`, where `installation_id` is its canonical lowercase string. Both the page and the collector display it so the user can compare them.

Rules:

- Without an open code the request fails fast with 401.
- Ten consecutive failed pairing attempts invalidate every open code and are shown on the page.
- A pairing that links a new installation to an existing device revokes the older installations of that device. Their past events stay.

## Batch

Batch plaintext:

```json
{
  "header": {
    "installation_id": "0192f3a0-1c2b-7cde-8f00-112233445566",
    "credential": "<base64url secret>",
    "seq": 42,
    "sent_at": 1760090000123,
    "tz": "Europe/Lisbon",
    "tz_offset_min": 60,
    "contract_version": 1,
    "client_version": "0.1.0"
  },
  "events": [ { "...": "see events.md" } ]
}
```

- The server checks the credential in constant time before it reads any event. A failed check writes nothing, marks nothing dirty and does not update the last contact time.
- At most **2000 events** per batch. Collectors split larger queues.
- `sent_at` is the device wall clock at send time. The server records `received_at − sent_at` as the clock skew of the batch and never corrects stored data with it.

### Sequence numbers and resends

Each installation numbers its batches from 1. A collector **freezes** a batch before the first send: it stores the sequence number together with the exact list of event ids and revisions. It resends that same frozen batch until it receives a valid receipt, then moves on to the next number.

On the server:

- Same `seq` and the same event-id-and-revision list as a logged batch: the server returns the stored receipt again (newly encrypted).
- `seq` lower than or equal to the last accepted one with a different list: `409 sequence_conflict`, and the installation is flagged as a possible clone.
- `seq` higher than the last accepted one: processed normally. A gap is recorded but not refused.

### Receipt

Receipt plaintext:

```json
{
  "installation_id": "0192f3a0-...",
  "seq": 42,
  "results": [
    { "id": "0192f3a1-...", "status": "accepted" },
    { "id": "0192f3a1-...", "status": "duplicate" },
    { "id": "0192f3a1-...", "status": "rejected", "reason": "end_before_start" }
  ]
}
```

- A `200` means every `accepted` and `duplicate` result is durably stored.
- `duplicate` is returned both for a repeated event id and for a repeated natural key under a new id.
- `rejected` events go to the server quarantine with disallowed fields stripped. The collector moves them to its local rejected table and never resends them.
- The collector only acts on ids it sent in that batch.

### Collector queue lifecycle

1. An event is written to the local queue together with any read watermark, in one transaction.
2. Batches are frozen, sent and resent as above.
3. `accepted` and `duplicate` events are marked acknowledged and kept for **14 days**, then deleted.
4. "Resend since T" re-queues acknowledged events from T onward in new batches. The server answers `duplicate` for those it already has. This is the recovery path after a server restore.
