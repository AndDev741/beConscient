-- One-time setup of the beConscient database inside a shared Postgres 17.
-- Run as a superuser with psql variables:
--   psql -v app_password=... -v backup_password=... -v other_db=<name of the other app's database> -f setup.sql
--
-- The app role owns only its database and cannot connect anywhere else.
-- The backup role can read every table of both databases and nothing more.

\set ON_ERROR_STOP on

CREATE ROLE beconscient LOGIN PASSWORD :'app_password';
CREATE DATABASE beconscient OWNER beconscient;

-- Nobody connects to a database through PUBLIC. Owners and superusers keep access.
REVOKE CONNECT ON DATABASE beconscient FROM PUBLIC;
REVOKE CONNECT ON DATABASE :"other_db" FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;

CREATE ROLE beconscient_backup LOGIN PASSWORD :'backup_password';
GRANT pg_read_all_data TO beconscient_backup;
GRANT CONNECT ON DATABASE beconscient TO beconscient_backup;
GRANT CONNECT ON DATABASE :"other_db" TO beconscient_backup;

-- Keep the write-ahead log small on a shared single-node instance.
ALTER SYSTEM SET max_wal_size = '256MB';
SELECT pg_reload_conf();
