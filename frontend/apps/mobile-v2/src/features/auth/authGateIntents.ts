import type { TranslationKey } from '@/i18n/translations';

/**
 * Contextual AuthGate intents for anonymous → sign-in membership prompts.
 * Wave 3: facility-detail, municipal-hidden, community-teaser.
 * Wave 4+: contribute.
 */
export type AuthGateIntent =
  | 'facility-detail'
  | 'municipal-hidden'
  | 'community-teaser'
  | 'contribute';

/** Non-sensitive resume metadata (never hide private coordinates/IDs). */
export interface AuthGateResumeMeta {
  intent: AuthGateIntent;
  /** Public municipal facility id only — already visible anonymously. */
  facilityId?: string;
}

let pending: AuthGateResumeMeta | null = null;

export function setPendingAuthGateIntent(meta: AuthGateResumeMeta): void {
  pending = meta;
}

export function peekPendingAuthGateIntent(): AuthGateResumeMeta | null {
  return pending;
}

export function consumePendingAuthGateIntent(): AuthGateResumeMeta | null {
  const next = pending;
  pending = null;
  return next;
}

export function clearPendingAuthGateIntent(): void {
  pending = null;
}

export function authGateTitleKey(intent: AuthGateIntent): TranslationKey {
  switch (intent) {
    case 'facility-detail':
      return 'authGate.facilityDetail.title';
    case 'municipal-hidden':
      return 'authGate.municipalHidden.title';
    case 'community-teaser':
      return 'authGate.community.title';
    case 'contribute':
      return 'authGate.contribute.title';
  }
}

export function authGateBodyKey(intent: AuthGateIntent): TranslationKey {
  switch (intent) {
    case 'facility-detail':
      return 'authGate.facilityDetail.body';
    case 'municipal-hidden':
      return 'authGate.municipalHidden.body';
    case 'community-teaser':
      return 'authGate.community.body';
    case 'contribute':
      return 'authGate.contribute.body';
  }
}
