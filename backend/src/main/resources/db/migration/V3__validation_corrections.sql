-- The total the owner reads in Android Settings, per closed local day.
create table validation_inputs (
    day        date        not null primary key,
    minutes    int         not null check (minutes >= 0),
    entered_at timestamptz not null
);

-- Destructive redactions, audited without content.
create table redactions (
    id                  uuid        not null primary key,
    device_id           uuid        not null references devices (id),
    type                varchar(40) not null,
    from_ts             bigint      not null,
    to_ts               bigint      not null,
    field               varchar(64),
    affected            int         not null,
    quarantine_affected int         not null,
    created_at          timestamptz not null,
    created_by          varchar(64) not null
);

-- Reversible exclusions, applied at derivation.
create table exclusions (
    id         uuid         not null primary key,
    device_id  uuid         not null references devices (id),
    type       varchar(40),
    from_ts    bigint       not null,
    to_ts      bigint       not null,
    note       varchar(300),
    created_at timestamptz  not null
);
create index exclusions_device on exclusions (device_id);
