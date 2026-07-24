export const ROUTE_IDS = Object.freeze({
  ROOT: 'routing.root',
  LOGIN: 'auth.login',
  REGISTER: 'auth.register',
  FORGOT_PASSWORD: 'auth.forgot-password',
  RESET_PASSWORD: 'auth.reset-password',
  CHECK_EMAIL: 'auth.check-email',
  VERIFY_EMAIL: 'auth.verify-email',
  TERMS: 'legal.terms',
  PRIVACY: 'legal.privacy',
  PROTECTED_BOUNDARY: 'routing.protected',
  AUTHENTICATED_ENTRY: 'app.entry',
  PREPARING: 'account.preparing',
  ACCOUNT_NOT_ACTIVE_SURFACE: 'account.not-active',
  APPLICATION_SHELL: 'shell.application',
  MAP: 'app.map',
  SPOT_DETAIL: 'app.spot-detail',
  MY_SPOTS: 'app.my-spots',
  UPLOAD: 'app.upload',
  PROFILE: 'app.profile',
  REPORTS: 'app.reports',
  NOTIFICATIONS: 'app.notifications',
  GAMIFICATION: 'app.gamification',
  LEADERBOARD: 'app.leaderboard',
  PRIVILEGED_BOUNDARY: 'routing.privileged',
  MODERATION_ALIAS: 'staff.moderation-alias',
  ADMIN_MODERATION: 'staff.moderation',
  ADMIN_BOUNDARY: 'routing.admin',
  ANALYTICS_ALIAS: 'admin.analytics-alias',
  ADMIN_SHELL: 'shell.admin',
  ADMIN_DASHBOARD: 'admin.dashboard',
  ADMIN_USERS: 'admin.users',
  ADMIN_USER_DETAIL: 'admin.user-detail',
  ADMIN_SECURITY: 'admin.security',
  ADMIN_ANALYTICS: 'admin.analytics',
  ADMIN_AUDIT: 'admin.audit',
  ADMIN_SYSTEM: 'admin.system',
  NOT_FOUND: 'routing.not-found',
} as const);

export type RouteId = (typeof ROUTE_IDS)[keyof typeof ROUTE_IDS];

export const ROUTE_COMPONENT_KEYS = Object.freeze({
  ROUTE_ACCESSIBILITY: 'route-accessibility',
  LOGIN_PAGE: 'login-page',
  REGISTER_PAGE: 'register-page',
  FORGOT_PASSWORD_PAGE: 'forgot-password-page',
  RESET_PASSWORD_PAGE: 'reset-password-page',
  CHECK_EMAIL_PAGE: 'check-email-page',
  VERIFY_EMAIL_PAGE: 'verify-email-page',
  TERMS_PAGE: 'terms-page',
  PRIVACY_PAGE: 'privacy-page',
  PROTECTED_BOUNDARY: 'protected-boundary',
  REDIRECT: 'redirect',
  ACCOUNT_PREPARING_PAGE: 'account-preparing-page',
  ACCOUNT_SUSPENDED_PAGE: 'account-suspended-page',
  APPLICATION_SHELL: 'application-shell',
  MAP_PAGE: 'map-page',
  SPOT_DETAIL_PAGE: 'spot-detail-page',
  MY_SPOTS_PAGE: 'my-spots-page',
  UPLOAD_PAGE: 'upload-page',
  PROFILE_PAGE: 'profile-page',
  REPORTS_PAGE: 'reports-page',
  NOTIFICATIONS_PAGE: 'notifications-page',
  GAMIFICATION_PAGE: 'gamification-page',
  LEADERBOARD_PAGE: 'leaderboard-page',
  PRIVILEGED_BOUNDARY: 'privileged-boundary',
  MODERATION_PAGE: 'moderation-page',
  ADMIN_BOUNDARY: 'admin-boundary',
  ADMIN_SHELL: 'admin-shell',
  ADMIN_DASHBOARD_PAGE: 'admin-dashboard-page',
  ADMIN_USERS_PAGE: 'admin-users-page',
  ADMIN_USER_DETAIL_PAGE: 'admin-user-detail-page',
  ADMIN_SECURITY_PAGE: 'admin-security-page',
  ANALYTICS_PAGE: 'analytics-page',
  ADMIN_AUDIT_PAGE: 'admin-audit-page',
  ADMIN_SYSTEM_PAGE: 'admin-system-page',
  NOT_FOUND_PAGE: 'not-found-page',
} as const);

export type RouteComponentKey =
  (typeof ROUTE_COMPONENT_KEYS)[keyof typeof ROUTE_COMPONENT_KEYS];
export type RoutePathKind = 'path' | 'index' | 'pathless';
export type RouteAccessPolicy = 'inherit' | 'public' | 'protected';
export type RouteBootstrapPolicy =
  | 'inherit'
  | 'public-immediate'
  | 'protected-await';
