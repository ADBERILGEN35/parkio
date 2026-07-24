import {
  ROUTE_IDS,
  buildRoutePath,
  isRedirectEligiblePath,
} from '@/routing/route-manifest';

const SAFE_DEFAULT_REDIRECT = buildRoutePath(ROUTE_IDS.MAP);

function pathnameFromRedirect(value: unknown): string | null {
  if (typeof value === 'string') {
    if (!value.startsWith('/') || value.startsWith('//')) {
      return null;
    }
    return value.split(/[?#]/, 1)[0] ?? null;
  }
  if (!value || typeof value !== 'object') {
    return null;
  }
  const pathname = (value as { pathname?: unknown }).pathname;
  return typeof pathname === 'string' ? pathname : null;
}

function recognizedInternalRoute(value: unknown): string | null {
  const pathname = pathnameFromRedirect(value);
  if (!pathname) {
    return null;
  }
  const candidate = pathname.replace(/\/+$/, '') || '/';
  if (isRedirectEligiblePath(candidate)) {
    return candidate;
  }
  return null;
}

/** Returns only a route already recognized by the frozen Web router. */
export function sanitizeInternalRedirect(
  value: unknown,
  fallback: unknown = SAFE_DEFAULT_REDIRECT,
): string {
  return (
    recognizedInternalRoute(value) ??
    recognizedInternalRoute(fallback) ??
    SAFE_DEFAULT_REDIRECT
  );
}
