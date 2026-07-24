import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  extractModuleSpecifiers,
  extractPublicExports,
  findCredentialPersistenceViolations,
  findCrossTabSecurityViolations,
  findDependencyBoundaryViolations,
  findDirectHttpViolations,
  findPackageManifestBoundaryViolations,
  findWebRoutingOwnershipViolations,
  findWebRuntimeOwnershipViolations,
  isBackendProtectedPath,
} from './guardrail-lib.mjs';
import { findWp04DataArchitectureViolations } from './wp04-data-architecture.mjs';
import { findWp05CoreParkingViolations } from './wp05-core-parking-flows.mjs';

test('direct HTTP detection rejects HTTP packages and network primitives', () => {
  const source = `
    import axios from 'axios';
    export async function load() {
      const first = await fetch('/api/v1/spots');
      const second = new XMLHttpRequest();
      return { first, second, axios };
    }
  `;

  assert.deepEqual(
    findDirectHttpViolations(source).map((violation) => violation.rule),
    ['no-direct-http-package', 'no-direct-http-call', 'no-direct-http-call'],
  );
});

test('direct HTTP detection ignores comments and string literals', () => {
  const source = `
    // fetch('/not-executable')
    // import axios from 'axios';
    const documentation = "new XMLHttpRequest(), fetch('/still-not-executable'), and require('ky')";
    const callableText = "fetch"();
  `;

  assert.deepEqual(findDirectHttpViolations(source), []);
});

test('direct HTTP detection covers optional calls and template expressions', () => {
  const source = [
    "globalThis.fetch?.('/api/v1/spots');",
    'const result = `response: ${await fetch("/api/v1/parking/sessions")}`;',
  ].join('\n');

  assert.equal(
    findDirectHttpViolations(source).filter((violation) => violation.rule === 'no-direct-http-call')
      .length,
    2,
  );
});

test('credential persistence detection rejects sensitive browser-storage writes', () => {
  const source = `
    window.localStorage.setItem('parkio.access_token', accessToken);
    sessionStorage
      .setItem("refresh-token", refreshToken);
    indexedDB.open('auth-credentials');
    caches.open('session-token');
  `;

  assert.equal(findCredentialPersistenceViolations(source).length, 4);
});

test('credential persistence detection permits unrelated storage and token removal', () => {
  const source = `
    localStorage.setItem('parkio.locale', locale);
    sessionStorage.setItem('chunk-reload', '1');
    localStorage.removeItem('parkio.accessToken');
    const accessToken = memoryAdapter.getAccessToken();
  `;

  assert.deepEqual(findCredentialPersistenceViolations(source), []);
});

test('credential persistence detection follows aliases, computed access, destructuring, and wrappers', () => {
  const source = `
    const browserStore = window['localStorage'];
    const renamedWrite = browserStore['setItem'].bind(browserStore);
    renamedWrite('opaque-key', accessToken);

    const { setItem: persistSession } = sessionStorage;
    persistSession('opaque-key', refreshToken);

    function persist(value) {
      const selectedStore = window.localStorage;
      selectedStore.setItem('opaque-key', value);
    }
    persist(accessToken);

    const cacheLayer = globalThis['caches'];
    const openCache = cacheLayer['open'].bind(cacheLayer);
    openCache(sessionToken);
  `;

  assert.equal(findCredentialPersistenceViolations(source).length, 4);
});

test('credential persistence detection permits aliased non-authentication storage writes', () => {
  const source = `
    const browserStore = window.localStorage;
    const renamedWrite = browserStore.setItem.bind(browserStore);
    renamedWrite('parkio.locale', locale);

    function persistPreference(value) {
      browserStore.setItem('parkio.theme', value);
    }
    persistPreference(theme);
  `;

  assert.deepEqual(findCredentialPersistenceViolations(source), []);
});

test('cross-tab guard rejects duplicate owners and sensitive payload fields', () => {
  assert.equal(
    findCrossTabSecurityViolations(
      "const channel = new BroadcastChannel('auth');",
      'apps/web/src/features/profile/session.ts',
    )[0]?.rule,
    'single-cross-tab-session-owner',
  );

  const unsafeOwner = `
    channel.postMessage({
      version: 1,
      type: 'session-destroyed',
      eventId,
      access_token: accessToken,
      user: currentUser,
    });
  `;
  assert.deepEqual(
    findCrossTabSecurityViolations(
      unsafeOwner,
      'apps/web/src/auth/crossTabSync.ts',
    ).map(({ rule }) => rule),
    ['credential-free-cross-tab-message'],
  );
});

test('cross-tab guard requires inspectable envelopes and permits the frozen safe shape', () => {
  assert.equal(
    findCrossTabSecurityViolations(
      'channel.postMessage(message);',
      'apps/web/src/auth/crossTabSync.ts',
    )[0]?.rule,
    'inline-cross-tab-session-envelope',
  );
  assert.deepEqual(
    findCrossTabSecurityViolations(
      `
        const channel = new BroadcastChannel('parkio.auth');
        channel.postMessage({ version: 1, type: 'session-destroyed', eventId });
      `,
      'apps/web/src/auth/crossTabSync.ts',
    ),
    [],
  );
});

