import { describe, expect, it } from 'vitest';
import {
  ROUTE_IDS,
  ROUTE_MANIFEST,
  buildRoutePath,
  classifyRoutePath,
  findRouteManifestEntryByPath,
  getProtectedRouteIds,
  getPublicRouteIds,
  getRedirectEligibleRouteIds,
  getRouteDocumentTitleKey,
  getRoutePath,
  isNavigationInterruptionBypassPath,
  isRedirectEligiblePath,
  isValidRouteParameter,
  validateRouteManifest,
  type RouteId,
  type RouteManifestEntry,
} from './route-manifest';

const validUuid = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

function replaceRoute(
  routeId: RouteId,
  changes: Partial<RouteManifestEntry>,
): readonly RouteManifestEntry[] {
  return ROUTE_MANIFEST.map((entry) =>
    entry.id === routeId
      ? ({ ...entry, ...changes } as RouteManifestEntry)
      : entry,
  );
}

function violationCodes(
  manifest: readonly RouteManifestEntry[],
): readonly string[] {
  return validateRouteManifest(manifest).map((violation) => violation.code);
}

function manifestFingerprint(value: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}

describe('canonical route inventory', () => {
  it('contains every existing URL exactly once without changing paths or aliases', () => {
    const concretePaths = ROUTE_MANIFEST.filter(
      (entry) => entry.kind === 'path',
    ).map((entry) =>
      entry.path === '*' ? entry.path : getRoutePath(entry.id),
    );

    expect(concretePaths).toHaveLength(new Set(concretePaths).size);
    expect(concretePaths).toEqual([
      '/login',
      '/register',
      '/forgot-password',
      '/reset-password',
      '/check-email',
      '/verify-email',
      '/terms',
      '/privacy',
      '/',
      '/preparing',
      '/map',
      '/spots/:spotId',
      '/my-spots',
      '/upload',
      '/profile',
      '/reports',
      '/notifications',
      '/gamification',
      '/leaderboard',
      '/moderation',
      '/admin/moderation',
      '/analytics',
      '/admin',
      '/admin/users',
      '/admin/users/:id',
      '/admin/security',
      '/admin/analytics',
      '/admin/audit',
      '/admin/system',
      '*',
    ]);
    expect(getRoutePath(ROUTE_IDS.ADMIN_DASHBOARD)).toBe('/admin');
  });

  it('has one stable ID per node and keeps all manifest data deeply frozen', () => {
    const routeIds = ROUTE_MANIFEST.map((entry) => entry.id);

    expect(routeIds).toHaveLength(new Set(routeIds).size);
    expect(routeIds).toHaveLength(Object.keys(ROUTE_IDS).length);
    expect(Object.isFrozen(ROUTE_MANIFEST)).toBe(true);
    for (const entry of ROUTE_MANIFEST) {
      expect(Object.isFrozen(entry)).toBe(true);
      expect(Object.isFrozen(entry.parameters)).toBe(true);
      if (entry.redirect) {
        expect(Object.isFrozen(entry.redirect)).toBe(true);
      }
      if (entry.navigation) {
        expect(Object.isFrozen(entry.navigation)).toBe(true);
      }
      if (entry.shellNavigation) {
        expect(Object.isFrozen(entry.shellNavigation)).toBe(true);
      }
    }
  });

  it('preserves the existing primary, secondary, and staff navigation metadata', () => {
    expect(
      ROUTE_MANIFEST.filter((entry) => entry.navigation).map((entry) => ({
        id: entry.id,
        ...entry.navigation,
      })),
    ).toEqual([
      {
        id: ROUTE_IDS.MAP,
        group: 'primary',
        labelKey: 'navigation:primary.map',
        icon: 'map',
        order: 0,
      },
      {
        id: ROUTE_IDS.MY_SPOTS,
        group: 'primary',
        labelKey: 'navigation:primary.mySpots',
        icon: 'bookmark',
        order: 1,
      },
      {
        id: ROUTE_IDS.UPLOAD,
        group: 'primary',
        labelKey: 'navigation:primary.share',
        icon: 'add_location_alt',
        order: 2,
      },
      {
        id: ROUTE_IDS.PROFILE,
        group: 'primary',
        labelKey: 'navigation:primary.profile',
        icon: 'account_circle',
        order: 4,
      },
      {
        id: ROUTE_IDS.REPORTS,
        group: 'secondary',
        labelKey: 'navigation:secondary.reports',
        icon: 'flag',
        order: 0,
      },
      {
        id: ROUTE_IDS.NOTIFICATIONS,
        group: 'secondary',
        labelKey: 'navigation:secondary.notifications',
        icon: 'notifications',
        order: 2,
      },
      {
        id: ROUTE_IDS.GAMIFICATION,
        group: 'secondary',
        labelKey: 'navigation:secondary.impact',
        icon: 'military_tech',
        order: 1,
      },
      {
        id: ROUTE_IDS.LEADERBOARD,
        group: 'primary',
        labelKey: 'navigation:primary.leaderboard',
        icon: 'leaderboard',
        order: 3,
      },
      {
        id: ROUTE_IDS.ADMIN_MODERATION,
        group: 'staff',
        labelKey: 'navigation:staff.moderation',
        icon: 'gavel',
        order: 0,
      },
      {
        id: ROUTE_IDS.ADMIN_DASHBOARD,
        group: 'staff',
        labelKey: 'navigation:staff.admin',
        icon: 'admin_panel_settings',
        order: 1,
      },
    ]);
  });

  it('owns the complete administration shell navigation metadata', () => {
    expect(
      ROUTE_MANIFEST.filter((entry) => entry.shellNavigation).map(
        (entry) => ({
          id: entry.id,
          ...entry.shellNavigation,
        }),
      ),
    ).toEqual([
      {
        id: ROUTE_IDS.ADMIN_MODERATION,
        labelKey: 'admin:shell.nav.moderation',
        order: 3,
        end: false,
      },
      {
        id: ROUTE_IDS.ADMIN_DASHBOARD,
        labelKey: 'admin:shell.nav.dashboard',
        order: 0,
        end: true,
      },
      {
        id: ROUTE_IDS.ADMIN_USERS,
        labelKey: 'admin:shell.nav.users',
        order: 1,
        end: false,
      },
      {
        id: ROUTE_IDS.ADMIN_SECURITY,
        labelKey: 'admin:shell.nav.security',
        order: 2,
        end: false,
      },
      {
        id: ROUTE_IDS.ADMIN_ANALYTICS,
        labelKey: 'admin:shell.nav.analytics',
        order: 4,
        end: false,
      },
      {
        id: ROUTE_IDS.ADMIN_AUDIT,
        labelKey: 'admin:shell.nav.audit',
        order: 5,
        end: false,
      },
      {
        id: ROUTE_IDS.ADMIN_SYSTEM,
        labelKey: 'admin:shell.nav.system',
        order: 6,
        end: false,
      },
    ]);
  });

  it('owns document titles for every renderable route', () => {
    const addressableWithoutOwnTitle = ROUTE_MANIFEST.filter(
      (entry) =>
        (entry.kind === 'path' || entry.kind === 'index') &&
        entry.documentTitleKey === null,
    ).map((entry) => entry.id);

    expect(addressableWithoutOwnTitle).toEqual([ROUTE_IDS.ADMIN_SHELL]);
    expect(
      ROUTE_MANIFEST.find(
        (entry) =>
          entry.kind === 'index' &&
          entry.parentId === ROUTE_IDS.ADMIN_SHELL,
      )?.documentTitleKey,
    ).toBe('titles.adminDashboard');
    expect(
      ROUTE_MANIFEST.filter((entry) => entry.kind === 'pathless').every(
        (entry) => entry.documentTitleKey === null,
      ),
    ).toBe(true);

    const titleExpectations = [
      ['/login', 'titles.login'],
      ['/register', 'titles.register'],
      ['/forgot-password', 'titles.forgotPassword'],
      ['/reset-password', 'titles.resetPassword'],
      ['/check-email', 'titles.checkEmail'],
      ['/verify-email', 'titles.verifyEmail'],
      ['/terms', 'titles.terms'],
      ['/privacy', 'titles.privacy'],
      ['/', 'titles.home'],
      ['/preparing', 'titles.preparing'],
      ['/map', 'titles.map'],
      [`/spots/${validUuid}`, 'titles.spotDetails'],
      ['/spots/not-a-uuid', 'titles.spotDetails'],
      ['/my-spots', 'titles.mySpots'],
      ['/upload', 'titles.upload'],
      ['/profile', 'titles.profile'],
      ['/reports', 'titles.reports'],
      ['/notifications', 'titles.notifications'],
      ['/gamification', 'titles.gamification'],
      ['/leaderboard', 'titles.leaderboard'],
      ['/moderation', 'titles.moderation'],
      ['/admin/moderation', 'titles.moderation'],
      ['/analytics', 'titles.analytics'],
      ['/admin', 'titles.adminDashboard'],
      ['/admin/users', 'titles.adminUsers'],
      [`/admin/users/${validUuid}`, 'titles.adminUser'],
      ['/admin/users/not-a-uuid', 'titles.adminUser'],
      ['/admin/security', 'titles.adminSecurity'],
      ['/admin/analytics', 'titles.analytics'],
      ['/admin/audit', 'titles.adminAudit'],
      ['/admin/system', 'titles.adminSystem'],
      ['/does-not-exist', 'titles.notFound'],
    ] as const;

    for (const [path, titleKey] of titleExpectations) {
      expect(getRouteDocumentTitleKey(path)).toBe(titleKey);
    }
  });

  it('owns the exact navigation-interruption bypass set', () => {
    const bypassEntries = ROUTE_MANIFEST.filter(
      (entry) => entry.navigationInterruption === 'bypass',
    );

    expect(bypassEntries.map((entry) => entry.id)).toEqual([
      ROUTE_IDS.LOGIN,
      ROUTE_IDS.REGISTER,
      ROUTE_IDS.FORGOT_PASSWORD,
      ROUTE_IDS.RESET_PASSWORD,
      ROUTE_IDS.CHECK_EMAIL,
      ROUTE_IDS.VERIFY_EMAIL,
      ROUTE_IDS.PREPARING,
    ]);
    for (const path of [
      '/login',
      '/register',
      '/forgot-password',
      '/reset-password',
      '/check-email',
      '/verify-email',
      '/preparing',
    ]) {
      expect(isNavigationInterruptionBypassPath(path)).toBe(true);
    }
    for (const path of [
      '/',
      '/terms',
      '/privacy',
      '/map',
      '/upload',
      '/admin',
      '/unknown',
      '/login/',
      '/login?return=/map',
    ]) {
      expect(isNavigationInterruptionBypassPath(path)).toBe(false);
    }
  });
});