export type RouteLifecyclePolicy =
  | 'inherit'
  | 'public'
  | 'protected-entry'
  | 'authenticated'
  | 'provisioning-only'
  | 'account-not-active-surface';
export type RouteRoleRequirement = 'inherit' | 'none' | 'privileged' | 'admin';
export type RouteShellOwnership = 'none' | 'application' | 'admin';
export type RouteLoadPolicy = 'eager' | 'lazy';
export type RouteFallbackPolicy = 'none' | 'shared' | 'profile';
export type RouteNavigationGroup = 'primary' | 'secondary' | 'staff';
export type RouteParameterValidator = 'uuid';
export type RouteNavigationInterruptionPolicy = 'guarded' | 'bypass';

export interface RouteRedirectMetadata {
  readonly targetId: RouteId;
  readonly replace: true;
}

export interface RouteNavigationMetadata {
  readonly group: RouteNavigationGroup;
  readonly labelKey: string;
  readonly icon: string;
  readonly order: number;
}

export interface RouteShellNavigationMetadata {
  readonly labelKey: string;
  readonly order: number;
  readonly end: boolean;
}

export interface RouteParameterMetadata {
  readonly name: string;
  readonly validator: RouteParameterValidator;
}

interface RouteManifestEntryBase {
  readonly id: RouteId;
  readonly parentId: RouteId | null;
  readonly componentKey: RouteComponentKey;
  readonly access: RouteAccessPolicy;
  readonly bootstrap: RouteBootstrapPolicy;
  readonly lifecycle: RouteLifecyclePolicy;
  readonly role: RouteRoleRequirement;
  readonly shell: RouteShellOwnership;
  readonly redirect: RouteRedirectMetadata | null;
  readonly redirectEligible: boolean;
  readonly load: RouteLoadPolicy;
  readonly fallback: RouteFallbackPolicy;
  readonly navigation: RouteNavigationMetadata | null;
  readonly shellNavigation: RouteShellNavigationMetadata | null;
  readonly parameters: readonly RouteParameterMetadata[];
  readonly documentTitleKey: string | null;
  readonly navigationInterruption: RouteNavigationInterruptionPolicy;
}

export interface PathRouteManifestEntry extends RouteManifestEntryBase {
  readonly kind: 'path';
  readonly path: string;
}

export interface IndexRouteManifestEntry extends RouteManifestEntryBase {
  readonly kind: 'index';
}

export interface PathlessRouteManifestEntry extends RouteManifestEntryBase {
  readonly kind: 'pathless';
}

export type RouteManifestEntry =
  | PathRouteManifestEntry
  | IndexRouteManifestEntry
  | PathlessRouteManifestEntry;

export type RouteManifestViolationCode =
  | 'duplicate-route-id'
  | 'duplicate-path'
  | 'duplicate-index'
  | 'missing-parent'
  | 'parent-cycle'
  | 'invalid-root'
  | 'multiple-catch-all'
  | 'invalid-path'
  | 'invalid-index'
  | 'invalid-pathless'
  | 'invalid-parameter-metadata'
  | 'missing-redirect-target'
  | 'self-redirect'
  | 'invalid-public-policy'
  | 'invalid-protected-policy'
  | 'invalid-provisioning-policy'
  | 'invalid-redirect-eligibility'
  | 'invalid-loading-policy'
  | 'invalid-navigation-policy'
  | 'invalid-document-title-policy'
  | 'invalid-navigation-interruption-policy'
  | 'invalid-shell-navigation-policy'
  | 'duplicate-shell-navigation-order';

export interface RouteManifestViolation {
  readonly code: RouteManifestViolationCode;
  readonly routeId: string;
  readonly detail: string;
}

export type RoutePathClassification = 'public' | 'protected' | 'unrecognized';

const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

const noParameters = Object.freeze([]) as readonly RouteParameterMetadata[];

type CanonicalMetadataInput = {
  readonly documentTitleKey?: string;
  readonly navigationInterruption?: RouteNavigationInterruptionPolicy;
};

type EagerRouteInput = RouteManifestEntry extends infer Entry
  ? Entry extends RouteManifestEntry
    ? Omit<
        Entry,
        | 'load'
        | 'fallback'
        | 'parameters'
        | 'shellNavigation'
        | 'documentTitleKey'
        | 'navigationInterruption'
      > &
        CanonicalMetadataInput & {
        readonly parameters?: readonly RouteParameterMetadata[];
        readonly shellNavigation?: RouteShellNavigationMetadata;
      }
    : never
  : never;

type LazyRouteInput = RouteManifestEntry extends infer Entry
  ? Entry extends RouteManifestEntry
    ? Omit<
        Entry,
        | 'load'
        | 'fallback'
        | 'parameters'
        | 'shellNavigation'
        | 'documentTitleKey'
        | 'navigationInterruption'
      > &
        CanonicalMetadataInput & {
        readonly fallback?: Exclude<RouteFallbackPolicy, 'none'>;
        readonly parameters?: readonly RouteParameterMetadata[];
        readonly shellNavigation?: RouteShellNavigationMetadata;
      }
    : never
  : never;

