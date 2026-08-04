import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import { Card, PageShell } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import {
  Navigate,
  Outlet,
  useLocation,
  useMatches,
  type UIMatch,
} from 'react-router-dom';
import {
  createSanitizedLoginReturnSearch,
  sanitizeInternalRedirect,
} from '@/auth/redirect';
import { useAuthStore } from '@/auth/store';
import { RouteFallback } from '@/components/RouteFallback';
import { AccountSuspendedPage } from '@/pages/AccountSuspendedPage';
import {
  AUTH_LIFECYCLE_DESTINATIONS,
  ROUTE_MANIFEST,
  getRoutePath,
  type RouteLifecyclePolicy,
  type RouteManifestEntry,
  type RouteRoleRequirement,
} from './route-manifest';

interface MatchedRoutePolicy {
  readonly lifecycle: RouteLifecyclePolicy;
  readonly role: RouteRoleRequirement;
}

function manifestEntryForMatch(
  match: UIMatch<unknown, unknown>,
): RouteManifestEntry | undefined {
  return ROUTE_MANIFEST.find((entry) => entry.id === match.id);
}

function matchedRoutePolicy(
  matches: readonly UIMatch<unknown, unknown>[],
): MatchedRoutePolicy {
  const entries = matches
    .map(manifestEntryForMatch)
    .filter((entry): entry is RouteManifestEntry => Boolean(entry))
    .reverse();

  return {
    lifecycle:
      entries.find((entry) => entry.lifecycle !== 'inherit')?.lifecycle ??
      'protected-entry',
    role:
      entries.find((entry) => entry.role !== 'inherit')?.role ?? 'none',
  };
}

function RoleDenied({
  requirement,
}: {
  readonly requirement: 'privileged' | 'admin';
}) {
  const { t } = useTranslation('common');

  return (
    <PageShell title={t('accessDenied.title')}>
      <Card title={t('accessDenied.forbidden')}>
        <p>
          {requirement === 'admin'
            ? t('accessDenied.adminRequired')
            : t('accessDenied.privilegedRequired')}
        </p>
      </Card>
    </PageShell>
  );
}

/**
 * The sole protected-route presentation policy owner. It projects the settled
 * WP-02 lifecycle through metadata from the canonical route manifest; it never
 * restores, refreshes, mutates, or authorizes a session.
 */
export function RoutePolicyBoundary() {
  const matches = useMatches();
  const location = useLocation();
  const bootstrapPending = useAuthStore((state) => state.bootstrapPending);
  const lifecycle = useAuthStore((state) => state.lifecycle);
  const restriction = useAuthStore((state) => state.restriction);
  const roles = useAuthStore((state) => state.roles);
  const routePolicy = matchedRoutePolicy(matches);

  if (bootstrapPending || lifecycle === 'bootstrapping') {
    return <RouteFallback />;
  }

  if (lifecycle === 'account-restricted') {
    if (restriction === 'ACCOUNT_NOT_ACTIVE') {
      return <AccountSuspendedPage />;
    }
    if (restriction === 'ACCOUNT_NOT_VERIFIED') {
      return (
        <Navigate
          to={getRoutePath(
            AUTH_LIFECYCLE_DESTINATIONS.accountNotVerified,
          )}
          replace
        />
      );
    }
  }

  if (lifecycle === 'provisioning') {
    if (routePolicy.lifecycle === 'provisioning-only') {
      return <Outlet />;
    }
    return (
      <Navigate
        to={getRoutePath(AUTH_LIFECYCLE_DESTINATIONS.provisioning)}
        replace
      />
    );
  }

  if (lifecycle === 'anonymous') {
    const returnPath = sanitizeInternalRedirect({
      pathname: location.pathname,
      search: location.search,
    });
    return (
      <Navigate
        to={{
          pathname: getRoutePath(AUTH_LIFECYCLE_DESTINATIONS.anonymous),
          search: createSanitizedLoginReturnSearch(returnPath),
        }}
        replace
      />
    );
  }

  if (routePolicy.lifecycle === 'provisioning-only') {
    return (
      <Navigate
        to={getRoutePath(
          AUTH_LIFECYCLE_DESTINATIONS.authenticatedDefault,
        )}
        replace
      />
    );
  }

  if (
    routePolicy.role === 'admin' &&
    !hasAdminRole(roles)
  ) {
    return <RoleDenied requirement="admin" />;
  }

  if (
    routePolicy.role === 'privileged' &&
    !hasPrivilegedRole(roles)
  ) {
    return <RoleDenied requirement="privileged" />;
  }

  return <Outlet />;
}