test('cross-tab guard inspects computed sends, indirection, and object spreads', () => {
  const spreadPayload = `
    const privateFields = {
      accessToken,
      credentials,
      user,
      roles,
      email,
      identity,
      coordinates,
      pii,
      backendPayload,
    };
    channel['postMessage']({
      version: 1,
      type: 'session-destroyed',
      eventId,
      ...privateFields,
    });
  `;
  assert.deepEqual(
    findCrossTabSecurityViolations(
      spreadPayload,
      'apps/web/src/auth/crossTabSync.ts',
    ).map(({ rule }) => rule),
    ['credential-free-cross-tab-message'],
  );

  const indirectPayload = `
    const send = channel.postMessage.bind(channel);
    const privateFields = { email, backendPayload };
    send({ version: 1, type: 'session-destroyed', eventId, ...privateFields });
  `;
  assert.deepEqual(
    findCrossTabSecurityViolations(
      indirectPayload,
      'apps/web/src/auth/crossTabSync.ts',
    ).map(({ rule }) => rule),
    ['credential-free-cross-tab-message'],
  );

  const aliasedOwner = `
    const Channel = globalThis['BroadcastChannel'];
    const duplicate = new Channel('parkio.auth');
  `;
  assert.deepEqual(
    findCrossTabSecurityViolations(
      aliasedOwner,
      'apps/web/src/features/profile/session.ts',
    ).map(({ rule }) => rule),
    ['single-cross-tab-session-owner'],
  );
});

test('cross-tab guard permits a statically resolved safe spread envelope', () => {
  const source = `
    const safeEnvelope = { version: 1, type: 'session-destroyed' };
    channel.postMessage({ ...safeEnvelope, eventId });
  `;

  assert.deepEqual(
    findCrossTabSecurityViolations(source, 'apps/web/src/auth/crossTabSync.ts'),
    [],
  );
});

test('application boundary permits the package entrypoint and rejects deep imports', () => {
  const publicSource = "import { createApiClient } from '@parkio/api-client';";
  const deepSource = "import { secret } from '@parkio/api-client/src/client';";

  assert.deepEqual(findDependencyBoundaryViolations(publicSource, 'application'), []);
  assert.equal(findDependencyBoundaryViolations(deepSource, 'application').length, 1);
});

test('api-client boundary rejects UI, app, and Node dependencies', () => {
  const source = `
    import React from 'react';
    import { queryClient } from '@/query/client';
    import path from 'node:path';
    import SecureStore from 'expo-secure-store';
  `;

  assert.equal(findDependencyBoundaryViolations(source, 'api-client').length, 4);
});

test('api-client boundary rejects bare Node and native runtime dependencies', () => {
  const source = `
    import dns from 'dns';
    import module from 'module';
    import readline from 'readline';
    import timers from 'timers';
    import NetInfo from '@react-native-community/netinfo';
    import { Capacitor } from '@capacitor/core';
  `;

  assert.equal(findDependencyBoundaryViolations(source, 'api-client').length, 6);
});

test('lower-level shared packages cannot depend on api-client', () => {
  const source = "export type { ApiClientOptions } from '@parkio/api-client';";

  assert.equal(findDependencyBoundaryViolations(source, 'types').length, 1);
  assert.equal(findDependencyBoundaryViolations(source, 'validation').length, 1);
});

test('runtime package dependencies obey the same dependency direction', () => {
  const packageJson = {
    dependencies: { '@parkio/types': 'workspace:*' },
    peerDependencies: { react: '^19.0.0' },
  };

  assert.equal(findPackageManifestBoundaryViolations(packageJson, 'api-client').length, 1);
});

test('application manifests reject raw HTTP dependencies', () => {
  const packageJson = {
    dependencies: { '@parkio/api-client': 'workspace:*', axios: '^1.0.0' },
  };

  assert.deepEqual(
    findPackageManifestBoundaryViolations(packageJson, 'application').map(({ rule }) => rule),
    ['runtime-dependency-boundary'],
  );
});

test('Web runtime ownership rejects singleton clients and duplicate Query Clients', () => {
  const featureSource = `
    import { parkingApi } from '@/api';
    import { createApiClient } from '@parkio/api-client';
    import { createParkioSdk } from '@/app/sdk';
    import { createWebQueryClient } from '@/providers/query-client';
    import { create } from 'zustand';
    const authStore = create(() => ({ accessToken: null }));
    const queryClient = new QueryClient();
  `;

  assert.deepEqual(
    findWebRuntimeOwnershipViolations(featureSource, 'apps/web/src/features/parking/data.ts').map(
      ({ rule }) => rule,
    ),
    [
      'web-sdk-dependency-injection',
      'single-sdk-composition-owner',
      'application-scoped-auth-ownership',
      'single-web-composition-owner',
      'single-web-composition-owner',
      'single-query-client-composition-owner',
    ],
  );
});

test('Web runtime ownership rejects aliased and namespace Query Client construction', () => {
  const source = `
    import { QueryClient as Client } from '@tanstack/react-query';
    import * as ReactQuery from '@tanstack/react-query';
    const first = new Client();
    const second = new ReactQuery.QueryClient();
  `;

  assert.deepEqual(
    findWebRuntimeOwnershipViolations(source, 'apps/web/src/features/parking/data.ts').map(
      ({ rule }) => rule,
    ),
    ['single-query-client-composition-owner', 'single-query-client-composition-owner'],
  );
});