describe('route graph invariants', () => {
  it('accepts the canonical manifest without violations', () => {
    expect(validateRouteManifest(ROUTE_MANIFEST)).toEqual([]);
  });

  it('rejects duplicate route IDs', () => {
    const duplicate = {
      ...ROUTE_MANIFEST.find((entry) => entry.id === ROUTE_IDS.MAP)!,
    } as RouteManifestEntry;

    expect(
      violationCodes([...ROUTE_MANIFEST, duplicate]),
    ).toContain('duplicate-route-id');
  });

  it('rejects duplicate concrete paths even when parameter names differ', () => {
    const duplicateStaticPath = replaceRoute(ROUTE_IDS.MY_SPOTS, {
      path: '/map',
    } as Partial<RouteManifestEntry>);
    const duplicateParameterizedPath = replaceRoute(ROUTE_IDS.ADMIN_USER_DETAIL, {
      path: '/spots/:otherId',
      parameters: [{ name: 'otherId', validator: 'uuid' }],
    } as Partial<RouteManifestEntry>);

    expect(violationCodes(duplicateStaticPath)).toContain('duplicate-path');
    expect(violationCodes(duplicateParameterizedPath)).toContain(
      'duplicate-path',
    );
  });

  it('rejects missing parents and parent cycles', () => {
    const missingParent = replaceRoute(ROUTE_IDS.MAP, {
      parentId: 'routing.missing' as RouteId,
    });
    const parentCycle = ROUTE_MANIFEST.map((entry) => {
      if (entry.id === ROUTE_IDS.MAP) {
        return {
          ...entry,
          parentId: ROUTE_IDS.MY_SPOTS,
        } as RouteManifestEntry;
      }
      if (entry.id === ROUTE_IDS.MY_SPOTS) {
        return {
          ...entry,
          parentId: ROUTE_IDS.MAP,
        } as RouteManifestEntry;
      }
      return entry;
    });

    expect(violationCodes(missingParent)).toContain('missing-parent');
    expect(violationCodes(parentCycle)).toContain('parent-cycle');
  });

  it.each([
    {
      name: 'path parameters without matching metadata',
      manifest: replaceRoute(ROUTE_IDS.SPOT_DETAIL, { parameters: [] }),
      code: 'invalid-parameter-metadata',
    },
    {
      name: 'a redirect to an unknown route',
      manifest: replaceRoute(ROUTE_IDS.MODERATION_ALIAS, {
        redirect: {
          targetId: 'routing.missing' as RouteId,
          replace: true,
        },
      }),
      code: 'missing-redirect-target',
    },
    {
      name: 'a public route with protected bootstrap metadata',
      manifest: replaceRoute(ROUTE_IDS.LOGIN, {
        bootstrap: 'protected-await',
      }),
      code: 'invalid-public-policy',
    },
    {
      name: 'a lazy route without a fallback',
      manifest: replaceRoute(ROUTE_IDS.MAP, { fallback: 'none' }),
      code: 'invalid-loading-policy',
    },
    {
      name: 'a second catch-all route',
      manifest: replaceRoute(ROUTE_IDS.PRIVACY, { path: '*' }),
      code: 'multiple-catch-all',
    },
    {
      name: 'parameterized shell navigation',
      manifest: replaceRoute(ROUTE_IDS.ADMIN_USER_DETAIL, {
        shellNavigation: {
          labelKey: 'admin:shell.nav.users',
          order: 7,
          end: false,
        },
      }),
      code: 'invalid-shell-navigation-policy',
    },
    {
      name: 'duplicate shell navigation order',
      manifest: replaceRoute(ROUTE_IDS.ADMIN_SYSTEM, {
        shellNavigation: {
          labelKey: 'admin:shell.nav.system',
          order: 5,
          end: false,
        },
      }),
      code: 'duplicate-shell-navigation-order',
    },
    {
      name: 'an addressable page without a document title',
      manifest: replaceRoute(ROUTE_IDS.MAP, {
        documentTitleKey: null,
      }),
      code: 'invalid-document-title-policy',
    },
    {
      name: 'document-title metadata on a pathless owner',
      manifest: replaceRoute(ROUTE_IDS.ROOT, {
        documentTitleKey: 'titles.home',
      }),
      code: 'invalid-document-title-policy',
    },
    {
      name: 'competing title metadata on an index-route parent',
      manifest: replaceRoute(ROUTE_IDS.ADMIN_SHELL, {
        documentTitleKey: 'titles.adminDashboard',
      }),
      code: 'invalid-document-title-policy',
    },
    {
      name: 'parameterized navigation-interruption bypass',
      manifest: replaceRoute(ROUTE_IDS.SPOT_DETAIL, {
        navigationInterruption: 'bypass',
      }),
      code: 'invalid-navigation-interruption-policy',
    },
  ])('rejects $name', ({ manifest, code }) => {
    expect(violationCodes(manifest)).toContain(code);
  });
});

