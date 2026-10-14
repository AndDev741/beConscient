-- Stretches of computer time the owner marked by hand on the page, for example
-- "studying from 20:00 to 21:45". The agent never says what was on screen, so a
-- label is the only way computer time gets a category other than Work or
-- Personal computer. Applied when reading, like the work window. Labels never
-- overlap; the service refuses one that would.
create table computer_labels (
    id          uuid        not null primary key,
    from_ts     bigint      not null,
    to_ts       bigint      not null,
    category_id uuid        not null references categories (id),
    created_at  timestamptz not null,
    check (to_ts > from_ts)
);
create index computer_labels_range on computer_labels (from_ts, to_ts);