test('Web runtime ownership permits the frozen composition owners and test clients', () => {
  assert.deepEqual(
    findWebRuntimeOwnershipViolations(
      "import { createApiClient, createParkingApi } from '@parkio/api-client';",
      'apps/web/src/app/sdk.ts',
    ),
    [],
  );
  assert.deepEqual(
    findWebRuntimeOwnershipViolations(
      'const queryClient = new QueryClient(); createWebAppRuntime(options);',
      'apps/web/src/test/utils.tsx',
    ),
    [],
  );
  assert.deepEqual(
    findWebRuntimeOwnershipViolations(
      'export function createAuthStore() { return createStore(() => ({})); }',
      'apps/web/src/auth/auth-store.ts',
    ),
    [],
  );
});

test('Web runtime ownership keeps refresh lifecycle in its frozen integration owners', () => {
  const featureSource = `
    import { refreshSession, setRefreshHandler } from '@parkio/api-client';
    authApi.refresh();
  `;

  assert.deepEqual(
    findWebRuntimeOwnershipViolations(featureSource, 'apps/web/src/features/profile/data.ts').map(
      ({ rule }) => rule,
    ),
    [
      'sdk-owned-refresh-lifecycle',
      'sdk-owned-refresh-lifecycle',
      'no-web-refresh-execution',
    ],
  );
  assert.deepEqual(
    findWebRuntimeOwnershipViolations(
      "import { refreshSession } from '@parkio/api-client'; refreshSession(); authApi.refresh();",
      'apps/web/src/auth/session.ts',
    ),
    [],
  );
});

test('Web refresh ownership detects aliases, destructuring, computed access, namespace access, and direct paths', () => {
  const featureSource = `
    import * as apiClient from '@parkio/api-client';
    const indirectRefresh = authApi['refresh'];
    indirectRefresh();
    const { refresh: renamedRefresh } = sdk.authApi;
    renamedRefresh();
    apiClient['refreshSession']();
    const refreshPath = '/auth/refresh-token';
    http.post(refreshPath);
    while (shouldRetry) {
      sdk.authApi.refresh();
    }
  `;

  const rules = findWebRuntimeOwnershipViolations(
    featureSource,
    'apps/web/src/features/profile/data.ts',
  ).map(({ rule }) => rule);

  assert.equal(rules.filter((rule) => rule === 'no-web-refresh-execution').length, 4);
  assert.equal(rules.filter((rule) => rule === 'sdk-owned-refresh-lifecycle').length, 1);
});

test('module-global auth ownership detects renamed and namespace-created stores', () => {
  const aliasedFactory = `
    import { createStore as buildContainer } from 'zustand/vanilla';
    export const runtimeState = buildContainer(() => ({
      accessToken: null,
      user: null,
      roles: [],
    }));
  `;
  const namespaceFactory = `
    import * as zustand from 'zustand/vanilla';
    const stateContainer = zustand['createStore'](() => ({
      identity: null,
      sessionEpoch: 0,
    }));
  `;
  const mutableContainer = `
    export const stateContainer = {
      accessToken: null,
      user: null,
    };
  `;

  for (const source of [aliasedFactory, namespaceFactory, mutableContainer]) {
    assert.deepEqual(
      findWebRuntimeOwnershipViolations(
        source,
        'apps/web/src/features/profile/session-state.ts',
      ).map(({ rule }) => rule),
      ['application-scoped-auth-ownership'],
    );
  }
});

test('module-global ownership permits unrelated stores and approved runtime composition', () => {
  const localeStore = `
    import { create as createState } from 'zustand';
    type LocaleState = { locale: string; setLocale(locale: string): void };
    const userStatusLabels = { user: 'Driver', status: 'Status' };
    export const useLocaleState = createState(() => ({
      locale: 'en',
      setLocale: () => undefined,
    }));
  `;
  assert.deepEqual(
    findWebRuntimeOwnershipViolations(localeStore, 'apps/web/src/i18n/localeStore.ts'),
    [],
  );
  assert.deepEqual(
    findWebRuntimeOwnershipViolations(
      "import { setRefreshHandler } from '@parkio/api-client'; setRefreshHandler(handler);",
      'apps/web/src/app/runtime.ts',
    ),
    [],
  );
});

test('routing ownership rejects retired router and guard imports', () => {
  const source = `
    import { routes } from '@/router';
    import { ProtectedRoute } from '@/auth/ProtectedRoute';
    import { RoleRoute } from '@/auth/RoleRoute';
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/routes.tsx',
    ).map(({ rule }) => rule),
    [
      'no-legacy-routing-owner',
      'no-legacy-routing-owner',
      'no-legacy-routing-owner',
    ],
  );
});

test('routing ownership detects direct, aliased, namespace, destructured, and computed router factories', () => {
  const source = `
    import { createBrowserRouter as buildBrowser } from 'react-router-dom';
    import * as Router from 'react-router-dom';
    const { createMemoryRouter: buildMemory } = Router;
    const computedFactory = Router['createBrowserRouter'];
    buildBrowser([]);
    Router.createMemoryRouter([]);
    buildMemory([]);
    computedFactory([]);
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/router.tsx',
    ).map(({ rule }) => rule),
    [
      'single-router-factory-owner',
      'single-router-factory-owner',
      'single-router-factory-owner',
      'single-router-factory-owner',
    ],
  );
});

