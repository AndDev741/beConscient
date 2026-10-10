-- Starting categories for common apps and sites, so the first days are not all
-- "Uncategorized". They are ordinary mappings: the owner can change any of them,
-- and an existing mapping is never overwritten.
insert into subject_mappings (subject_key, category_id, updated_at)
select v.subject_key, v.category_id::uuid, now()
from (values
    ('app:com.google.android.youtube',          '00000000-0000-0000-0000-000000000011'),
    ('app:com.google.android.youtube#shorts',   '00000000-0000-0000-0000-000000000011'),
    ('site:youtube',                            '00000000-0000-0000-0000-000000000011'),
    ('app:com.netflix.mediaclient',             '00000000-0000-0000-0000-000000000011'),
    ('app:com.zhiliaoapp.musically',            '00000000-0000-0000-0000-000000000011'),
    ('app:com.whatsapp',                        '00000000-0000-0000-0000-000000000012'),
    ('app:com.whatsapp.w4b',                    '00000000-0000-0000-0000-000000000012'),
    ('app:org.telegram.messenger',              '00000000-0000-0000-0000-000000000012'),
    ('app:org.thoughtcrime.securesms',          '00000000-0000-0000-0000-000000000012'),
    ('app:com.google.android.apps.messaging',   '00000000-0000-0000-0000-000000000012'),
    ('app:com.facebook.orca',                   '00000000-0000-0000-0000-000000000012'),
    ('app:com.instagram.android',               '00000000-0000-0000-0000-000000000013'),
    ('app:com.facebook.katana',                 '00000000-0000-0000-0000-000000000013'),
    ('app:com.twitter.android',                 '00000000-0000-0000-0000-000000000013'),
    ('app:com.reddit.frontpage',                '00000000-0000-0000-0000-000000000013'),
    ('app:com.linkedin.android',                '00000000-0000-0000-0000-000000000013'),
    ('site:linkedin',                           '00000000-0000-0000-0000-000000000013'),
    ('app:com.spotify.music',                   '00000000-0000-0000-0000-000000000014'),
    ('app:com.google.android.apps.youtube.music','00000000-0000-0000-0000-000000000014'),
    ('app:com.android.chrome',                  '00000000-0000-0000-0000-000000000015'),
    ('app:org.mozilla.firefox',                 '00000000-0000-0000-0000-000000000015'),
    ('app:com.brave.browser',                   '00000000-0000-0000-0000-000000000015'),
    ('app:com.google.android.googlequicksearchbox','00000000-0000-0000-0000-000000000015')
) as v(subject_key, category_id)
on conflict (subject_key) do nothing;
