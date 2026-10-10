-- Derivation (blocks, segments, unlocks) and the read-time settings.

create table derivation_state (
    id             int         not null primary key check (id = 1),
    -- the version every stored segment has once no rebuild is running
    version        int         not null,
    rebuilding     boolean     not null default false,
    target_version int,
    total_blocks   int         not null default 0,
    done_blocks    int         not null default 0,
    started_at     timestamptz,
    finished_at    timestamptz
);
insert into derivation_state (id, version) values (1, 0);

-- A block is one device on one UTC day. It keeps the state it starts and ends in,
-- so a changed end state can mark the next block dirty.
create table blocks (
    device_id            uuid        not null references devices (id),
    day                  date        not null,
    version              int         not null,
    start_state          jsonb,
    end_state            jsonb,
    -- set when the block was cut at the last known fact of the device (a day in
    -- progress); such a block is derived again once later facts arrive
    capped_at            bigint,
    segment_count        int         not null default 0,
    invariant_violations int         not null default 0,
    violation_detail     varchar(300),
    derived_at           timestamptz not null,
    primary key (device_id, day)
);

-- Screen-time segments in UTC. Never overlapping within a device.
create table segments (
    id               bigserial   not null primary key,
    device_id        uuid        not null references devices (id),
    block_day        date        not null,
    start_ts         bigint      not null,
    end_ts           bigint      not null,
    kind             varchar(16) not null check (kind in ('app', 'unattributed', 'computer', 'site')),
    subject          varchar(300),
    source_event_ids bigint[]    not null default '{}',
    rules            text[]      not null default '{}',
    version          int         not null,
    check (end_ts > start_ts)
);
create index segments_range on segments (start_ts, end_ts);
create index segments_block on segments (device_id, block_day);
create index segments_subject on segments (subject, start_ts);

-- Phone app intervals before clipping to screen-on-and-unlocked, for the
-- raw-versus-clipped column of the validation view.
create table raw_intervals (
    id        bigserial not null primary key,
    device_id uuid      not null references devices (id),
    block_day date      not null,
    start_ts  bigint    not null,
    end_ts    bigint    not null,
    version   int       not null
);
create index raw_intervals_range on raw_intervals (device_id, start_ts);
create index raw_intervals_block on raw_intervals (device_id, block_day);

create table unlocks (
    id              bigserial    not null primary key,
    device_id       uuid         not null references devices (id),
    block_day       date         not null,
    at              bigint       not null,
    first_app       varchar(255),
    source_event_id bigint,
    version         int          not null
);
create index unlocks_at on unlocks (at);
create index unlocks_block on unlocks (device_id, block_day);

-- The blocks a version rebuild still has to derive, so it resumes after a restart
create table rebuild_blocks (
    device_id uuid    not null,
    day       date    not null,
    done      boolean not null default false,
    primary key (device_id, day)
);

create table categories (
    id         uuid        not null primary key,
    name       varchar(60) not null unique,
    color      varchar(9)  not null,
    builtin    boolean     not null default false,
    sort_order int         not null default 100
);
insert into categories (id, name, color, builtin, sort_order) values
    ('00000000-0000-0000-0000-000000000001', 'Work',              '#5b6cff', true,  1),
    ('00000000-0000-0000-0000-000000000002', 'Personal computer', '#8e7cc3', true,  2),
    ('00000000-0000-0000-0000-000000000003', 'Uncategorized',     '#a7a7a7', true,  3),
    ('00000000-0000-0000-0000-000000000004', 'Unattributed',      '#d6cfc2', true,  4),
    ('00000000-0000-0000-0000-000000000011', 'Video',             '#e4572e', false, 10),
    ('00000000-0000-0000-0000-000000000012', 'Messaging',         '#17a398', false, 11),
    ('00000000-0000-0000-0000-000000000013', 'Social',            '#f2a541', false, 12),
    ('00000000-0000-0000-0000-000000000014', 'Music',             '#76b041', false, 13),
    ('00000000-0000-0000-0000-000000000015', 'Browsing',          '#2e86ab', false, 14);

-- Categories are applied when reading, so a mapping change fixes the past.
create table subject_mappings (
    subject_key varchar(300) not null primary key,
    category_id uuid         not null references categories (id),
    updated_at  timestamptz  not null
);

-- Work and awake windows, one version per local day they became valid.
create table window_settings (
    id         bigserial   not null primary key,
    kind       varchar(8)  not null check (kind in ('work', 'awake')),
    valid_from date        not null,
    days       varchar(20),
    start_time varchar(5)  not null,
    end_time   varchar(5)  not null,
    updated_at timestamptz not null,
    unique (kind, valid_from)
);
insert into window_settings (kind, valid_from, days, start_time, end_time, updated_at) values
    ('work',  '2000-01-01', '1,2,3,4,5', '09:00', '18:00', now()),
    ('awake', '2000-01-01', null,        '07:00', '23:00', now());

create table non_workdays (
    day date not null primary key
);
