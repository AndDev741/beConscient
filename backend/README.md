# beConscient backend

The Quarkus service behind beConscient. It receives encrypted batches from the collectors on `/ingest/*`, turns raw events into screen-time segments in the background, and serves the page API on `/api/*`.

The wire formats live in [`../contract`](../contract) and the rules in [`../docs/design.md`](../docs/design.md). This README covers running, configuring, testing and packaging the service.

## Requirements

- Java 25. Maven runs through the wrapper (`./mvnw`). If the default `java` on your PATH is another version, point `JAVA_HOME` at a JDK 25, for example `export JAVA_HOME=$HOME/.sdkman/candidates/java/25-amzn`.
- Docker, for the tests and dev mode. Quarkus Dev Services starts a throwaway Postgres 17 (`postgres:17-alpine`).

## Run it locally

```shell
./mvnw quarkus:dev
```

Dev mode starts Postgres through Dev Services, runs the Flyway migrations and creates the `owner` account with the password `beconscient-dev`. It accepts envelopes sealed to the public test key in `../contract/keys/test-public.json` (kid `test-0001`). The API listens on http://localhost:8080 and `vite dev` on localhost may call it with credentials (CORS is open to localhost in dev only).

Derivation runs every 15 seconds in dev and production. The tests switch the scheduler off and derive by hand.

## Configuration

Production reads everything from the environment. Nothing secret has a default outside the `dev` and `test` profiles.

| Variable | Required | What |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | yes | For example `jdbc:postgresql://postgres.database.svc.cluster.local:5432/beconscient` |
| `QUARKUS_DATASOURCE_USERNAME` | yes | The database owner role, `beconscient` |
| `QUARKUS_DATASOURCE_PASSWORD` | yes | |
| `BECONSCIENT_SESSION_KEY` | yes | Encryption key of the session cookie, at least 32 characters |
| `BECONSCIENT_OWNER_PASSWORD` | yes | Password of the `owner` account, used to create it on the first start |
| `BECONSCIENT_HPKE_KEYS` | yes | JSON array of `{"kid","private_key"}` objects with base64url X25519 private keys, the shape `tools/cmd/keygen` writes. A key may carry `"retire_at": "2026-12-01T00:00:00Z"`; envelopes for it are refused from that instant on |
| `BECONSCIENT_HPKE_KEY_FILES` | no | Comma-separated paths of key files, as an alternative to `BECONSCIENT_HPKE_KEYS` (for a mounted Secret) |
| `BECONSCIENT_HPKE_ACCEPTED_KIDS` | no | Comma-separated kids; when set, only these of the loaded keys are accepted |
| `BECONSCIENT_DB_BUDGET_BYTES` | no | Database size budget for the backstage view, default `10737418240` (10 GiB). The view raises an alert above 70% |
| `BECONSCIENT_FALLBACK_ZONE` | no | Zone of the local day before the phone has reported any, default `Europe/Lisbon` |
| `BECONSCIENT_DERIVATION_EVERY` | no | Derivation interval, default `15s` |
| `QUARKUS_HTTP_PORT` | no | Default `8080` |

The service refuses to start, in any profile other than `dev` and `test`, when the session key, the owner password or the HPKE keys are missing, or when one of them is a known development value. That includes the test key pair under any kid, any key marked `test_only`, and the kid `test-0001`. The log names the setting that failed, never its value.

Health checks are at `/q/health/ready` (includes the database) and `/q/health/live`.

### Database

The schema belongs to Flyway (`src/main/resources/db/migration`) and runs at start. It needs no superuser: no extensions, only tables, indexes and transaction-scoped advisory locks. In production the database `beconscient` lives in the shared Postgres 17 instance with its own owner role.

Derived segments can always be rebuilt from raw events. A backup that skips `segments`, `raw_intervals`, `unlocks` and `blocks` loses nothing: after a restore, set `derivation_state.version` to `0` and the service rebuilds every block in the background.

### Keys

Generate a production key pair with the tool in `../tools`:

```shell
cd ../tools
go run ./cmd/keygen -kid prod-2026-10 -public ../contract/keys/prod-public.json -private /secure/place/prod.private.json
```

The public file goes into the collector builds. Put the private one in the Secret as `BECONSCIENT_HPKE_KEYS='[<content of prod.private.json>]'` and keep a copy in the password manager. To rotate, load both keys, ship collectors with the new public key, then set `retire_at` on the old one (or drop it).

