import { useCallback, useEffect, useRef, useState } from 'react';

export interface Loaded<T> {
  /** Only ever data for the current key: switching day never shows the previous day's numbers. */
  data: T | null;
  error: string | null;
  loading: boolean;
  reload: () => void;
  /** Replace the data after a write that returned the new state. */
  set: (value: T) => void;
}

interface LoadState<T> {
  key: string;
  data: T | null;
  error: string | null;
  loading: boolean;
}

/**
 * Load once per key, expose the states the UI draws, and give callers a reload
 * they can fire after a write. A reload under the same key keeps the old data on
 * screen while it runs; a new key starts empty.
 */
export function useLoad<T>(load: () => Promise<T>, key: string): Loaded<T> {
  const [state, setState] = useState<LoadState<T>>({ key, data: null, error: null, loading: true });
  const [nonce, setNonce] = useState(0);

  // `load` is a fresh closure every render, so the key is what decides a refetch
  const run = useCallback(load, [key, nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let live = true;
    setState((s) => ({ key, data: s.key === key ? s.data : null, error: null, loading: true }));
    run()
      .then((value) => {
        if (live) setState({ key, data: value, error: null, loading: false });
      })
      .catch((e: unknown) => {
        if (!live) return;
        const message = e instanceof Error ? e.message : 'Network failure.';
        setState((s) => ({ key, data: s.key === key ? s.data : null, error: message, loading: false }));
      });
    return () => {
      live = false;
    };
  }, [run, key]);

  const fresh = state.key === key;
  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const set = useCallback((value: T) => setState({ key, data: value, error: null, loading: false }), [key]);
  return {
    data: fresh ? state.data : null,
    error: fresh ? state.error : null,
    loading: !fresh || state.loading,
    reload,
    set,
  };
}

/** Refetch when something elsewhere changed (a mapping, a window), without blanking the screen. */
export function useRevision(reload: () => void, revision: number) {
  const first = useRef(revision);
  useEffect(() => {
    if (revision !== first.current) reload();
  }, [revision]); // eslint-disable-line react-hooks/exhaustive-deps
}

/** Runs `fn` every `ms` while `enabled`. */
export function useInterval(fn: () => void, ms: number, enabled: boolean) {
  const saved = useRef(fn);
  useEffect(() => {
    saved.current = fn;
  });
  useEffect(() => {
    if (!enabled) return;
    const id = setInterval(() => saved.current(), ms);
    return () => clearInterval(id);
  }, [ms, enabled]);
}

export function useDebounced<T>(value: T, ms: number): T {
  const [out, setOut] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setOut(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return out;
}

/** Width of an element, or the fallback where ResizeObserver does not exist (tests, old browsers). */
export function useWidth<E extends HTMLElement>(fallback: number) {
  const ref = useRef<E | null>(null);
  const [width, setWidth] = useState(fallback);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w && w > 0) setWidth(w);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return { ref, width };
}

const THEME_KEY = 'beconscient-theme';

/** Light by default, dark when the system says so, and a manual override that sticks. */
export function useTheme() {
  const [theme, setTheme] = useState<'light' | 'dark' | null>(() => {
    try {
      const saved = localStorage.getItem(THEME_KEY);
      return saved === 'light' || saved === 'dark' ? saved : null;
    } catch {
      return null;
    }
  });

  useEffect(() => {
    const root = document.documentElement;
    if (theme) root.setAttribute('data-theme', theme);
    else root.removeAttribute('data-theme');
    try {
      if (theme) localStorage.setItem(THEME_KEY, theme);
      else localStorage.removeItem(THEME_KEY);
    } catch {
      // private mode: the toggle still works for this tab
    }
  }, [theme]);

  const isDark = theme
    ? theme === 'dark'
    : typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches;

  return { isDark, toggle: () => setTheme(isDark ? 'light' : 'dark') };
}
