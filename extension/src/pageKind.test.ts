import { describe, expect, it } from 'vitest';
import { pageKindFromPath } from './pageKind';

describe('pageKindFromPath', () => {
  it.each([
    ['/feed', 'feed'],
    ['/feed/', 'feed'],
    ['/feed/update/urn:li:activity:123/', 'feed'],
    ['/messaging', 'messaging'],
    ['/messaging/thread/2-abc/', 'messaging'],
    ['/in/jane-doe/', 'profile'],
    ['/in/jane-doe/recent-activity/all/', 'profile'],
    ['/jobs', 'jobs'],
    ['/jobs/view/4000000000/', 'jobs'],
    ['/', 'other'],
    ['/company/acme/', 'other'],
    ['/notifications/', 'other'],
    ['/feedback', 'other'],
    ['/jobsearch', 'other'],
    ['/in', 'other'],
    ['/FEED/', 'feed'],
  ])('%s -> %s', (path, kind) => {
    expect(pageKindFromPath(path)).toBe(kind);
  });
});
