import type { TrustFreshness } from '@parkio/ui';
import type { TFunction } from 'i18next';
import {
  getRejectionPresentation,
  isLegacySystemMigrationRejection,
} from '@/lib/getRejectionPresentation';

/** Localized parking-spot status label (`common:spotStatus.*`). */
export function spotStatusLabel(status: string, t: TFunction): string {
  const key = `spotStatus.${status}`;
  const translated = t(key, { ns: 'common', defaultValue: '' });
  if (translated && translated !== key) return translated;
  return t('spotStatus.UNKNOWN', { ns: 'common' });
}

/**
 * Display-state status label. Domain status may remain {@code REJECTED}; legacy
 * system-migration rows use a distinct label so they do not read as AI rejection.
 */
export function spotDisplayStatusLabel(
  status: string,
  rejection:
    | { code?: string | null; source?: string | null; message?: string | null; moderatorNote?: string | null }
    | null
    | undefined,
  t: TFunction,
): string {
  if (
    status === 'REJECTED' &&
    rejection &&
    isLegacySystemMigrationRejection({
      code: rejection.code ?? '',
      source: rejection.source ?? '',
    })
  ) {
    return (
      getRejectionPresentation({
        status,
        code: rejection.code,
        source: rejection.source,
        serverMessage: rejection.message,
        moderatorNote: rejection.moderatorNote,
        t,
      }).displayStatus ?? t('rejection.displayStatus.systemMigration', { ns: 'parking' })
    );
  }
  return spotStatusLabel(status, t);
}

/** Localized trust-freshness label (`common:freshness.*`). */
export function freshnessLabel(freshness: TrustFreshness, t: TFunction): string {
  return t(`freshness.${freshness}`, { ns: 'common' });
}
