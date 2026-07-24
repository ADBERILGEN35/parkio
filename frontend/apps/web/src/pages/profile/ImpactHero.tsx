import { Icon, LoadingState, MetricCard, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { enumLabel } from '@/lib/format';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { accountStatusTone, trustBandTone } from './accountVisuals';

/**
 * Impact-first hero for `/profile`: identity plus the four read-only stat metrics.
 */
export function ImpactHero() {
  const { usersApi } = useParkioSdk();
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
    <section className="flex min-w-0 flex-col gap-lg rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-md shadow-soft sm:p-lg">
      <div className="flex min-w-0 items-start gap-md">
        <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-primary-container text-headline-md text-on-primary-container sm:h-16 sm:w-16">
          {initial}
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="m-0 break-words text-headline-md text-on-surface [overflow-wrap:anywhere]" title={heading}>
            {heading}
          </h2>
          <div className="mt-xs flex min-w-0 flex-wrap items-center gap-sm">
            {city ? (
              <span className="flex min-w-0 max-w-full items-center gap-xs text-body-md text-on-surface-variant">
                <Icon name="location_on" className="shrink-0 text-[16px] leading-none" />
                <span className="truncate">{city}</span>
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
        <div className="grid min-w-0 grid-cols-2 gap-sm sm:gap-md lg:grid-cols-4">
          <MetricCard dense label={t('impact.totalPoints')} value={stats.data.totalPoints} icon="stars" />
          <MetricCard dense label={t('impact.currentLevel')} value={stats.data.currentLevel} icon="military_tech" />
          <MetricCard dense label={t('impact.trustScore')} value={stats.data.trustScore} icon="verified_user" />
          <MetricCard
            dense
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
