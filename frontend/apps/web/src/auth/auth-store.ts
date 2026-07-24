import type { User } from '@parkio/types';
import { createStore, type StoreApi } from 'zustand/vanilla';
import { clearPendingProfile } from './pendingProfile';

export type AuthAccountStatus = 'ACTIVE' | 'SUSPENDED' | 'BANNED' | string;
export type AuthRestriction = 'ACCOUNT_NOT_ACTIVE' | 'ACCOUNT_NOT_VERIFIED';
export type AuthLifecycle =
  | 'bootstrapping'
  | 'anonymous'
  | 'authenticated'
  | 'provisioning'
  | 'account-restricted';

interface AnonymousIdentity {
  readonly state: 'anonymous';
  readonly userId: null;
  readonly roles: readonly [];
  readonly accountStatus: null;
  readonly restriction: null;
}

interface SessionIdentity {
  readonly state: 'authenticated' | 'provisioning';
  readonly userId: string;
  readonly roles: readonly string[];
  readonly accountStatus: AuthAccountStatus;
  readonly restriction: null;
}

interface RestrictedIdentity {
  readonly state: 'account-restricted';
  readonly userId: string | null;
  readonly roles: readonly string[];
  readonly accountStatus: AuthAccountStatus | null;
  readonly restriction: AuthRestriction;
}

export type AuthIdentity = AnonymousIdentity | SessionIdentity | RestrictedIdentity;

export interface AuthIdentityChange {
  readonly previous: AuthIdentity;
  readonly current: AuthIdentity;
  readonly sessionEpoch: number;
}

export interface AuthState {
  accessToken: string | null;
  user: User | null;
  roles: string[];
  status: AuthAccountStatus | null;
  lifecycle: AuthLifecycle;
  identity: AuthIdentity;
  restriction: AuthRestriction | null;
  isAuthenticated: boolean;
  /** Compatibility projection for the existing ACCOUNT_NOT_ACTIVE surface. */
  suspended: boolean;
  /** True while user-service is provisioning the post-registration profile. */
  provisioning: boolean;
  /** True until public entry settles or protected entry completes SDK restoration. */
  bootstrapPending: boolean;
  /** Monotonic generation that prevents a late refresh from restoring a cleared session. */
  sessionEpoch: number;
  setSession: (accessToken: string, user: User) => void;
  restoreSession: (sessionEpoch: number, accessToken: string, user: User) => boolean;
  clearSession: () => boolean;
  setUser: (user: User) => void;
  markAccountRestricted: (restriction: AuthRestriction) => void;
  markSuspended: () => void;
  beginProvisioning: () => void;
  endProvisioning: () => void;
  endBootstrap: () => void;
}

export interface AuthStore extends StoreApi<AuthState> {
  subscribeIdentityChanges(listener: (change: AuthIdentityChange) => void): () => void;
}

type IdentityTransitionFields = Partial<AuthState> & Pick<AuthState, 'identity'>;

const ANONYMOUS_IDENTITY: AnonymousIdentity = Object.freeze({
  state: 'anonymous',
  userId: null,
  roles: Object.freeze([]) as readonly [],
  accountStatus: null,
  restriction: null,
});

function accountStatus(user: User): AuthAccountStatus {
  return user.status as AuthAccountStatus;
}

function sessionIdentity(
  state: SessionIdentity['state'],
  user: User,
): SessionIdentity {
  return Object.freeze({
    state,
    userId: user.id,
    roles: Object.freeze([...user.roles]),
    accountStatus: accountStatus(user),
    restriction: null,
  });
}

function restrictedIdentity(
  restriction: AuthRestriction,
  user: User | null,
): RestrictedIdentity {
  return Object.freeze({
    state: 'account-restricted',
    userId: user?.id ?? null,
    roles: Object.freeze([...(user?.roles ?? [])]),
    accountStatus: user ? accountStatus(user) : null,
    restriction,
  });
}

function restrictedForUser(user: User): AuthRestriction | null {
  if (accountStatus(user) === 'ACTIVE') {
    return null;
  }
  return accountStatus(user) === 'PENDING_VERIFICATION'
    ? 'ACCOUNT_NOT_VERIFIED'
    : 'ACCOUNT_NOT_ACTIVE';
}

function sessionFields(
  accessToken: string,
  user: User,
  lifecycle: 'authenticated' | 'provisioning' = 'authenticated',
): IdentityTransitionFields {
  const restriction = restrictedForUser(user);
  if (restriction) {
    return {
      accessToken,
      user,
      roles: [...user.roles],
      status: accountStatus(user),
      lifecycle: 'account-restricted',
      identity: restrictedIdentity(restriction, user),
      restriction,
      isAuthenticated: false,
      suspended: restriction === 'ACCOUNT_NOT_ACTIVE',
      provisioning: false,
      bootstrapPending: false,
    };
  }

  return {
    accessToken,
    user,
    roles: [...user.roles],
    status: accountStatus(user),
    lifecycle,
    identity: sessionIdentity(lifecycle, user),
    restriction: null,
    isAuthenticated: true,
    suspended: false,
    provisioning: lifecycle === 'provisioning',
    bootstrapPending: false,
  };
}

function settledFields(state: AuthState): IdentityTransitionFields {
  if (state.restriction) {
    return {
      lifecycle: 'account-restricted',
      identity: restrictedIdentity(state.restriction, state.user),
      isAuthenticated: false,
      suspended: state.restriction === 'ACCOUNT_NOT_ACTIVE',
      provisioning: false,
      bootstrapPending: false,
    };
  }

  if (state.accessToken && state.user) {
    return sessionFields(
      state.accessToken,
      state.user,
      state.provisioning ? 'provisioning' : 'authenticated',
    );
  }

  return {
    lifecycle: 'anonymous',
    identity: ANONYMOUS_IDENTITY,
    restriction: null,
    isAuthenticated: false,
    suspended: false,
    provisioning: false,
    bootstrapPending: false,
  };
}

