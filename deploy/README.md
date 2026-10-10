# Deploying beConscient

The reference deployment is a single-node k3s cluster with:

- a Postgres 17 instance that already runs in the cluster and is shared with another application (beConscient gets its own database and roles);
- a registry that listens only on the node's loopback (`127.0.0.1:5000`);
- a TLS-terminating proxy (for example a Cloudflare Tunnel) in front of the frontend NodePort.

Nothing here needs root on the node.

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

Create them with `kubectl create secret generic ... --from-env-file=<file>` from a file outside the repository, or pipe a manifest into `kubectl apply -f -`. The backend refuses to start in production when a secret is missing or still has a development value.

Keep `RESTIC_PASSWORD` in a password manager too. Without it, a lost disk loses the backups.

## 2. Database

Run once, as a superuser of the shared instance:

```sh
kubectl apply -f deploy/00-namespace.yaml
{ printf "\\set app_password '%s'\n\\set backup_password '%s'\n\\set other_db <other database>\n" "$APP_PW" "$BACKUP_PW"
  cat deploy/db/setup.sql; } | kubectl -n <db namespace> exec -i <postgres pod> -- psql -U <superuser> -d postgres -q -f -
```

The `beconscient` role owns only its database. The `beconscient_backup` role reads both databases and nothing else. Passwords travel on standard input, never on a command line.

## 3. Backups

```sh
kubectl apply -f deploy/backup.yaml
kubectl -n beconscient create job --from=cronjob/backup backup-now
kubectl -n beconscient create job --from=cronjob/restore-check restore-now
```

The nightly job dumps every database in `DATABASES` with `pg_dump -Fc` (derived tables listed in `EXCLUDE_TABLE_DATA` keep their schema but not their data) and stores the dumps with restic, which encrypts them before they leave the cluster. Retention: 7 daily, 4 weekly, 6 monthly. The weekly drill restores the newest snapshot into a throwaway Postgres inside the job and compares row counts with the live databases.

Take a manual backup before any deployment that carries a Flyway migration: Flyway does not roll back.

### Restoring after a disaster

1. On any machine, with the restic password from the password manager: `restic restore latest --host beconscient-backup --target ./restore`.
2. Recreate the database and roles (step 2), then `pg_restore --no-owner -d beconscient restore/dumps/beconscient.dump`.
3. Deploy the backend. It rebuilds the derived segments from the raw events.
4. On each collector, resend everything acknowledged since the backup date (`beconscient-agent replay --since <date>`, and "Resend since" on the phone). The server answers `duplicate` for events it already has.

## 4. Images and rollout

```sh
deploy/scripts/publish-images.sh <ssh host>      # builds and pushes both images
kubectl apply -f deploy/backend.yaml -f deploy/frontend.yaml
kubectl -n beconscient set image deploy/beconscient-backend backend=localhost:5000/beconscient-backend@<digest>
kubectl -n beconscient set image deploy/beconscient-frontend nginx=localhost:5000/beconscient-frontend@<digest>
kubectl -n beconscient rollout status deploy/beconscient-backend
```

The backend uses the `Recreate` strategy, so a deployment has a short downtime. Collectors queue events meanwhile.

`publish-images.sh` builds a native backend image with `-march=x86-64-v2` for nodes without AVX2. Pass `--jvm` to build the JVM image instead (raise the memory limit in `backend.yaml` to about 384Mi).

## 5. Exposure

Point the proxy at the frontend NodePort (`30081`). The nginx in the frontend image forwards `/ingest/` to the backend with the body untouched and caps it at 1 MiB, and `/api/` with the session cookie. With a Cloudflare Tunnel managed through the API, the ingress list is a single object: send the full list, then create the proxied DNS record for the new hostname. Make sure the zone does not challenge non-browser clients on `/ingest/` (Bot Fight Mode; Browser Integrity Check needs a normal `User-Agent`, which the collectors send).
