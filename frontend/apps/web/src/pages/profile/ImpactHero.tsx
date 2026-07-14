import { Icon, LoadingState, MetricCard, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { enumLabel } from '@/lib/format';
import { usersApi } from '@/api';
import { accountStatusTone, trustBandTone } from './accountVisuals';

/**
 * Impact-first hero for `/profile`: identity plus the four read-only stat metrics.
 */
export function ImpactHero() {
  const { t } = useTranslation(['settings', 'common']);
  const profile = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });
  const stats = useQuery({ queryKey: ['me', 'stats'], queryFn: usersApi.getMyStats });

  const sessionUser = useAuthStore((s) => s.user);
  const roles = useAuthStore((s) => s.roles);
  const status = useAuthStore((s) => s.status);

  const displayName = profile.data?.displayName?.trim() || null;
  const email = profile.data?.email ?? sessionUser?.email ?? null;
  const heading = displayName ?? email ?? t('profile.yourProfile');
  const city = profile.data?.city?.trim() || null;
  const initial = (displayName ?? email ?? '?').charAt(0).toUpperCase();

  return (
    <section className="flex flex-col gap-lg rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-lg shadow-soft">
      <div className="flex flex-wrap items-center gap-md">
        <span className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-primary-container text-headline-md text-on-primary-container">
          {initial}
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="m-0 truncate text-headline-md text-on-surface">{heading}</h2>
          <div className="mt-xs flex flex-wrap items-center gap-sm">
            {city ? (
              <span className="flex items-center gap-xs text-body-md text-on-surface-variant">
                <Icon name="location_on" className="text-[16px] leading-none" />
                {city}
              </span>
            ) : null}
            {status ? (
              <SoftBadge tone={accountStatusTone(status)} icon="account_circle">
                {enumLabel(status, t)}
              </SoftBadge>
            ) : null}
            {roles.map((role) => (
              <SoftBadge key={role} tone="primary" icon="badge">
                {enumLabel(role, t)}
              </SoftBadge>
            ))}
          </div>
        </div>
      </div>

      {stats.isPending ? (
        <LoadingState label={t('impact.loading')} />
      ) : stats.isError ? (
        <FriendlyApiErrorMessage error={stats.error} />
      ) : (
        <div className="grid grid-cols-2 gap-md lg:grid-cols-4">
          <MetricCard label={t('impact.totalPoints')} value={stats.data.totalPoints} icon="stars" />
          <MetricCard label={t('impact.currentLevel')} value={stats.data.currentLevel} icon="military_tech" />
          <MetricCard label={t('impact.trustScore')} value={stats.data.trustScore} icon="verified_user" />
          <MetricCard
            label={t('impact.trustBand')}
            icon="shield"
            value={
              <SoftBadge tone={trustBandTone(stats.data.trustBand)}>
                {enumLabel(stats.data.trustBand, t)}
              </SoftBadge>
            }
          />
        </div>
      )}
    </section>
  );
}