## Test

```shell
./mvnw -B test
```

The suite needs Docker. It covers:

- the envelope: every file in `../contract/vectors` opens with the test key, the exported response key matches and the response decrypts (`VectorsTest`), an envelope never opens for the other purpose or under another kid, and a production key ring refuses the test kid and retired keys;
- pairing, batches and resend rules against the real endpoints, through `FakeCollector`, which seals envelopes like a collector does (`PairingTest`, `IngestResourceTest`);
- the contract fixtures: `valid.json` is stored as is and `forbidden.json` behaves as listed;
- the phone and desktop rules on hand-made event sequences (`PhoneSegmenterTest`, `DesktopSegmenterTest`) and the block derivation with carried state, late events, the version rebuild and the version guard (`DerivationServiceTest`);
- the page API: day, trends, categories, settings, backstage, validation and corrections, including the acceptance examples AE1 to AE11 that concern the server;
- the production secrets guard (`ProdSecretsGuardTest`).

### Against the packaged application

`IngestIT` runs black-box against the packaged application in the production profile, with a freshly generated key: login, pairing, a batch, background derivation, every page endpoint, and the test key refused.

```shell
# the JVM package
./mvnw verify -DskipITs=false -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
# the native binary
./mvnw verify -Dnative -Dquarkus.native.container-build=true -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

### Envelope vectors

`../contract/vectors/java-backend.json` is this implementation's vector file. To write it again:

```shell
./mvnw test -Dtest=JavaVectorWriterTest -Dbeconscient.writeVectors=true
cd ../tools && go run ./cmd/vectorcheck
```

Its pairing response carries the real fingerprint of the sample installation (`AWJC-42F0`), so collectors can check their fingerprint code against it.

## Build images

Two Dockerfiles live in `src/main/docker`. Neither image contains a secret.

The native image is the default for production. It needs no local GraalVM:

```shell
./mvnw package -Dnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native-micro -t beconscient-backend .
```

The JVM image is the documented fallback. It behaves the same and needs more memory (about 384Mi instead of 160Mi):

```shell
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t beconscient-backend-jvm .
```

Run either with the production environment:

```shell
docker run -p 8080:8080 --env-file prod.env beconscient-backend
```

Deploy with the `Recreate` strategy so two pods never derive at the same time.

## How it is organised

| Package | What |
|---|---|
| `auth` | Owner account, form login, the global rate limiter for login and pairing, the production secrets guard |
| `crypto` | HPKE through BouncyCastle's lightweight API (no JCA provider), the key ring, Crockford base32 |
| `ingest` | `/ingest/v1/pair` and `/ingest/v1/batches`, the contract filter, batch storage and receipts |
| `device` | Devices, installations, pairing codes and fingerprints; `/api/devices` |
| `derive` | Dirty marks, blocks, the phone and desktop segmenters, the scheduler and the version rebuild |
| `view` | The user timeline (local day in the phone's zone), the day view and trends |
| `category`, `settings` | Categories, subject mappings, versioned windows and non-workdays |
| `health` | Health gaps, sensor states and the backstage view with traces |
| `validation`, `correction` | The comparison with Android Settings; redactions and exclusions |

## Decisions worth knowing

These follow the contract and the design where those are silent:

- A rewrite of an interval with a different start is answered `duplicate`, recorded in `interval_conflicts` and counted in the backstage view. It is not quarantined.
- More than 2000 events in a batch is answered `413 too_large`.
- An open phone session never counts past the last fact the phone reported. A day in progress is cut there and derived again once later facts arrive.
- When the screen state is unknown (the start of coverage, or after a reboot), the first resumed activity counts as the screen turning on, and so as an unlock.
- Validation compares app time only: measured is app time clipped to screen-on-and-unlocked; raw is the app intervals before clipping. Unattributed time is in neither.
- A day is partial when it starts before the coverage of a time sensor (`usage` or `activity`) of a device with an active installation.
- A health gap makes a day incomplete when more than three periods of it fall inside the awake window, so a gap that only grazes the window edge does not.
- In trends, an excluded day also removes its counterpart in the other span, so both sides cover the same weekdays.
- `GET /api/backstage` adds `database.alert` and `shorts.unconfirmedSince`; `GET /api/day` adds `hours[].segments` for traces.