function freezeEntry(entry: RouteManifestEntry): RouteManifestEntry {
  const redirect = entry.redirect ? Object.freeze({ ...entry.redirect }) : null;
  const navigation = entry.navigation
    ? Object.freeze({ ...entry.navigation })
    : null;
  const shellNavigation = entry.shellNavigation
    ? Object.freeze({ ...entry.shellNavigation })
    : null;
  const parameters = Object.freeze(
    entry.parameters.map((parameter) => Object.freeze({ ...parameter })),
  );

  return Object.freeze({
    ...entry,
    redirect,
    navigation,
    shellNavigation,
    parameters,
  });
}

function eagerBase(entry: EagerRouteInput): RouteManifestEntry {
  return freezeEntry({
    ...entry,
    load: 'eager',
    fallback: 'none',
    shellNavigation: entry.shellNavigation ?? null,
    parameters: entry.parameters ?? noParameters,
    documentTitleKey: entry.documentTitleKey ?? null,
    navigationInterruption: entry.navigationInterruption ?? 'guarded',
  } as RouteManifestEntry);
}

function lazyBase(entry: LazyRouteInput): RouteManifestEntry {
  return freezeEntry({
    ...entry,
    load: 'lazy',
    fallback: entry.fallback ?? 'shared',
    shellNavigation: entry.shellNavigation ?? null,
    parameters: entry.parameters ?? noParameters,
    documentTitleKey: entry.documentTitleKey ?? null,
    navigationInterruption: entry.navigationInterruption ?? 'guarded',
  } as RouteManifestEntry);
}