test('routing ownership resolves constant-computed router factory access', () => {
  const source = `
    import * as Router from 'react-router-dom';
    const browserFactoryName = 'createBrowserRouter';
    const selectedFactoryName = browserFactoryName;
    Router[selectedFactoryName]([]);
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/runtime.tsx',
    ).map(({ rule }) => rule),
    ['single-router-factory-owner'],
  );
});

test('routing ownership rejects production route objects, JSX graphs, and useRoutes', () => {
  const source = `
    import { Route as Page, Routes as Pages, useRoutes as compileLocally } from 'react-router-dom';
    const duplicateGraph = [{ id: 'map', path: '/map', element: <MapPage /> }];
    export function FeatureRoutes() {
      compileLocally(duplicateGraph);
      return <Pages><Page path="/map" element={<MapPage />} /></Pages>;
    }
  `;

  const rules = findWebRoutingOwnershipViolations(
    source,
    'apps/web/src/features/parking/FeatureRoutes.tsx',
  ).map(({ rule }) => rule);

  assert.equal(
    rules.filter((rule) => rule === 'single-route-graph-owner').length,
    4,
  );
});

test('routing ownership rejects aliased, namespace, and computed RouterProvider owners', () => {
  const source = `
    import { RouterProvider as Provider } from 'react-router-dom';
    import * as Router from 'react-router-dom';
    const ComputedProvider = Router['RouterProvider'];
    export function FeatureRoot({ router }) {
      return (
        <>
          <Provider router={router} />
          <Router.RouterProvider router={router} />
          <ComputedProvider router={router} />
        </>
      );
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/FeatureRoot.tsx',
    ).map(({ rule }) => rule),
    [
      'single-router-provider-owner',
      'single-router-provider-owner',
      'single-router-provider-owner',
    ],
  );
});

test('routing ownership rejects renamed authentication-aware route wrappers', () => {
  const source = `
    import { useAuthStore as useSessionState } from '@/auth/store';
    import { Navigate as Redirect, Outlet as Slot } from 'react-router-dom';
    export function SessionGate() {
      const authenticated = useSessionState((state) => state.isAuthenticated);
      return authenticated ? <Slot /> : <Redirect to="/login" replace />;
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/profile/SessionGate.tsx',
    ).map(({ rule }) => rule),
    ['single-route-policy-owner'],
  );
});

test('routing ownership rejects imperative authentication-aware wrappers structurally', () => {
  const source = `
    import * as SessionState from '@/state/runtimeSession';
    import {
      Outlet as RoutedContent,
      useNavigate as useRouteTransition,
    } from 'react-router-dom';

    export function CompletelyRenamedBoundary() {
      const session = SessionState.observe((state) => state);
      const transition = useRouteTransition();
      useEffect(() => {
        if (!session.isAuthenticated) {
          transition('/login', { replace: true });
        }
      }, [session.isAuthenticated, transition]);
      return <RoutedContent />;
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/profile/CompletelyRenamedBoundary.tsx',
    ).map(({ rule }) => rule),
    ['single-route-policy-owner'],
  );
});

test('routing ownership rejects parallel literal redirect and navigation registries', () => {
  const navigation = `
    const destinations = [
      { destination: '/map', label: 'Map', icon: 'map', order: 0 },
      { destination: '/profile', label: 'Profile', icon: 'person', order: 1 },
    ];
  `;
  const redirects = `
    const compatibilityRedirects = {
      '/moderation': '/admin/moderation',
      '/analytics': '/admin/analytics',
    };
  `;

  for (const source of [navigation, redirects]) {
    assert.deepEqual(
      findWebRoutingOwnershipViolations(
        source,
        'apps/web/src/features/parking/navigation.ts',
      ).map(({ rule }) => rule),
      ['manifest-owned-navigation'],
    );
  }
});

test('routing ownership rejects function-local returned navigation registries', () => {
  const directReturn = `
    export function provideDestinations() {
      return [{ to: '/map', label: 'Map' }];
    }
  `;
  const returnedBinding = `
    export function provideDestinations() {
      const collection = [{ to: '/map', label: 'Map' }];
      return collection;
    }
  `;

  for (const source of [directReturn, returnedBinding]) {
    assert.deepEqual(
      findWebRoutingOwnershipViolations(
        source,
        'apps/web/src/features/parking/destinations.ts',
      ).map(({ rule }) => rule),
      ['manifest-owned-navigation'],
    );
  }
});

test('routing ownership rejects default-exported navigation registries', () => {
  const source = `
    export default [{ to: '/map', label: 'Map' }];
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/destinations.ts',
    ).map(({ rule }) => rule),
    ['manifest-owned-navigation'],
  );
});

test('routing ownership permits ordinary local UI link collections', () => {
  const source = `
    const helpLinks = [
      { to: '/privacy', label: 'Privacy' },
    ];

    export function HelpPage() {
      return (
        <ul>
          {helpLinks.map((item) => (
            <li key={item.to}><a href={item.to}>{item.label}</a></li>
          ))}
        </ul>
      );
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/pages/HelpPage.tsx',
    ),
    [],
  );
});