describe('route parameters and builders', () => {
  it('validates UUID route parameters deterministically', () => {
    expect(isValidRouteParameter('uuid', validUuid)).toBe(true);
    expect(
      isValidRouteParameter(
        'uuid',
        '6F9619FF-8B86-4D01-B42D-00CF4FC964FF',
      ),
    ).toBe(true);
    expect(isValidRouteParameter('uuid', 'not-a-uuid')).toBe(false);
    expect(isValidRouteParameter('uuid', `${validUuid}/extra`)).toBe(false);
  });

  it('builds static and UUID-parameterized routes from manifest metadata', () => {
    expect(buildRoutePath(ROUTE_IDS.MAP)).toBe('/map');
    expect(buildRoutePath(ROUTE_IDS.SPOT_DETAIL, { spotId: validUuid })).toBe(
      `/spots/${validUuid}`,
    );
    expect(buildRoutePath(ROUTE_IDS.ADMIN_USER_DETAIL, { id: validUuid })).toBe(
      `/admin/users/${validUuid}`,
    );
    expect(buildRoutePath(ROUTE_IDS.ADMIN_DASHBOARD)).toBe('/admin');
  });

  it('rejects missing, invalid, and undeclared builder parameters', () => {
    expect(() => buildRoutePath(ROUTE_IDS.SPOT_DETAIL)).toThrow(
      "requires a valid uuid parameter 'spotId'",
    );
    expect(() =>
      buildRoutePath(ROUTE_IDS.SPOT_DETAIL, { spotId: 'not-a-uuid' }),
    ).toThrow("requires a valid uuid parameter 'spotId'");
    expect(() =>
      buildRoutePath(ROUTE_IDS.MAP, { unexpected: validUuid }),
    ).toThrow("does not declare parameter 'unexpected'");
  });
});

