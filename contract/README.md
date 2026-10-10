# Contract

The single source of truth for everything that crosses a component boundary. Every component tests against the files here.

| File | Owns |
|---|---|
| [`envelope.md`](envelope.md) | HPKE envelope, pairing, batches, receipts, resend rules. |
| [`events.md`](events.md) | Event types and their allowed fields. |
| [`api.md`](api.md) | The page API under `/api/*`. |
| [`fixtures/`](fixtures) | Example events: `valid.json` must be accepted as is; `forbidden.json` lists events whose disallowed fields must be dropped or which must be rejected, with the expected outcome. |
| [`vectors/`](vectors) | Envelope test vectors, one file per producer. |
| [`keys/`](keys) | The **test-only** key pair. It is public on purpose. Production refuses its kid. |

## Test vectors

Each implementation that seals envelopes writes its own vector file, `vectors/<producer>.json`, in the format produced by `tools/cmd/vectorgen`, and every implementation's test suite verifies all vector files:

- open `request` with the test private key and check `plaintext`;
- export the response key and compare it with `response_key`;
- open `response` with that key and check `response_plaintext`.

`go run ./cmd/vectorcheck` (from `tools/`) checks every file in one go.

## Changing the contract

Bump `contract_version` in `envelope.md` and `events.md` for any incompatible change. The server must accept a new version before any collector sends it.