test('routing ownership rejects independent route title and accessibility metadata', () => {
  const fixtures = [
    `
      const routeTitles = [
        { pattern: '/map', titleKey: 'titles.map' },
        { pattern: '/profile', titleKey: 'titles.profile' },
      ];
    `,
    `
      export function accessibilityForRoutes() {
        return [
          { path: '/map', focusSelector: 'main h1' },
          { path: '/profile', focusSelector: '[data-route-focus]' },
        ];
      }
    `,
    `
      export default {
        '/map': 'titles.map',
        '/profile': 'titles.profile',
      };
    `,
    `
      const ROUTE_KEY = 'path';
      const TITLE_KEY = 'documentTitleKey';
      const MAP = '/map';
      const base = [{ [ROUTE_KEY]: MAP, [TITLE_KEY]: 'titles.map' }];
      const alias = base;
      export default [...alias, { path: '/profile', title: 'Profile' }];
    `,
    `
      const shared = {
        '/map': { accessibilityTitleKey: 'titles.map' },
      };
      export const routeAccessibility = {
        ...shared,
        '/profile': { focusTarget: 'main' },
      };
    `,
  ];

  for (const source of fixtures) {
    const rules = findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/route-copy.ts',
    ).map(({ rule }) => rule);
    assert.ok(rules.length > 0);
    assert.ok(
      rules.every((rule) => rule === 'manifest-owned-route-metadata'),
    );
  }
});

test('routing ownership rejects independent navigation-interruption policies', () => {
  const fixtures = [
    `
      const bypassDestinations = ['/login', '/register', '/preparing'];
      export function canLeave(pathname) {
        return bypassDestinations.includes(pathname);
      }
    `,
    `
      export default new Set([
        '/login',
        '/forgot-password',
        '/reset-password',
      ]);
    `,
    `
      const PATH = 'path';
      const POLICY = 'navigationInterruption';
      export const interruptionPolicy = [
        { [PATH]: '/login', [POLICY]: 'bypass' },
      ];
    `,
    `
      const publicDestinations = ['/login', '/register'];
      const lifecycleDestinations = ['/check-email', '/preparing'];
      const combined = [...publicDestinations, ...lifecycleDestinations];
      export const getBypasses = () => combined;
    `,
    `
      const LOGIN = '/login';
      const PREPARING = '/preparing';
      export function isBypass(destination) {
        return destination === LOGIN || destination === PREPARING;
      }
    `,
    `
      export default {
        '/login': true,
        '/profile': false,
      };
    `,
  ];

  for (const source of fixtures) {
    const rules = findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/upload/interruption.ts',
    ).map(({ rule }) => rule);
    assert.ok(rules.length > 0);
    assert.ok(
      rules.every((rule) => rule === 'manifest-owned-route-metadata'),
    );
  }
});

test('routing ownership permits non-authoritative metadata-shaped collections', () => {
  const fixtures = [
    {
      path: 'apps/web/src/pages/HelpPage.tsx',
      source: `
        const helpLinks = [
          { to: '/privacy', label: 'Privacy' },
          { to: '/terms', label: 'Terms' },
        ];
        export function HelpPage() {
          return helpLinks.map((link) => <a href={link.to}>{link.label}</a>);
        }
      `,
    },
    {
      path: 'apps/web/src/i18n/locales/en/common.ts',
      source: `
        export default {
          titles: {
            login: 'Parkio — Login',
            map: 'Parkio — Map',
          },
        };
      `,
    },
    {
      path: 'apps/web/src/pages/RegisterWizard.tsx',
      source: `
        const steps = [
          { id: 'account', title: 'Account' },
          { id: 'profile', title: 'Profile' },
        ];
        export function RegisterWizard() {
          return steps.map((step) => <section key={step.id}>{step.title}</section>);
        }
      `,
    },
    {
      path: 'apps/web/src/api/endpoints.ts',
      source: `
        export const endpoints = [
          { path: '/auth/login', method: 'POST' },
          { path: '/parking/spots', method: 'GET' },
        ];
      `,
    },
    {
      path: 'apps/web/src/routing/route-owner.test.ts',
      source: `
        export default [
          { path: '/fixture', titleKey: 'titles.fixture' },
        ];
      `,
    },
    {
      path: 'apps/web/src/components/RouteTitle.tsx',
      source: `
        import { getRouteDocumentTitleKey } from '@/routing/route-manifest';
        export function RouteTitle({ pathname }) {
          return translate(getRouteDocumentTitleKey(pathname));
        }
      `,
    },
  ];

  for (const fixture of fixtures) {
    assert.deepEqual(
      findWebRoutingOwnershipViolations(
        fixture.source,
        fixture.path,
      ),
      [],
      fixture.path,
    );
  }
});