const routeManifestEntries: readonly RouteManifestEntry[] = [
  eagerBase({
    id: ROUTE_IDS.ROOT,
    parentId: null,
    kind: 'pathless',
    componentKey: ROUTE_COMPONENT_KEYS.ROUTE_ACCESSIBILITY,
    access: 'inherit',
    bootstrap: 'inherit',
    lifecycle: 'inherit',
    role: 'inherit',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.LOGIN,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/login',
    componentKey: ROUTE_COMPONENT_KEYS.LOGIN_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.login',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.REGISTER,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/register',
    componentKey: ROUTE_COMPONENT_KEYS.REGISTER_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.register',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.FORGOT_PASSWORD,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/forgot-password',
    componentKey: ROUTE_COMPONENT_KEYS.FORGOT_PASSWORD_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.forgotPassword',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.RESET_PASSWORD,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/reset-password',
    componentKey: ROUTE_COMPONENT_KEYS.RESET_PASSWORD_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.resetPassword',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.CHECK_EMAIL,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/check-email',
    componentKey: ROUTE_COMPONENT_KEYS.CHECK_EMAIL_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.checkEmail',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.VERIFY_EMAIL,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/verify-email',
    componentKey: ROUTE_COMPONENT_KEYS.VERIFY_EMAIL_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.verifyEmail',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.TERMS,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/terms',
    componentKey: ROUTE_COMPONENT_KEYS.TERMS_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.terms',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.PRIVACY,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '/privacy',
    componentKey: ROUTE_COMPONENT_KEYS.PRIVACY_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.privacy',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.PROTECTED_BOUNDARY,
    parentId: ROUTE_IDS.ROOT,
    kind: 'pathless',
    componentKey: ROUTE_COMPONENT_KEYS.PROTECTED_BOUNDARY,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'protected-entry',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.AUTHENTICATED_ENTRY,
    parentId: ROUTE_IDS.PROTECTED_BOUNDARY,
    kind: 'path',
    path: '/',
    componentKey: ROUTE_COMPONENT_KEYS.REDIRECT,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'none',
    redirect: { targetId: ROUTE_IDS.MAP, replace: true },
    redirectEligible: true,
    documentTitleKey: 'titles.home',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.PREPARING,
    parentId: ROUTE_IDS.PROTECTED_BOUNDARY,
    kind: 'path',
    path: '/preparing',
    componentKey: ROUTE_COMPONENT_KEYS.ACCOUNT_PREPARING_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'provisioning-only',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.preparing',
    navigationInterruption: 'bypass',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.ACCOUNT_NOT_ACTIVE_SURFACE,
    parentId: ROUTE_IDS.PROTECTED_BOUNDARY,
    kind: 'pathless',
    componentKey: ROUTE_COMPONENT_KEYS.ACCOUNT_SUSPENDED_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'account-not-active-surface',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.APPLICATION_SHELL,
    parentId: ROUTE_IDS.PROTECTED_BOUNDARY,
    kind: 'pathless',
    componentKey: ROUTE_COMPONENT_KEYS.APPLICATION_SHELL,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  lazyBase({
    id: ROUTE_IDS.MAP,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/map',
    componentKey: ROUTE_COMPONENT_KEYS.MAP_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.map',
    navigation: {
      group: 'primary',
      labelKey: 'navigation:primary.map',
      icon: 'map',
      order: 0,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.SPOT_DETAIL,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/spots/:spotId',
    componentKey: ROUTE_COMPONENT_KEYS.SPOT_DETAIL_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.spotDetails',
    navigation: null,
    parameters: [{ name: 'spotId', validator: 'uuid' }],
  }),
  lazyBase({
    id: ROUTE_IDS.MY_SPOTS,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/my-spots',
    componentKey: ROUTE_COMPONENT_KEYS.MY_SPOTS_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.mySpots',
    navigation: {
      group: 'primary',
      labelKey: 'navigation:primary.mySpots',
      icon: 'bookmark',
      order: 1,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.UPLOAD,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/upload',
    componentKey: ROUTE_COMPONENT_KEYS.UPLOAD_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.upload',
    navigation: {
      group: 'primary',
      labelKey: 'navigation:primary.share',
      icon: 'add_location_alt',
      order: 2,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.PROFILE,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/profile',
    componentKey: ROUTE_COMPONENT_KEYS.PROFILE_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    fallback: 'profile',
    documentTitleKey: 'titles.profile',
    navigation: {
      group: 'primary',
      labelKey: 'navigation:primary.profile',
      icon: 'account_circle',
      order: 4,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.REPORTS,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/reports',
    componentKey: ROUTE_COMPONENT_KEYS.REPORTS_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.reports',
    navigation: {
      group: 'secondary',
      labelKey: 'navigation:secondary.reports',
      icon: 'flag',
      order: 0,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.NOTIFICATIONS,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/notifications',
    componentKey: ROUTE_COMPONENT_KEYS.NOTIFICATIONS_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.notifications',
    navigation: {
      group: 'secondary',
      labelKey: 'navigation:secondary.notifications',
      icon: 'notifications',
      order: 2,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.GAMIFICATION,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/gamification',
    componentKey: ROUTE_COMPONENT_KEYS.GAMIFICATION_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.gamification',
    navigation: {
      group: 'secondary',
      labelKey: 'navigation:secondary.impact',
      icon: 'military_tech',
      order: 1,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.LEADERBOARD,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'path',
    path: '/leaderboard',
    componentKey: ROUTE_COMPONENT_KEYS.LEADERBOARD_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'none',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.leaderboard',
    navigation: {
      group: 'primary',
      labelKey: 'navigation:primary.leaderboard',
      icon: 'leaderboard',
      order: 3,
    },
  }),
  eagerBase({
    id: ROUTE_IDS.PRIVILEGED_BOUNDARY,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'pathless',
    componentKey: ROUTE_COMPONENT_KEYS.PRIVILEGED_BOUNDARY,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'privileged',
    shell: 'application',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.MODERATION_ALIAS,
    parentId: ROUTE_IDS.PRIVILEGED_BOUNDARY,
    kind: 'path',
    path: '/moderation',
    componentKey: ROUTE_COMPONENT_KEYS.REDIRECT,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'privileged',
    shell: 'application',
    redirect: { targetId: ROUTE_IDS.ADMIN_MODERATION, replace: true },
    redirectEligible: true,
    documentTitleKey: 'titles.moderation',
    navigation: null,
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_MODERATION,
    parentId: ROUTE_IDS.PRIVILEGED_BOUNDARY,
    kind: 'path',
    path: '/admin/moderation',
    componentKey: ROUTE_COMPONENT_KEYS.MODERATION_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'privileged',
    shell: 'application',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.moderation',
    navigation: {
      group: 'staff',
      labelKey: 'navigation:staff.moderation',
      icon: 'gavel',
      order: 0,
    },
    shellNavigation: {
      labelKey: 'admin:shell.nav.moderation',
      order: 3,
      end: false,
    },
  }),
  eagerBase({
    id: ROUTE_IDS.ADMIN_BOUNDARY,
    parentId: ROUTE_IDS.APPLICATION_SHELL,
    kind: 'pathless',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_BOUNDARY,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'application',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.ANALYTICS_ALIAS,
    parentId: ROUTE_IDS.ADMIN_BOUNDARY,
    kind: 'path',
    path: '/analytics',
    componentKey: ROUTE_COMPONENT_KEYS.REDIRECT,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'application',
    redirect: { targetId: ROUTE_IDS.ADMIN_ANALYTICS, replace: true },
    redirectEligible: true,
    documentTitleKey: 'titles.analytics',
    navigation: null,
  }),
  eagerBase({
    id: ROUTE_IDS.ADMIN_SHELL,
    parentId: ROUTE_IDS.ADMIN_BOUNDARY,
    kind: 'path',
    path: '/admin',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_SHELL,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: false,
    navigation: null,
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_DASHBOARD,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'index',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_DASHBOARD_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.adminDashboard',
    navigation: {
      group: 'staff',
      labelKey: 'navigation:staff.admin',
      icon: 'admin_panel_settings',
      order: 1,
    },
    shellNavigation: {
      labelKey: 'admin:shell.nav.dashboard',
      order: 0,
      end: true,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_USERS,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'path',
    path: 'users',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_USERS_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.adminUsers',
    navigation: null,
    shellNavigation: {
      labelKey: 'admin:shell.nav.users',
      order: 1,
      end: false,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_USER_DETAIL,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'path',
    path: 'users/:id',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_USER_DETAIL_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.adminUser',
    navigation: null,
    parameters: [{ name: 'id', validator: 'uuid' }],
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_SECURITY,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'path',
    path: 'security',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_SECURITY_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.adminSecurity',
    navigation: null,
    shellNavigation: {
      labelKey: 'admin:shell.nav.security',
      order: 2,
      end: false,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_ANALYTICS,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'path',
    path: 'analytics',
    componentKey: ROUTE_COMPONENT_KEYS.ANALYTICS_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.analytics',
    navigation: null,
    shellNavigation: {
      labelKey: 'admin:shell.nav.analytics',
      order: 4,
      end: false,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_AUDIT,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'path',
    path: 'audit',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_AUDIT_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.adminAudit',
    navigation: null,
    shellNavigation: {
      labelKey: 'admin:shell.nav.audit',
      order: 5,
      end: false,
    },
  }),
  lazyBase({
    id: ROUTE_IDS.ADMIN_SYSTEM,
    parentId: ROUTE_IDS.ADMIN_SHELL,
    kind: 'path',
    path: 'system',
    componentKey: ROUTE_COMPONENT_KEYS.ADMIN_SYSTEM_PAGE,
    access: 'protected',
    bootstrap: 'protected-await',
    lifecycle: 'authenticated',
    role: 'admin',
    shell: 'admin',
    redirect: null,
    redirectEligible: true,
    documentTitleKey: 'titles.adminSystem',
    navigation: null,
    shellNavigation: {
      labelKey: 'admin:shell.nav.system',
      order: 6,
      end: false,
    },
  }),
  eagerBase({
    id: ROUTE_IDS.NOT_FOUND,
    parentId: ROUTE_IDS.ROOT,
    kind: 'path',
    path: '*',
    componentKey: ROUTE_COMPONENT_KEYS.NOT_FOUND_PAGE,
    access: 'public',
    bootstrap: 'public-immediate',
    lifecycle: 'public',
    role: 'none',
    shell: 'none',
    redirect: null,
    redirectEligible: false,
    documentTitleKey: 'titles.notFound',
    navigation: null,
  }),
];

export const ROUTE_MANIFEST = Object.freeze(routeManifestEntries);

export const AUTH_LIFECYCLE_DESTINATIONS = Object.freeze({
  anonymous: ROUTE_IDS.LOGIN,
  authenticatedDefault: ROUTE_IDS.MAP,
  provisioning: ROUTE_IDS.PREPARING,
  accountNotVerified: ROUTE_IDS.CHECK_EMAIL,
  accountNotActiveSurface: ROUTE_IDS.ACCOUNT_NOT_ACTIVE_SURFACE,
} as const);

function routeById(
  routeId: string,
  manifest: readonly RouteManifestEntry[],
): RouteManifestEntry | undefined {
  return manifest.find((entry) => entry.id === routeId);
}

export function getRouteManifestEntry(routeId: RouteId): RouteManifestEntry {
  const entry = routeById(routeId, ROUTE_MANIFEST);
  if (!entry) {
    throw new Error(`Unknown route id: ${routeId}`);
  }
  return entry;
}

function joinPaths(parentPath: string | null, childPath: string): string {
  if (childPath.startsWith('/')) {
    return childPath;
  }
  const parent = parentPath?.replace(/\/+$/, '') ?? '';
  return `${parent}/${childPath}`;
}

function effectivePath(
  entry: RouteManifestEntry,
  manifest: readonly RouteManifestEntry[],
  visited = new Set<string>(),
): string | null {
  if (visited.has(entry.id)) {
    return null;
  }
  visited.add(entry.id);

  if (entry.kind === 'path' && entry.path.startsWith('/')) {
    return entry.path;
  }

  const parent = entry.parentId ? routeById(entry.parentId, manifest) : undefined;
  const parentPath = parent ? effectivePath(parent, manifest, visited) : null;

  if (entry.kind === 'path') {
    return joinPaths(parentPath, entry.path);
  }
  if (entry.kind === 'index') {
    return parentPath;
  }
  return parentPath;
}

export function getRoutePath(routeId: RouteId): string {
  const entry = getRouteManifestEntry(routeId);
  const path = effectivePath(entry, ROUTE_MANIFEST);
  if (!path || path === '*') {
    throw new Error(`Route '${routeId}' does not have a buildable path.`);
  }
  return path;
}

export function isValidRouteParameter(
  validator: RouteParameterValidator,
  value: string,
): boolean {
  switch (validator) {
    case 'uuid':
      return UUID_PATTERN.test(value);
  }
}

export function buildRoutePath(
  routeId: RouteId,
  parameters: Readonly<Record<string, string>> = {},
): string {
  const entry = getRouteManifestEntry(routeId);
  const template = getRoutePath(routeId);
  const declaredNames = new Set(entry.parameters.map((parameter) => parameter.name));
  const suppliedNames = Object.keys(parameters);

  for (const suppliedName of suppliedNames) {
    if (!declaredNames.has(suppliedName)) {
      throw new Error(
        `Route '${routeId}' does not declare parameter '${suppliedName}'.`,
      );
    }
  }

  let result = template;
  for (const parameter of entry.parameters) {
    const value = parameters[parameter.name];
    if (!value || !isValidRouteParameter(parameter.validator, value)) {
      throw new Error(
        `Route '${routeId}' requires a valid ${parameter.validator} parameter '${parameter.name}'.`,
      );
    }
    result = result.replace(`:${parameter.name}`, value);
  }

  return result;
}

function normalizeInternalPath(value: string): string | null {
  const pathname = value.split(/[?#]/, 1)[0] ?? '';
  if (!pathname.startsWith('/') || pathname.startsWith('//')) {
    return null;
  }
  return pathname.replace(/\/+$/, '') || '/';
}

function pathPatternMatchesEntry(
  entry: RouteManifestEntry,
  pathname: string,
): boolean {
  const template = effectivePath(entry, ROUTE_MANIFEST);
  if (!template || template === '*') {
    return false;
  }

  const templateSegments = template.split('/').filter(Boolean);
  const pathSegments = pathname.split('/').filter(Boolean);
  if (templateSegments.length !== pathSegments.length) {
    return false;
  }

  return templateSegments.every((segment, index) => {
    if (!segment.startsWith(':')) {
      return segment === pathSegments[index];
    }
    return Boolean(pathSegments[index]);
  });
}

function routeParametersAreValid(
  entry: RouteManifestEntry,
  pathname: string,
): boolean {
  const template = effectivePath(entry, ROUTE_MANIFEST);
  if (!template || template === '*') {
    return false;
  }

  const templateSegments = template.split('/').filter(Boolean);
  const pathSegments = pathname.split('/').filter(Boolean);

  return templateSegments.every((segment, index) => {
    if (!segment.startsWith(':')) {
      return true;
    }
    const name = segment.slice(1);
    const parameter = entry.parameters.find((candidate) => candidate.name === name);
    const value = pathSegments[index];
    return Boolean(
      parameter &&
        value &&
        isValidRouteParameter(parameter.validator, value),
    );
  });
}

function isAddressable(entry: RouteManifestEntry): boolean {
  return entry.kind === 'path' || entry.kind === 'index';
}

export function findRouteManifestEntryByPath(
  value: string,
): RouteManifestEntry | null {
  const pathname = normalizeInternalPath(value);
  if (!pathname) {
    return null;
  }

  const matches = ROUTE_MANIFEST.filter(
    (entry) => isAddressable(entry) && pathPatternMatchesEntry(entry, pathname),
  );
  const indexMatch = matches.find((entry) => entry.kind === 'index');
  if (indexMatch) {
    return indexMatch;
  }
  if (matches[0]) {
    return matches[0];
  }
  return getRouteManifestEntry(ROUTE_IDS.NOT_FOUND);
}

export function getRouteDocumentTitleKey(value: string): string {
  const entry = findRouteManifestEntryByPath(value);
  const titleKey =
    entry?.documentTitleKey ??
    getRouteManifestEntry(ROUTE_IDS.NOT_FOUND).documentTitleKey;
  if (!titleKey) {
    throw new Error(`Route '${entry?.id ?? ROUTE_IDS.NOT_FOUND}' has no document title.`);
  }
  return titleKey;
}

export function isNavigationInterruptionBypassPath(
  pathname: string,
): boolean {
  return ROUTE_MANIFEST.some(
    (entry) =>
      entry.navigationInterruption === 'bypass' &&
      isAddressable(entry) &&
      effectivePath(entry, ROUTE_MANIFEST) === pathname,
  );
}

export function classifyRoutePath(value: string): RoutePathClassification {
  const entry = findRouteManifestEntryByPath(value);
  if (!entry) {
    return 'unrecognized';
  }
  return entry.access === 'protected' ? 'protected' : 'public';
}

export function getPublicRouteIds(): readonly RouteId[] {
  return Object.freeze(
    ROUTE_MANIFEST.filter(
      (entry) => isAddressable(entry) && entry.access === 'public',
    ).map((entry) => entry.id),
  );
}

export function getProtectedRouteIds(): readonly RouteId[] {
  return Object.freeze(
    ROUTE_MANIFEST.filter(
      (entry) => isAddressable(entry) && entry.access === 'protected',
    ).map((entry) => entry.id),
  );
}

export function getRedirectEligibleRouteIds(): readonly RouteId[] {
  return Object.freeze(
    ROUTE_MANIFEST.filter((entry) => entry.redirectEligible).map(
      (entry) => entry.id,
    ),
  );
}

export function isRedirectEligiblePath(value: string): boolean {
  const pathname = normalizeInternalPath(value);
  if (!pathname) {
    return false;
  }
  return ROUTE_MANIFEST.some(
    (entry) =>
      entry.redirectEligible &&
      isAddressable(entry) &&
      pathPatternMatchesEntry(entry, pathname) &&
      routeParametersAreValid(entry, pathname),
  );
}

function addViolation(
  violations: RouteManifestViolation[],
  code: RouteManifestViolationCode,
  routeId: string,
  detail: string,
): void {
  violations.push(Object.freeze({ code, routeId, detail }));
}

function parameterNamesFromPath(path: string): readonly string[] {
  return [...path.matchAll(/:([A-Za-z0-9_]+)/g)].map((match) => match[1]!);
}

function canonicalPathPattern(path: string): string {
  const canonical = path
    .replace(/:[A-Za-z0-9_]+/g, ':parameter')
    .replace(/\/+$/, '');
  return canonical.length > 0 ? canonical : '/';
}

export function validateRouteManifest(
  manifest: readonly RouteManifestEntry[],
): readonly RouteManifestViolation[] {
  const violations: RouteManifestViolation[] = [];
  const entriesById = new Map<string, RouteManifestEntry>();
  const pathOwners = new Map<string, string>();
  const indexOwners = new Map<string, string>();
  const shellNavigationOrders = new Map<number, string>();
  let rootCount = 0;
  let catchAllCount = 0;

  for (const entry of manifest) {
    if (entriesById.has(entry.id)) {
      addViolation(
        violations,
        'duplicate-route-id',
        entry.id,
        `Route id '${entry.id}' is declared more than once.`,
      );
    } else {
      entriesById.set(entry.id, entry);
    }
    if (entry.parentId === null) {
      rootCount += 1;
    }
  }

  for (const entry of manifest) {
    if (entry.parentId !== null && !entriesById.has(entry.parentId)) {
      addViolation(
        violations,
        'missing-parent',
        entry.id,
        `Parent route '${entry.parentId}' does not exist.`,
      );
    }

    const visited = new Set<string>();
    let cursor: RouteManifestEntry | undefined = entry;
    while (cursor?.parentId) {
      if (visited.has(cursor.id)) {
        addViolation(
          violations,
          'parent-cycle',
          entry.id,
          `Route '${entry.id}' participates in a parent cycle.`,
        );
        break;
      }
      visited.add(cursor.id);
      cursor = entriesById.get(cursor.parentId);
    }

    if (entry.kind === 'path') {
      if (!entry.path || (entry.path !== '*' && entry.path.includes('//'))) {
        addViolation(
          violations,
          'invalid-path',
          entry.id,
          `Route '${entry.id}' has an invalid path.`,
        );
      }
      if (entry.path === '*') {
        catchAllCount += 1;
      } else {
        const path = effectivePath(entry, manifest);
        if (path) {
          const canonicalPath = canonicalPathPattern(path);
          const previousOwner = pathOwners.get(canonicalPath);
          if (previousOwner) {
            addViolation(
              violations,
              'duplicate-path',
              entry.id,
              `Path '${path}' duplicates route '${previousOwner}'.`,
            );
          } else {
            pathOwners.set(canonicalPath, entry.id);
          }
        }
      }

      const pathParameters = [...parameterNamesFromPath(entry.path)].sort();
      const metadataParameters = entry.parameters
        .map((parameter) => parameter.name)
        .sort();
      if (
        pathParameters.length !== metadataParameters.length ||
        pathParameters.some(
          (parameter, index) => parameter !== metadataParameters[index],
        )
      ) {
        addViolation(
          violations,
          'invalid-parameter-metadata',
          entry.id,
          `Route parameters for '${entry.id}' do not match its path template.`,
        );
      }
    } else if (entry.parameters.length > 0) {
      addViolation(
        violations,
        'invalid-parameter-metadata',
        entry.id,
        `Non-path route '${entry.id}' cannot declare path parameters.`,
      );
    }

    if (entry.kind === 'index') {
      if (!entry.parentId) {
        addViolation(
          violations,
          'invalid-index',
          entry.id,
          `Index route '${entry.id}' requires a parent.`,
        );
      } else {
        const previousIndex = indexOwners.get(entry.parentId);
        if (previousIndex) {
          addViolation(
            violations,
            'duplicate-index',
            entry.id,
            `Parent '${entry.parentId}' already has index route '${previousIndex}'.`,
          );
        } else {
          indexOwners.set(entry.parentId, entry.id);
        }
      }
    }

    if (entry.kind === 'pathless' && entry.redirectEligible) {
      addViolation(
        violations,
        'invalid-pathless',
        entry.id,
        `Pathless route '${entry.id}' cannot be redirect eligible.`,
      );
    }

    if (entry.redirect) {
      if (!entriesById.has(entry.redirect.targetId)) {
        addViolation(
          violations,
          'missing-redirect-target',
          entry.id,
          `Redirect target '${entry.redirect.targetId}' does not exist.`,
        );
      }
      if (entry.redirect.targetId === entry.id) {
        addViolation(
          violations,
          'self-redirect',
          entry.id,
          `Route '${entry.id}' cannot redirect to itself.`,
        );
      }
    }

    if (
      entry.access === 'public' &&
      (entry.bootstrap !== 'public-immediate' ||
        entry.lifecycle !== 'public' ||
        entry.role !== 'none' ||
        entry.redirectEligible)
    ) {
      addViolation(
        violations,
        'invalid-public-policy',
        entry.id,
        `Public route '${entry.id}' has protected routing metadata.`,
      );
    }

    if (
      entry.access === 'protected' &&
      entry.bootstrap !== 'protected-await'
    ) {
      addViolation(
        violations,
        'invalid-protected-policy',
        entry.id,
        `Protected route '${entry.id}' must await bootstrap.`,
      );
    }

    if (
      entry.lifecycle === 'provisioning-only' &&
      entry.id !== ROUTE_IDS.PREPARING
    ) {
      addViolation(
        violations,
        'invalid-provisioning-policy',
        entry.id,
        `Only '${ROUTE_IDS.PREPARING}' may be provisioning-only.`,
      );
    }

    if (
      entry.redirectEligible &&
      (entry.access !== 'protected' || !isAddressable(entry))
    ) {
      addViolation(
        violations,
        'invalid-redirect-eligibility',
        entry.id,
        `Redirect-eligible route '${entry.id}' must be addressable and protected.`,
      );
    }

    if (
      (entry.load === 'lazy' && entry.fallback === 'none') ||
      (entry.load === 'eager' && entry.fallback !== 'none')
    ) {
      addViolation(
        violations,
        'invalid-loading-policy',
        entry.id,
        `Route '${entry.id}' has inconsistent loading and fallback metadata.`,
      );
    }

    const hasIndexChild = manifest.some(
      (candidate) =>
        candidate.kind === 'index' && candidate.parentId === entry.id,
    );
    const ownsDocumentTitle = isAddressable(entry) && !hasIndexChild;
    if (
      (ownsDocumentTitle && !entry.documentTitleKey?.trim()) ||
      (hasIndexChild && entry.documentTitleKey !== null) ||
      (!isAddressable(entry) && entry.documentTitleKey !== null)
    ) {
      addViolation(
        violations,
        'invalid-document-title-policy',
        entry.id,
        `Route '${entry.id}' has inconsistent document-title metadata.`,
      );
    }

    if (
      entry.navigationInterruption === 'bypass' &&
      (!isAddressable(entry) || entry.parameters.length > 0)
    ) {
      addViolation(
        violations,
        'invalid-navigation-interruption-policy',
        entry.id,
        `Navigation-interruption bypass route '${entry.id}' must be static and addressable.`,
      );
    }

    if (
      entry.navigation &&
      (entry.redirect !== null ||
        !isAddressable(entry) ||
        entry.access !== 'protected')
    ) {
      addViolation(
        violations,
        'invalid-navigation-policy',
        entry.id,
        `Navigation route '${entry.id}' must be an addressable protected destination.`,
      );
    }

    if (entry.shellNavigation) {
      if (
        entry.redirect !== null ||
        !isAddressable(entry) ||
        entry.access !== 'protected' ||
        entry.parameters.length > 0
      ) {
        addViolation(
          violations,
          'invalid-shell-navigation-policy',
          entry.id,
          `Shell navigation route '${entry.id}' must be a static, addressable protected destination.`,
        );
      }
      const previousOwner = shellNavigationOrders.get(
        entry.shellNavigation.order,
      );
      if (previousOwner) {
        addViolation(
          violations,
          'duplicate-shell-navigation-order',
          entry.id,
          `Shell navigation order '${entry.shellNavigation.order}' duplicates route '${previousOwner}'.`,
        );
      } else {
        shellNavigationOrders.set(entry.shellNavigation.order, entry.id);
      }
    }
  }

  if (rootCount !== 1) {
    addViolation(
      violations,
      'invalid-root',
      ROUTE_IDS.ROOT,
      `The manifest must contain exactly one root; found ${rootCount}.`,
    );
  }
  if (catchAllCount !== 1) {
    addViolation(
      violations,
      'multiple-catch-all',
      ROUTE_IDS.NOT_FOUND,
      `The manifest must contain exactly one catch-all; found ${catchAllCount}.`,
    );
  }

  return Object.freeze(violations);
}

const manifestViolations = validateRouteManifest(ROUTE_MANIFEST);
if (manifestViolations.length > 0) {
  throw new Error(
    `Invalid canonical route manifest: ${manifestViolations
      .map((violation) => `${violation.code}:${violation.routeId}`)
      .join(', ')}`,
  );
}
