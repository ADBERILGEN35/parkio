import { Icon, LoadingState, MetricCard, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { humanizeEnum } from '@/lib/format';
import { usersApi } from '@/api';
import { accountStatusTone, trustBandTone } from './accountVisuals';

/**
 * Impact-first hero for `/profile`: identity (display name, city, status,
 * roles) plus the four read-only stat metrics (points, level, trust score,
 * trust band). Uses only existing backend fields — no streaks, achievements,
 * heatmaps or "helped drivers", which the backend does not expose.
 */
export function ImpactHero() {
  const profile = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });
  const stats = useQuery({ queryKey: ['me', 'stats'], queryFn: usersApi.getMyStats });

  const sessionUser = useAuthStore((s) => s.user);
  const roles = useAuthStore((s) => s.roles);
  const status = useAuthStore((s) => s.status);

  const displayName = profile.data?.displayName?.trim() || null;
  const email = profile.data?.email ?? sessionUser?.email ?? null;
  const heading = displayName ?? email ?? 'Your profile';
  const city = profile.data?.city?.trim() || null;
  const initial = (displayName ?? email ?? '?').charAt(0).toUpperCase();

  return (
    <section className="flex flex-col gap-lg rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-lg shadow-soft">
      {/* Identity */}
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
                {humanizeEnum(status)}
              </SoftBadge>
            ) : null}
            {roles.map((role) => (
              <SoftBadge key={role} tone="primary" icon="badge">
                {humanizeEnum(role)}
              </SoftBadge>
            ))}
          </div>
        </div>
      </div>

      {/* Stats / trust — above the fold */}
      {stats.isPending ? (
        <LoadingState label="Loading your impact…" />
      ) : stats.isError ? (
        <FriendlyApiErrorMessage error={stats.error} />
      ) : (
        <div className="grid grid-cols-2 gap-md lg:grid-cols-4">
          <MetricCard label="Total points" value={stats.data.totalPoints} icon="stars" />
          <MetricCard label="Current level" value={stats.data.currentLevel} icon="military_tech" />
          <MetricCard label="Trust score" value={stats.data.trustScore} icon="verified_user" />
          <MetricCard
            label="Trust band"
            icon="shield"
            value={
              <SoftBadge tone={trustBandTone(stats.data.trustBand)}>
                {humanizeEnum(stats.data.trustBand)}
              </SoftBadge>
            }
          />
        </div>
      )}
    </section>
  );
}