test('routing ownership permits every frozen owner, derived consumers, feature links, and tests', () => {
  const approved = [
    {
      path: 'apps/web/src/routing/create-app-router.tsx',
      source: `
        import { createBrowserRouter, createMemoryRouter } from 'react-router-dom';
        export const createAppRouter = () => createBrowserRouter(compileAppRoutes());
        export const createMemoryAppRouter = () => createMemoryRouter(compileAppRoutes());
      `,
    },
    {
      path: 'apps/web/src/App.tsx',
      source: `
        import { RouterProvider } from 'react-router-dom';
        export const App = ({ runtime }) => <RouterProvider router={runtime.router} />;
      `,
    },
    {
      path: 'apps/web/src/routing/RoutePolicyBoundary.tsx',
      source: `
        import { useAuthStore } from '@/auth/store';
        import { Navigate, Outlet, useMatches } from 'react-router-dom';
        import { ROUTE_MANIFEST } from '@/routing/route-manifest';
        export const RoutePolicyBoundary = () => {
          const authenticated = useAuthStore((state) => state.isAuthenticated);
          const matches = useMatches();
          const policy = ROUTE_MANIFEST.find((entry) =>
            matches.some((match) => match.id === entry.id)
          );
          return authenticated && policy
            ? <Outlet />
            : <Navigate to="/login" replace />;
        };
      `,
    },
    {
      path: 'apps/web/src/routing/route-manifest.ts',
      source: `
        export const ROUTE_MANIFEST = [
          { id: 'map', path: '/map', navigation: { labelKey: 'map' } },
        ];
      `,
    },
    {
      path: 'apps/web/src/components/shell/navConfig.ts',
      source: `
        export const getNavigation = () =>
          ROUTE_MANIFEST.map((entry) => ({
            id: entry.id,
            to: getRoutePath(entry.id),
            label: translate(entry.navigation.labelKey),
          }));
      `,
    },
    {
      path: 'apps/web/src/pages/ProfilePage.tsx',
      source: `
        import { Link, useNavigate } from 'react-router-dom';
        export const ProfilePage = () => {
          const navigate = useNavigate();
          return <Link to="/map" onClick={() => navigate('/profile')}>Profile</Link>;
        };
      `,
    },
    {
      path: 'apps/web/src/features/parking/constants.ts',
      source: `
        export type RouteCopy = { path: string };
        export const emptyCopy = { path: '/map' };
      `,
    },
    {
      path: 'apps/web/src/features/parking/router.test.tsx',
      source: `
        import { createMemoryRouter, RouterProvider } from 'react-router-dom';
        const router = createMemoryRouter([]);
        export const TestView = () => <RouterProvider router={router} />;
      `,
    },
  ];

  for (const fixture of approved) {
    assert.deepEqual(
      findWebRoutingOwnershipViolations(fixture.source, fixture.path),
      [],
      fixture.path,
    );
  }
});

test('routing ownership rejects function-local literal route graphs', () => {
  const source = `
    export function buildFeatureGraph() {
      const graph = [{ path: '/map', element: null }];
      return graph;
    }
  `;

  assert.ok(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/parking/graph.ts',
    ).some((violation) => violation.rule === 'single-route-graph-owner'),
  );
});

test('routing ownership rejects direct and identifier default-exported route graphs', () => {
  const direct = `
    export default [{ path: '/map', element: null }];
  `;
  const viaIdentifier = `
    const routes = [{ path: '/map', element: null }];
    export default routes;
  `;

  for (const source of [direct, viaIdentifier]) {
    assert.ok(
      findWebRoutingOwnershipViolations(
        source,
        'apps/web/src/features/parking/graph.ts',
      ).some((violation) => violation.rule === 'single-route-graph-owner'),
    );
  }
});

test('routing ownership rejects Object.freeze-wrapped route graphs and policy Sets', () => {
  const frozenGraph = `
    const graph = Object.freeze([{ path: '/map', element: null }]);
    export { graph };
  `;
  const frozenPolicy = `
    const bypass = Object.freeze(new Set(['/login', '/register']));
    export function canLeave(pathname) {
      return bypass.has(pathname);
    }
  `;

  assert.ok(
    findWebRoutingOwnershipViolations(
      frozenGraph,
      'apps/web/src/features/parking/graph.ts',
    ).some((violation) => violation.rule === 'single-route-graph-owner'),
  );
  assert.ok(
    findWebRoutingOwnershipViolations(
      frozenPolicy,
      'apps/web/src/features/upload/interruption.ts',
    ).some((violation) => violation.rule === 'manifest-owned-route-metadata'),
  );
});

test('routing ownership rejects spread-composed route-policy collections', () => {
  const source = `
    const base = ['/login'];
    const bypass = new Set([...base, '/register', '/preparing']);
    export function canLeave(pathname) {
      return bypass.has(pathname);
    }
  `;

  assert.ok(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/upload/interruption.ts',
    ).some((violation) => violation.rule === 'manifest-owned-route-metadata'),
  );
});

test('routing ownership rejects switch(pathname) multi-path classifiers and aliased discriminants', () => {
  const direct = `
    export function classify(pathname) {
      switch (pathname) {
        case '/login':
          return 'auth';
        case '/register':
          return 'auth';
        default:
          return 'other';
      }
    }
  `;
  const aliased = `
    export function classify(location) {
      const path = location.pathname;
      switch (path) {
        case '/login':
          return 'auth';
        case '/register':
          return 'auth';
        default:
          return 'other';
      }
    }
  `;

  for (const source of [direct, aliased]) {
    assert.ok(
      findWebRoutingOwnershipViolations(
        source,
        'apps/web/src/features/routing/classify.ts',
      ).some((violation) => violation.rule === 'manifest-owned-route-metadata'),
    );
  }
});