describe('derived route classifications', () => {
  it('classifies every frozen public entry as public', () => {
    expect(getPublicRouteIds()).toEqual([
      ROUTE_IDS.LOGIN,
      ROUTE_IDS.REGISTER,
      ROUTE_IDS.FORGOT_PASSWORD,
      ROUTE_IDS.RESET_PASSWORD,
      ROUTE_IDS.CHECK_EMAIL,
      ROUTE_IDS.VERIFY_EMAIL,
      ROUTE_IDS.TERMS,
      ROUTE_IDS.PRIVACY,
      ROUTE_IDS.NOT_FOUND,
    ]);

    for (const path of [
      '/login',
      '/register',
      '/forgot-password',
      '/reset-password',
      '/check-email',
      '/verify-email',
      '/terms',
      '/privacy',
      '/unknown',
    ]) {
      expect(classifyRoutePath(path)).toBe('public');
    }
  });

  it('classifies protected, index, alias, and UUID routes as protected', () => {
    expect(getProtectedRouteIds()).toContain(ROUTE_IDS.AUTHENTICATED_ENTRY);
    expect(getProtectedRouteIds()).toContain(ROUTE_IDS.ADMIN_DASHBOARD);
    expect(getProtectedRouteIds()).toContain(ROUTE_IDS.MODERATION_ALIAS);

    for (const path of [
      '/',
      '/map',
      '/preparing',
      `/spots/${validUuid}`,
      '/moderation',
      '/admin',
      `/admin/users/${validUuid}`,
    ]) {
      expect(classifyRoutePath(path)).toBe('protected');
    }
    expect(classifyRoutePath('https://evil.example/map')).toBe('unrecognized');
    expect(classifyRoutePath('//evil.example/map')).toBe('unrecognized');
  });

  it('classifies malformed UUID paths by protected route family without making them redirect eligible', () => {
    expect(findRouteManifestEntryByPath('/spots/not-a-uuid')?.id).toBe(
      ROUTE_IDS.SPOT_DETAIL,
    );
    expect(classifyRoutePath('/spots/not-a-uuid')).toBe('protected');
    expect(
      findRouteManifestEntryByPath('/admin/users/not-a-uuid')?.id,
    ).toBe(ROUTE_IDS.ADMIN_USER_DETAIL);
    expect(classifyRoutePath('/admin/users/not-a-uuid')).toBe('protected');

    expect(isRedirectEligiblePath('/spots/not-a-uuid')).toBe(false);
    expect(isRedirectEligiblePath('/admin/users/not-a-uuid')).toBe(false);

    expect(classifyRoutePath('/login')).toBe('public');
    expect(classifyRoutePath('/unknown')).toBe('public');
    expect(classifyRoutePath(`/spots/${validUuid}`)).toBe('protected');
    expect(isRedirectEligiblePath(`/spots/${validUuid}`)).toBe(true);
    expect(classifyRoutePath(`/admin/users/${validUuid}`)).toBe('protected');
    expect(isRedirectEligiblePath(`/admin/users/${validUuid}`)).toBe(true);
  });

  it('resolves the admin index and not-found entries deterministically', () => {
    expect(findRouteManifestEntryByPath('/admin')?.id).toBe(
      ROUTE_IDS.ADMIN_DASHBOARD,
    );
    expect(findRouteManifestEntryByPath('/does-not-exist')?.id).toBe(
      ROUTE_IDS.NOT_FOUND,
    );
  });

  it('owns the existing redirect-eligible protected route set', () => {
    expect(getRedirectEligibleRouteIds()).toEqual([
      ROUTE_IDS.AUTHENTICATED_ENTRY,
      ROUTE_IDS.PREPARING,
      ROUTE_IDS.MAP,
      ROUTE_IDS.SPOT_DETAIL,
      ROUTE_IDS.MY_SPOTS,
      ROUTE_IDS.UPLOAD,
      ROUTE_IDS.PROFILE,
      ROUTE_IDS.REPORTS,
      ROUTE_IDS.NOTIFICATIONS,
      ROUTE_IDS.GAMIFICATION,
      ROUTE_IDS.LEADERBOARD,
      ROUTE_IDS.MODERATION_ALIAS,
      ROUTE_IDS.ADMIN_MODERATION,
      ROUTE_IDS.ANALYTICS_ALIAS,
      ROUTE_IDS.ADMIN_DASHBOARD,
      ROUTE_IDS.ADMIN_USERS,
      ROUTE_IDS.ADMIN_USER_DETAIL,
      ROUTE_IDS.ADMIN_SECURITY,
      ROUTE_IDS.ADMIN_ANALYTICS,
      ROUTE_IDS.ADMIN_AUDIT,
      ROUTE_IDS.ADMIN_SYSTEM,
    ]);

    expect(isRedirectEligiblePath('/profile?tab=account#security')).toBe(true);
    expect(isRedirectEligiblePath(`/spots/${validUuid}`)).toBe(true);
    expect(isRedirectEligiblePath('/spots/not-a-uuid')).toBe(false);
    expect(isRedirectEligiblePath('/login')).toBe(false);
    expect(isRedirectEligiblePath('/unknown')).toBe(false);
    expect(isRedirectEligiblePath('https://evil.example/map')).toBe(false);
  });
});

