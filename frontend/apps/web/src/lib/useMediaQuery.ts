import { useEffect, useState } from 'react';

/**
 * Subscribe to a CSS media query and re-render on changes.
 *
 * SSR/jsdom safe: when `window.matchMedia` is unavailable (e.g. the vitest jsdom
 * environment) it resolves to `false` without throwing, so components fall back
 * to their mobile-first layout in tests unless `matchMedia` is explicitly stubbed.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => readMatch(query));

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(query);
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches);
    // Sync immediately in case the query changed between render and effect.
    setMatches(mql.matches);
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, [query]);

  return matches;
}

function readMatch(query: string): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(query).matches;
}

/** Tailwind `md` breakpoint (768px) — the desktop/mobile cutover for the map shell. */
export const DESKTOP_QUERY = '(min-width: 768px)';
