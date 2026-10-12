# Deploying beConscient

Production runs on a single-node k3s cluster managed by Flux from
[be-infra](https://github.com/AndDev741/be-infra). The manifests live there, in
`apps/beconscient` (namespace, quota, network policies, encrypted Secrets,
backend, frontend, backup jobs, image automation); the database is
`beconscient` in the cluster's shared CloudNativePG Postgres (`data/`), with
its roles declared in Git. This page keeps what is specific to beConscient.

## 1. Keys and secrets

Generate the production HPKE key pair (from `tools/`):

```sh
go run ./cmd/keygen -kid prod-$(date +%Y-%m) -public ~/.config/beconscient/keys/prod.public.json \
  -private ~/.config/beconscient/keys/prod.private.json
```

Keep the private key file out of the repository, and store a copy in a password manager. The collectors are built with the public file.

Secrets, all in the `beconscient` namespace:

| Secret | Keys |
|---|---|
| `beconscient-db` | `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` |
| `beconscient-auth` | `BECONSCIENT_OWNER_PASSWORD`, `BECONSCIENT_SESSION_KEY` (at least 32 characters) |
| `beconscient-hpke` | `BECONSCIENT_HPKE_KEYS`: a JSON array of `{"kid", "private_key"}` |
| `beconscient-backup` | `PGHOST`, `PGUSER`, `PGPASSWORD`, `RESTIC_REPOSITORY`, `RESTIC_PASSWORD`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `DATABASES`, `EXCLUDE_TABLE_DATA` |

They are committed to be-infra encrypted with SOPS (`apps/beconscient/secrets/`). The backend refuses to start in production when a secret is missing or still has a development value.

Keep `RESTIC_PASSWORD` in a password manager too. Without it, a lost disk loses the backups.

## 2. Database

The roles are declared in be-infra's `data/postgres.yaml`: `beconscient` owns only its database, and `beconscient_backup` can read the databases it dumps (`beconscient` and befree's) and nothing else. Its grants are listed in be-infra's README.

## 3. Backups

`apps/beconscient/backup.yaml` in be-infra. The nightly job dumps every database in `DATABASES` with `pg_dump -Fc` (derived tables listed in `EXCLUDE_TABLE_DATA` keep their schema but not their data) and stores the dumps with restic, which encrypts them before they leave the cluster. Retention: 7 daily, 4 weekly, 6 monthly. The weekly drill restores the newest snapshot into a throwaway Postgres inside the job and compares row counts with the live databases.

```sh
kubectl -n beconscient create job --from=cronjob/backup backup-now
kubectl -n beconscient create job --from=cronjob/restore-check restore-now
```

Take a manual backup before any deployment that carries a Flyway migration: Flyway does not roll back.

### Restoring after a disaster

1. On any machine, with the restic password from the password manager: `restic restore latest --host beconscient-backup --target ./restore`.
2. Let be-infra recreate the database and roles (step 2), then `pg_restore --no-owner --role=beconscient -d beconscient restore/dumps/beconscient.dump`.
3. Deploy the backend. It rebuilds the derived segments from the raw events.
4. On each collector, resend everything acknowledged since the backup date (`beconscient-agent replay --since <date>`, and "Resend since" on the phone). The server answers `duplicate` for events it already has.

## 4. Images and rollout

The `backend` and `frontend` workflows push `:latest` and `:<short sha>` to GHCR on every push to `main` (or a manual run). Flux image automation in be-infra commits each new `:latest` digest and rolls it out within a few minutes; roll back with `git revert` there.

The backend uses the `Recreate` strategy, so a deployment has a short downtime. Collectors queue events meanwhile.

The backend image is native, built with `-march=x86-64-v2` (set in `application.properties`) for nodes without AVX2.

## 5. Exposure

beconscient.beyouweb.com reaches the frontend through the Cloudflare Tunnel and Traefik. The nginx in the frontend image forwards `/ingest/` to the backend with the body untouched and caps it at 1 MiB, and `/api/` with the session cookie. Make sure the zone does not challenge non-browser clients on `/ingest/` (Bot Fight Mode; Browser Integrity Check needs a normal `User-Agent`, which the collectors send).
