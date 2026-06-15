import { Card, Icon, LoadingState, MetricCard, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { humanizeEnum } from '@/lib/format';
import { trustBandTone } from './accountVisuals';

/**
 * Read-only "Trust & progress" section: the four gamification/trust metrics from
 * `GET /users/me/stats` plus a link to the full gamification view. Streaks,
 * achievements and activity heatmaps are intentionally absent — the backend
 * exposes none of them, so nothing is invented (only an honest note is shown).
 */
export function TrustProgressCard() {
  const stats = useQuery({ queryKey: ['me', 'stats'], queryFn: usersApi.getMyStats });

  return (
    <Card title="Trust & progress">
      {stats.isPending ? (
        <LoadingState label="Loading your progress…" />
      ) : stats.isError ? (
        <FriendlyApiErrorMessage error={stats.error} />
      ) : (
        <div className="flex flex-col gap-md">
          <div className="grid grid-cols-2 gap-md">
            <MetricCard label="Total points" value={stats.data.totalPoints} icon="stars" />
            <MetricCard
              label="Current level"
              value={stats.data.currentLevel}
              icon="military_tech"
            />
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

          <Link
            to="/gamification"
            className="inline-flex items-center gap-xs self-start text-label-md font-semibold text-primary hover:underline"
          >
            <Icon name="trending_up" className="text-[18px] leading-none" />
            View level progress and points history
          </Link>

          <p className="m-0 text-label-sm text-on-surface-variant">
            Streaks, achievements and activity heatmaps aren&apos;t available yet — only lifetime
            points, level and trust are tracked today.
          </p>
        </div>
      )}
    </Card>
  );
}
