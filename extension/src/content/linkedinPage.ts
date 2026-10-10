// LinkedIn page reading. Sends only a coarse page kind derived from the
// path. It never reads document.title (titles carry names of people and
// companies) and never sends the URL.

import { pageKindFromPath } from '../pageKind';
import type { PageState } from '../types';

export function createLinkedinReader(doc: Document, loc: { pathname: string }): () => PageState {
  return () => ({
    site: 'linkedin',
    visible: doc.visibilityState === 'visible',
    focused: doc.hasFocus(),
    // Feed videos autoplay muted; LinkedIn time counts only with focus.
    playing: false,
    title: null,
    pageKind: pageKindFromPath(loc.pathname),
  });
}