describe('manifest snapshot', () => {
  it('freezes the complete graph shape and metadata fingerprint', () => {
    const projection = {
      total: ROUTE_MANIFEST.length,
      fingerprint: manifestFingerprint(JSON.stringify(ROUTE_MANIFEST)),
      graph: ROUTE_MANIFEST.map((entry) => {
        const location =
          entry.kind === 'path'
            ? entry.path
            : entry.kind === 'index'
              ? 'index'
              : 'pathless';
        return `${entry.id}|${entry.parentId ?? 'root'}|${location}`;
      }),
    };

    expect(projection).toMatchInlineSnapshot(`
      {
        "fingerprint": "ec6a749f",
        "graph": [
          "routing.root|root|pathless",
          "auth.login|routing.root|/login",
          "auth.register|routing.root|/register",
          "auth.forgot-password|routing.root|/forgot-password",
          "auth.reset-password|routing.root|/reset-password",
          "auth.check-email|routing.root|/check-email",
          "auth.verify-email|routing.root|/verify-email",
          "legal.terms|routing.root|/terms",
          "legal.privacy|routing.root|/privacy",
          "routing.protected|routing.root|pathless",
          "app.entry|routing.protected|/",
          "account.preparing|routing.protected|/preparing",
          "account.not-active|routing.protected|pathless",
          "shell.application|routing.protected|pathless",
          "app.map|shell.application|/map",
          "app.spot-detail|shell.application|/spots/:spotId",
          "app.my-spots|shell.application|/my-spots",
          "app.upload|shell.application|/upload",
          "app.profile|shell.application|/profile",
          "app.reports|shell.application|/reports",
          "app.notifications|shell.application|/notifications",
          "app.gamification|shell.application|/gamification",
          "app.leaderboard|shell.application|/leaderboard",
          "routing.privileged|shell.application|pathless",
          "staff.moderation-alias|routing.privileged|/moderation",
          "staff.moderation|routing.privileged|/admin/moderation",
          "routing.admin|shell.application|pathless",
          "admin.analytics-alias|routing.admin|/analytics",
          "shell.admin|routing.admin|/admin",
          "admin.dashboard|shell.admin|index",
          "admin.users|shell.admin|users",
          "admin.user-detail|shell.admin|users/:id",
          "admin.security|shell.admin|security",
          "admin.analytics|shell.admin|analytics",
          "admin.audit|shell.admin|audit",
          "admin.system|shell.admin|system",
          "routing.not-found|routing.root|*",
        ],
        "total": 37,
      }
    `);
  });
});