function identitiesEqual(left: AuthIdentity, right: AuthIdentity): boolean {
  const leftRoles = [...left.roles].sort();
  const rightRoles = [...right.roles].sort();
  return (
    left.state === right.state &&
    left.userId === right.userId &&
    left.accountStatus === right.accountStatus &&
    left.restriction === right.restriction &&
    leftRoles.length === rightRoles.length &&
    leftRoles.every((role, index) => role === rightRoles[index])
  );
}

/**
 * The session generation is the single stale-result contract for authentication work.
 * Full session replacement and explicit invalidation always advance it; derived identity
 * transitions advance it only when their security-relevant identity fields change.
 */
function withSessionGeneration(
  state: AuthState,
  fields: IdentityTransitionFields,
  forceReplacement = false,
): IdentityTransitionFields {
  if (forceReplacement || !identitiesEqual(state.identity, fields.identity)) {
    return {
      ...fields,
      sessionEpoch: state.sessionEpoch + 1,
    };
  }
  return fields;
}

/** Creates all mutable authentication state for one application runtime. */
export function createAuthStore(): AuthStore {
  const store = createStore<AuthState>()((set) => ({
    accessToken: null,
    user: null,
    roles: [],
    status: null,
    lifecycle: 'bootstrapping',
    identity: ANONYMOUS_IDENTITY,
    restriction: null,
    isAuthenticated: false,
    suspended: false,
    provisioning: false,
    bootstrapPending: true,
    sessionEpoch: 0,

    setSession(accessToken, user) {
      set((state) =>
        withSessionGeneration(state, sessionFields(accessToken, user), true),
      );
    },

    restoreSession(expectedSessionEpoch, accessToken, user) {
      let restored = false;
      set((state) => {
        if (state.sessionEpoch !== expectedSessionEpoch) {
          return state;
        }
        restored = true;
        return withSessionGeneration(state, sessionFields(accessToken, user));
      });
      return restored;
    },

    clearSession() {
      let cleared = false;
      set((state) => {
        const alreadyAnonymous =
          state.lifecycle === 'anonymous' &&
          !state.bootstrapPending &&
          !state.accessToken &&
          !state.user &&
          !state.restriction;
        if (alreadyAnonymous) {
          return state;
        }

        cleared = true;
        return withSessionGeneration(
          state,
          {
            accessToken: null,
            user: null,
            roles: [],
            status: null,
            lifecycle: 'anonymous',
            identity: ANONYMOUS_IDENTITY,
            restriction: null,
            isAuthenticated: false,
            suspended: false,
            provisioning: false,
            bootstrapPending: false,
          },
          true,
        );
      });

      if (cleared) {
        // Remove credentials left by pre-memory-only Web builds. Active credentials
        // are owned by the runtime token adapter and are never written here.
        if (typeof localStorage !== 'undefined') {
          localStorage.removeItem('parkio.accessToken');
          localStorage.removeItem('parkio.refreshToken');
        }
        clearPendingProfile();
      }
      return cleared;
    },

    setUser(user) {
      set((state) => {
        if (!state.accessToken) {
          return {
            user,
            roles: [...user.roles],
            status: accountStatus(user),
          };
        }
        if (state.lifecycle === 'account-restricted' && state.restriction) {
          return withSessionGeneration(state, {
            user,
            roles: [...user.roles],
            status: accountStatus(user),
            identity: restrictedIdentity(state.restriction, user),
          });
        }
        return withSessionGeneration(
          state,
          sessionFields(
            state.accessToken,
            user,
            state.provisioning ? 'provisioning' : 'authenticated',
          ),
        );
      });
    },

    markAccountRestricted(restriction) {
      set((state) => {
        if (state.provisioning) {
          return state;
        }
        return withSessionGeneration(state, {
          lifecycle: 'account-restricted',
          identity: restrictedIdentity(restriction, state.user),
          restriction,
          isAuthenticated: false,
          suspended: restriction === 'ACCOUNT_NOT_ACTIVE',
          provisioning: false,
          bootstrapPending: false,
        });
      });
    },

    markSuspended() {
      set((state) => {
        if (state.provisioning) {
          return state;
        }
        return withSessionGeneration(state, {
          lifecycle: 'account-restricted',
          identity: restrictedIdentity('ACCOUNT_NOT_ACTIVE', state.user),
          restriction: 'ACCOUNT_NOT_ACTIVE',
          isAuthenticated: false,
          suspended: true,
          provisioning: false,
          bootstrapPending: false,
        });
      });
    },

    beginProvisioning() {
      set((state) => {
        if (!state.accessToken || !state.user) {
          return state;
        }
        return withSessionGeneration(
          state,
          sessionFields(state.accessToken, state.user, 'provisioning'),
        );
      });
    },

    endProvisioning() {
      set((state) =>
        withSessionGeneration(
          state,
          settledFields({ ...state, provisioning: false }),
        ),
      );
    },

    endBootstrap() {
      set((state) => {
        if (!state.bootstrapPending && state.lifecycle !== 'bootstrapping') {
          return state;
        }
        return withSessionGeneration(state, settledFields(state));
      });
    },
  })) as AuthStore;

  store.subscribeIdentityChanges = (listener) =>
    store.subscribe((state, previousState) => {
      if (!identitiesEqual(state.identity, previousState.identity)) {
        listener({
          previous: previousState.identity,
          current: state.identity,
          sessionEpoch: state.sessionEpoch,
        });
      }
    });

  return store;
}