test('routing ownership rejects a second manifest-aware authentication wrapper', () => {
  const source = `
    import { useAuthStore } from '@/auth/store';
    import { Navigate, Outlet, useMatches } from 'react-router-dom';
    import { ROUTE_MANIFEST } from '@/routing/route-manifest';
    export const AlternateGate = () => {
      const authenticated = useAuthStore((state) => state.isAuthenticated);
      const matches = useMatches();
      void matches;
      void ROUTE_MANIFEST;
      return authenticated ? <Outlet /> : <Navigate to="/login" replace />;
    };
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/routing/AlternateGate.tsx',
    ).map(({ rule }) => rule),
    ['single-route-policy-owner'],
  );
});

test('routing ownership rejects duplicate RouterProviders inside the approved App file', () => {
  const source = `
    import { RouterProvider } from 'react-router-dom';
    export const App = ({ primary, secondary }) => (
      <>
        <RouterProvider router={primary} />
        <RouterProvider router={secondary} />
      </>
    );
  `;

  assert.ok(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/App.tsx',
    ).some((violation) => violation.rule === 'single-router-provider-owner'),
  );
});

test('routing ownership rejects duplicate router factories inside an approved compiler file', () => {
  const source = `
    import { createBrowserRouter } from 'react-router-dom';
    import { ROUTES } from './route-manifest';
    export function createAppRouter() {
      return createBrowserRouter(ROUTES);
    }
    export function createShadowRouter() {
      return createBrowserRouter(ROUTES);
    }
  `;

  assert.ok(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/routing/create-app-router.tsx',
    ).some((violation) => violation.rule === 'single-router-factory-owner'),
  );
});

test('routing ownership rejects function-local title and navigation-interruption registries', () => {
  const titles = `
    export function titles() {
      const TITLE_BY_PATH = { '/login': 'Login', '/map': 'Map' };
      return TITLE_BY_PATH;
    }
  `;
  const interruption = `
    export function bypasses() {
      const BYPASS = new Set(['/login', '/register', '/preparing']);
      return (pathname) => BYPASS.has(pathname);
    }
  `;

  assert.ok(
    findWebRoutingOwnershipViolations(
      titles,
      'apps/web/src/features/routing/titles.ts',
    ).some((violation) => violation.rule === 'manifest-owned-route-metadata'),
  );
  assert.ok(
    findWebRoutingOwnershipViolations(
      interruption,
      'apps/web/src/features/upload/interruption.ts',
    ).some((violation) => violation.rule === 'manifest-owned-route-metadata'),
  );
});

test('routing ownership permits page-local non-auth wizards using hook state, Navigate, and Outlet', () => {
  const source = `
    import { Navigate, Outlet } from 'react-router-dom';
    import { useState } from 'react';
    export function RegisterWizard() {
      const [step, setStep] = useState(0);
      if (step === 1) {
        return <Navigate to="/register/profile" />;
      }
      return <Outlet />;
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/pages/RegisterWizard.tsx',
    ),
    [],
  );
});

