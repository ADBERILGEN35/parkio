import type { TrustFreshness } from '@parkio/ui';
import type { TFunction } from 'i18next';

/** Localized parking-spot status label (`common:spotStatus.*`). */
export function spotStatusLabel(status: string, t: TFunction): string {
  const key = `spotStatus.${status}`;
  const translated = t(key, { ns: 'common', defaultValue: '' });
  if (translated && translated !== key) return translated;
  return t('spotStatus.UNKNOWN', { ns: 'common' });
}

/** Localized trust-freshness label (`common:freshness.*`). */
export function freshnessLabel(freshness: TrustFreshness, t: TFunction): string {
  return t(`freshness.${freshness}`, { ns: 'common' });
}