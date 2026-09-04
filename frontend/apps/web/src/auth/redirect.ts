import {
  ROUTE_IDS,
  buildRoutePath,
  isRedirectEligiblePath,
} from '@/routing/route-manifest';

const SAFE_DEFAULT_REDIRECT = buildRoutePath(ROUTE_IDS.MAP);
export const AUTH_RETURN_QUERY_PARAM = 'return';

interface SanitizedRedirectTarget {
  readonly pathname: string;
  readonly search: string;
}

function hasControlCharacters(value: string): boolean {
  return [...value].some((character) => {
    const code = character.charCodeAt(0);
    return code >= 0 && code <= 31;
  });
}

function pathnameLooksUnsafe(value: string): boolean {
  return (
    value.includes('\\') ||
    /\/\.(?:\/|$)/.test(value) ||
    /\/\.\.(?:\/|$)/.test(value) ||
    hasControlCharacters(value)
  );
}

function sanitizeSearch(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) {
    return '';
  }
  if (!value.startsWith('?') || value.includes('#') || hasControlCharacters(value)) {
    return '';
  }
  return value;
}

function redirectTargetFromString(value: string): SanitizedRedirectTarget | null {
  if (!value.startsWith('/') || value.startsWith('//')) {
    return null;
  }
  const rawPath = value.split(/[?#]/, 1)[0] ?? '';
  if (pathnameLooksUnsafe(rawPath)) {
    return null;
  }
  try {
    const url = new URL(value, 'https://parkio.internal');
    return {
      pathname: url.pathname,
      search: sanitizeSearch(url.search),
    };
  } catch {
    return null;
  }
}

function redirectTarget(value: unknown): SanitizedRedirectTarget | null {
  if (typeof value === 'string') {
    return redirectTargetFromString(value);
  }
  if (!value || typeof value !== 'object') {
    return null;
  }
  const pathname = (value as { pathname?: unknown }).pathname;
  if (typeof pathname !== 'string' || pathnameLooksUnsafe(pathname)) {
    return null;
  }
  return {
    pathname,
    search: sanitizeSearch((value as { search?: unknown }).search),
  };
}

function recognizedInternalRoute(value: unknown): SanitizedRedirectTarget | null {
  const target = redirectTarget(value);
  if (!target) {
    return null;
  }
  const candidate = target.pathname.replace(/\/+$/, '') || '/';
  if (isRedirectEligiblePath(candidate)) {
    return {
      pathname: candidate,
      search: target.search,
    };
  }
  return null;
}

/** Returns only a route already recognized by the frozen Web router. */
export function sanitizeInternalRedirect(
  value: unknown,
  fallback: unknown = SAFE_DEFAULT_REDIRECT,
): string {
  const target =
    recognizedInternalRoute(value) ??
    recognizedInternalRoute(fallback);
  if (!target) {
    return SAFE_DEFAULT_REDIRECT;
  }
  return `${target.pathname}${target.search}`;
}

export function createSanitizedLoginReturnSearch(value: unknown): string {
  const params = new URLSearchParams();
  params.set(AUTH_RETURN_QUERY_PARAM, sanitizeInternalRedirect(value));
  return `?${params.toString()}`;
}
