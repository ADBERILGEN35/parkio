import type { AnalyticsScreenName } from '@parkio/types';
import { trimAsciiSlashes } from './pathTrim';

/** Hard cap — router paths are short; rejects pathological library input early. */
const MAX_ROUTE_CHARS = 512;

/**
 * Normalize pathname / Expo route segments to a closed screen inventory.
 * Never returns raw query strings or facility IDs.
 */
export function normalizeAnalyticsScreenName(
  pathOrRoute: string | null | undefined,
): AnalyticsScreenName {
  if (!pathOrRoute) return 'other';
  // Strip query/hash before length bound so `?…` cannot inflate matching work.
  const bare = pathOrRoute.split('?')[0]?.split('#')[0] ?? '';
  if (bare.length > MAX_ROUTE_CHARS) return 'other';
  const path = trimAsciiSlashes(bare).toLowerCase();

  if (path === '' || path === 'map' || path.endsWith('/(tabs)/map') || path.includes('(tabs)/map')) {
    return 'map';
  }
  if (path === 'explore' || path.includes('public-explore') || path.includes('explore')) {
    // Prefer exact explore over accidental matches
    if (
      path === 'explore' ||
      path.endsWith('/explore') ||
      path.includes('publicexplore') ||
      path.includes('public-explore')
    ) {
      return 'public_explore';
    }
  }
  if (path.startsWith('facilities/') || path.includes('/facilities/') || path.includes('facilities/[id]')) {
    return 'facility_detail';
  }
  if (path === 'login' || path.endsWith('/login') || path.includes('(auth)/login')) {
    return 'login';
  }
  if (path === 'register' || path.endsWith('/register') || path.includes('(auth)/register')) {
    return 'register';
  }
  if (path.includes('preferences') || path.endsWith('/preferences')) {
    return 'preferences';
  }
  if (path === 'profile' || path.endsWith('/profile') || path.includes('(tabs)/profile')) {
    return 'profile';
  }
  return 'other';
}
