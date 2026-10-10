-- Devices, installations, pairing, raw events and everything the ingest path writes.
-- Hand-written; Hibernate never generates DDL here. Times from devices are epoch
-- milliseconds (bigint); server times are timestamptz.

-- Panache sequences use allocationSize 50, hence "increment by 50"
create sequence app_users_SEQ start with 1 increment by 50;

create table app_users (
    id       bigint       not null primary key,
    username varchar(64)  not null unique,
    password varchar(255) not null,
    role     varchar(64)  not null
);

create table devices (
    id         uuid        not null primary key,
    name       varchar(80) not null,
    kind       varchar(16) not null check (kind in ('android', 'desktop')),
    created_at timestamptz not null
);

create table installations (
    id              uuid        not null primary key,
    device_id       uuid        not null references devices (id),
    -- SHA-256 of the 32-byte secret; the secret itself is never stored
    credential_hash bytea       not null,
    fingerprint     varchar(9)  not null,
    client_name     varchar(80),
    client_version  varchar(32),
    created_at      timestamptz not null,
    last_seen_at    timestamptz,
    revoked_at      timestamptz,
    clone_suspected boolean     not null default false,
    last_seq        bigint      not null default 0,
    last_tz         varchar(64)
);
create index installations_device on installations (device_id);

create table pairing_codes (
    id             uuid        not null primary key,
    device_id      uuid        not null references devices (id),
    -- SHA-256 of the normalised 8-character code; the code itself is never stored
    code_hash      bytea       not null unique,
    created_at     timestamptz not null,
    expires_at     timestamptz not null,
    used_at        timestamptz,
    invalidated_at timestamptz
);

-- One row: consecutive failed pairing attempts across all codes
create table pairing_state (
    id                   int         not null primary key check (id = 1),
    consecutive_failures int         not null default 0,
    locked_out           boolean     not null default false,
    locked_out_at        timestamptz
);
insert into pairing_state (id) values (1);

-- One row per accepted batch: the frozen id-and-revision list and the receipt
-- that is returned again, newly encrypted, when the same batch is resent.
create table batch_log (
    installation_id  uuid        not null references installations (id),
    seq              bigint      not null,
    list_hash        bytea       not null,
    receipt          text        not null,
    received_at      timestamptz not null,
    sent_at          bigint,
    clock_skew_ms    bigint,
    tz               varchar(64),
    tz_offset_min    int,
    client_version   varchar(32),
    contract_version int,
    event_count      int         not null,
    gap_before       bigint      not null default 0,
    primary key (installation_id, seq)
);
create index batch_log_received on batch_log (received_at);

create table raw_events (
    id              bigserial   not null primary key,
    device_id       uuid        not null references devices (id),
    installation_id uuid        not null references installations (id),
    event_id        uuid        not null,
    type            varchar(40) not null,
    -- "at" for point events, "start" for intervals
    ts              bigint      not null,
    end_ts          bigint,
    rev             int,
    tz              varchar(64),
    tz_offset_min   int,
    natural_key     text,
    -- the allowed type-specific fields, after the contract filter
    payload         jsonb       not null,
    suspect         boolean     not null default false,
    received_at     timestamptz not null,
    batch_seq       bigint      not null,
    unique (device_id, event_id)
);
create unique index raw_events_natural_key on raw_events (device_id, type, natural_key) where natural_key is not null;
create index raw_events_device_type_ts on raw_events (device_id, type, ts);
create index raw_events_device_ts on raw_events (device_id, ts);
create index raw_events_installation_type_ts on raw_events (installation_id, type, ts);
create index raw_events_device_type_end on raw_events (device_id, type, end_ts) where end_ts is not null;

-- Rejected events, with disallowed fields already stripped
create table quarantine (
    id              bigserial   not null primary key,
    device_id       uuid        not null references devices (id),
    installation_id uuid        not null,
    event_id        varchar(64),
    type            varchar(64),
    ts              bigint,
    reason          varchar(40) not null,
    payload         jsonb       not null,
    received_at     timestamptz not null
);
create index quarantine_device on quarantine (device_id, ts);

-- A rewrite of an interval with a different start: recorded and ignored
create table interval_conflicts (
    id           bigserial   not null primary key,
    device_id    uuid        not null references devices (id),
    event_id     uuid        not null,
    stored_start bigint      not null,
    sent_start   bigint      not null,
    sent_rev     int,
    received_at  timestamptz not null
);

create table device_counters (
    device_id          uuid   not null primary key references devices (id),
    duplicates         bigint not null default 0,
    rejected           bigint not null default 0,
    dropped_fields     bigint not null default 0,
    interval_conflicts bigint not null default 0
);

-- Dirty marks are rows with ids: a derivation pass claims ids, writes segments
-- and deletes exactly those ids in one transaction.
create table dirty_blocks (
    id         bigserial   not null primary key,
    device_id  uuid        not null references devices (id),
    day        date        not null,
    created_at timestamptz not null default now()
);
create index dirty_blocks_device on dirty_blocks (device_id, day);

-- When each sensor first reported (server time, the anchor for suspect events)
-- and the earliest fact it reported (device time, the coverage start).
create table sensor_coverage (
    device_id         uuid        not null references devices (id),
    sensor            varchar(40) not null,
    first_reported_at timestamptz not null,
    first_event_at    bigint,
    primary key (device_id, sensor)
);

-- The phone's zone over time, from android.tz_change markers and batch headers
create table zone_points (
    id        bigserial   not null primary key,
    device_id uuid        not null references devices (id),
    at        bigint      not null,
    zone      varchar(64) not null,
    source    varchar(16) not null check (source in ('marker', 'header')),
    event_id  bigint
);
create index zone_points_at on zone_points (at);