test('routing ownership permits ordinary feature path comparisons without routing-policy ownership', () => {
  const source = `
    export function isMapSurface(pathname) {
      return pathname === '/map' || pathname.startsWith('/map/');
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/features/map/utils.ts',
    ),
    [],
  );
});

test('routing ownership permits page-local soft path+title step arrays that do not escape', () => {
  const source = `
    const steps = [
      { path: '/register/account', title: 'Account' },
      { path: '/register/profile', title: 'Profile' },
    ];
    export function RegisterWizard() {
      return steps.map((step) => <section key={step.path}>{step.title}</section>);
    }
  `;

  assert.deepEqual(
    findWebRoutingOwnershipViolations(
      source,
      'apps/web/src/pages/RegisterWizard.tsx',
    ),
    [],
  );
});

test('module parser recognizes static, dynamic, side-effect, and CommonJS imports', () => {
  const source = `
    import type { Thing } from '@parkio/types';
    import 'side-effect';
    const dynamic = import('dynamic-module');
    const legacy = require('legacy-module');
  `;

  assert.deepEqual(
    extractModuleSpecifiers(source).map(({ specifier }) => specifier).sort(),
    ['@parkio/types', 'dynamic-module', 'legacy-module', 'side-effect'],
  );
});

test('public export inventory preserves value, type, alias, and source identity', () => {
  const source = `
    export { createClient, type ClientOptions } from './client';
    export type { InternalError as PublicError } from './errors';
    export * from './future';
    export * as clientNamespace from './namespace';
    export async function loadClient() {}
    const localValue = true;
    const type = true;
    export { localValue, type as typeValue }
    const documentation = "export function phantom() {}";
    const exportWord = "export";
    function phantomAfterString() {}
    // export const ignored = true;
  `;

  assert.deepEqual(extractPublicExports(source), [
    { name: '*', kind: 'wildcard', source: './future' },
    { name: 'ClientOptions', kind: 'type', source: './client' },
    { name: 'PublicError', kind: 'type', source: './errors' },
    { name: 'clientNamespace', kind: 'value', source: './namespace' },
    { name: 'createClient', kind: 'value', source: './client' },
    { name: 'loadClient', kind: 'value', source: '<entrypoint>' },
    { name: 'localValue', kind: 'value', source: '<entrypoint>' },
    { name: 'typeValue', kind: 'value', source: '<entrypoint>' },
  ]);
});

test('backend-change classifier distinguishes protected backend paths', () => {
  assert.equal(isBackendProtectedPath('services/parking-service/src/main/App.java'), true);
  assert.equal(isBackendProtectedPath('platform/docker/Dockerfile'), true);
  assert.equal(isBackendProtectedPath('infra/terraform/main.tf'), true);
  assert.equal(isBackendProtectedPath('.dockerignore'), true);
  assert.equal(isBackendProtectedPath('settings.gradle.kts'), true);
  assert.equal(isBackendProtectedPath('frontend/packages/api-client/src/index.ts'), false);
  assert.equal(isBackendProtectedPath('docs/sprint-2.3/implementation-plan.md'), false);
});

test('WP-04 rejects global queryClient.clear outside the session-cache owner', () => {
  const source = `
    export function logout(queryClient) {
      queryClient.clear();
    }
  `;
  const rules = findWp04DataArchitectureViolations(source, 'apps/web/src/auth/logout.ts').map(
    (v) => v.rule,
  );
  assert.ok(rules.includes('wp04-no-global-query-clear'));
  assert.deepEqual(
    findWp04DataArchitectureViolations(
      'export function clearUserSessionQueries(queryClient) { queryClient.clear(); }',
      'apps/web/src/data/sessionQueryCache.ts',
    ),
    [],
  );
});

test('WP-04 rejects duplicate meKeys registries and inline migrated query keys', () => {
  assert.ok(
    findWp04DataArchitectureViolations(
      'export const meKeys = { all: ["me"] };',
      'apps/web/src/pages/profile/localKeys.ts',
    ).some((v) => v.rule === 'wp04-duplicate-query-key-registry'),
  );
  assert.deepEqual(
    findWp04DataArchitectureViolations(
      'export const meKeys = { all: ["me"] as const };',
      'apps/web/src/data/keys.ts',
    ),
    [],
  );
  assert.ok(
    findWp04DataArchitectureViolations(
      'useQuery({ queryKey: ["me", "profile"], queryFn: fn });',
      'apps/web/src/pages/profile/ImpactHero.tsx',
    ).some((v) => v.rule === 'wp04-inline-migrated-query-key'),
  );
  assert.deepEqual(
    findWp04DataArchitectureViolations(
      'useQuery({ queryKey: meKeys.profile(), queryFn: fn });',
      'apps/web/src/pages/profile/ImpactHero.tsx',
    ),
    [],
  );
});

test('WP-04 permits page-local UI state and rejects feature bearer headers', () => {
  assert.deepEqual(
    findWp04DataArchitectureViolations(
      'const steps = [{ path: "/a", title: "A" }, { path: "/b", title: "B" }];',
      'apps/web/src/pages/UploadPage.tsx',
    ),
    [],
  );
  assert.ok(
    findWp04DataArchitectureViolations(
      'export const config = { headers: { Authorization: "Bearer secret" } };',
      'apps/web/src/pages/MapPage.tsx',
    ).some((v) => v.rule === 'wp04-no-feature-auth-headers'),
  );
});

test('WP-05 rejects page-level parking mutation API calls and duplicate spot-cache helpers', () => {
  assert.ok(
    findWp05CoreParkingViolations(
      `
        export function Bad() {
          const { parkingApi } = useParkioSdk();
          return parkingApi.verifySpot(id, body, key);
        }
      `,
      'apps/web/src/pages/SpotDetailPage.tsx',
    ).some((v) => v.rule === 'wp05-page-parking-mutation-api'),
  );
  assert.ok(
    findWp05CoreParkingViolations(
      `
        export function Bad() {
          const { parkingApi } = useParkioSdk();
          return parkingApi.createParkingSpot(body, key);
        }
      `,
      'apps/web/src/pages/UploadPage.tsx',
    ).some((v) => v.rule === 'wp05-page-parking-mutation-api'),
  );
  assert.ok(
    findWp05CoreParkingViolations(
      `
        export function Bad() {
          const { moderationApi } = useParkioSdk();
          return moderationApi.createReport(body);
        }
      `,
      'apps/web/src/pages/SpotDetailPage.tsx',
    ).some((v) => v.rule === 'wp05-page-report-mutation-api'),
  );
  assert.ok(
    findWp05CoreParkingViolations(
      `export function applyParkingSpotUpdate() { return null; }`,
      'apps/web/src/pages/SpotDetailPage.tsx',
    ).some((v) => v.rule === 'wp05-duplicate-spot-cache-helper'),
  );
});

test('WP-05 permits data-layer mutation owners and rejects page smart-return cache writes', () => {
  assert.equal(
    findWp05CoreParkingViolations(
      `
        export function createVerifySpotMutationOptions(sdk) {
          return { mutationFn: () => sdk.parkingApi.verifySpot(id, body, key) };
        }
      `,
      'apps/web/src/data/mutation-options/parking.ts',
    ).length,
    0,
  );
  assert.ok(
    findWp05CoreParkingViolations(
      `
        export function Bad() {
          queryClient.setQueryData(meKeys.smartReturn(), next);
        }
      `,
      'apps/web/src/pages/profile/SmartReturnCard.tsx',
    ).some((v) => v.rule === 'wp05-page-smart-return-cache-write'),
  );
  assert.equal(
    findWp05CoreParkingViolations(
      `export const config = { auth: 'Bearer x' };`,
      'apps/web/src/pages/MapPage.test.tsx',
    ).length,
    0,
  );
});
