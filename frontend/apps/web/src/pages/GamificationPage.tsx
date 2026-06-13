import type {
  GamificationAccessPolicy,
  LevelRule,
  LevelStanding,
  PointTransactionEntry,
} from '@parkio/types';
import {
  Card,
  EmptyState,
  Icon,
  LoadingState,
  MetricCard,
  PageShell,
  SoftBadge,
  cn,
} from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { gamificationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatRelativeAgo, humanizeEnum } from '@/lib/format';

export function GamificationPage() {
  return (
    <PageShell title="Progress">
      <AppNav />
      <div className="flex flex-col gap-lg">
        <LevelHero />
        <div className="grid grid-cols-1 gap-lg lg:grid-cols-2 lg:items-start">
          <PointsCard />
          <AccessPolicyCard />
        </div>
        <LevelsCard />
      </div>
    </PageShell>
  );
}

/** Level + progress hero, driven by `GET /gamification/me/level`. */
function LevelHero() {
  const query = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });

  return (
    <section className="rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-lg shadow-soft">
      {query.isPending ? (
        <LoadingState label="Loading your progress…" />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <LevelHeroContent level={query.data} />
      )}
    </section>
  );
}

function LevelHeroContent({ level }: { level: LevelStanding }) {
  const atMax = level.nextLevelMinPoints === null || level.pointsToNextLevel === null;

  // Progress within the current level band (clamped 0–100).
  const span = atMax ? 0 : (level.nextLevelMinPoints as number) - level.currentLevelMinPoints;
  const earnedInBand = level.totalPoints - level.currentLevelMinPoints;
  const pct = atMax || span <= 0 ? 100 : Math.min(100, Math.max(0, (earnedInBand / span) * 100));

  return (
    <div className="flex flex-col gap-md">
      <div className="flex flex-wrap items-center gap-md">
        <span className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-primary-container text-headline-md text-on-primary-container">
          {level.currentLevel}
        </span>
        <div className="min-w-0 flex-1">
          <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">
            Current level
          </p>
          <h2 className="m-0 text-headline-md text-on-surface">Level {level.currentLevel}</h2>
          <p className="m-0 mt-xs text-body-md text-on-surface-variant">
            {level.totalPoints} total points
          </p>
        </div>
        {atMax ? (
          <SoftBadge tone="success" icon="workspace_premium">
            Max level reached
          </SoftBadge>
        ) : (
          <SoftBadge tone="primary" icon="trending_up">
            {level.pointsToNextLevel} pts to level {level.currentLevel + 1}
          </SoftBadge>
        )}
      </div>

      <div>
        <div className="flex items-center justify-between text-label-sm text-on-surface-variant">
          <span>Level {level.currentLevel}</span>
          <span>{atMax ? 'Top level' : `Level ${level.currentLevel + 1}`}</span>
        </div>
        <div
          className="mt-xs h-2 w-full overflow-hidden rounded-full bg-surface-container-high"
          role="progressbar"
          aria-valuenow={Math.round(pct)}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
        </div>
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          {atMax
            ? 'You are at the highest level.'
            : `${level.totalPoints} / ${level.nextLevelMinPoints} points toward the next level.`}
        </p>
      </div>
    </div>
  );
}

/** Total points + recent ledger entries (`GET /gamification/me/points`). */
function PointsCard() {
  const query = useQuery({ queryKey: ['points'], queryFn: gamificationApi.getMyPoints });

  return (
    <Card title="Points">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <div className="flex flex-col gap-md">
          <MetricCard label="Total points" value={query.data.totalPoints} icon="stars" />
          <div>
            <p className="m-0 mb-sm text-label-sm font-semibold uppercase tracking-wider text-on-surface-variant">
              Recent activity
            </p>
            {query.data.recentTransactions.length === 0 ? (
              <EmptyState
                icon="receipt_long"
                title="No point activity yet"
                description="Share or verify spots to start earning points."
              />
            ) : (
              <ul className="m-0 flex list-none flex-col gap-sm p-0">
                {query.data.recentTransactions.map((entry, index) => (
                  <TransactionItem key={index} entry={entry} />
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </Card>
  );
}

function TransactionItem({ entry }: { entry: PointTransactionEntry }) {
  const earned = entry.direction === 'EARNED';
  return (
    <li className="flex items-center justify-between gap-sm rounded-xl border border-outline-variant/40 bg-surface-container-low p-md">
      <div className="min-w-0">
        <p className="m-0 text-body-md text-on-surface">{humanizeEnum(entry.sourceType)}</p>
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          {formatRelativeAgo(entry.createdAt)}
          {entry.relatedSpotId ? (
            <>
              {' · '}
              <Link to={`/spots/${entry.relatedSpotId}`} className="text-primary hover:underline">
                View spot
              </Link>
            </>
          ) : null}
        </p>
      </div>
      <SoftBadge tone={earned ? 'success' : 'danger'} icon={earned ? 'add' : 'remove'}>
        {earned ? '+' : '−'}
        {entry.points}
      </SoftBadge>
    </li>
  );
}

/** Level-based access policy (`GET /gamification/me/access-policy`). */
function AccessPolicyCard() {
  const query = useQuery({
    queryKey: ['access-policy'],
    queryFn: gamificationApi.getMyAccessPolicy,
  });

  return (
    <Card title="Access policy">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <AccessPolicyContent policy={query.data} />
      )}
    </Card>
  );
}

function AccessPolicyContent({ policy }: { policy: GamificationAccessPolicy }) {
  return (
    <div className="flex flex-col gap-md">
      <p className="m-0 text-body-md text-on-surface-variant">
        Perks unlocked by your current level ({policy.currentLevel}).
      </p>
      <dl className="m-0 grid grid-cols-1 gap-sm sm:grid-cols-3">
        <PolicyStat label="Search radius" value={`${policy.searchRadiusMeters} m`} icon="my_location" />
        <PolicyStat label="Results / search" value={policy.resultLimit} icon="format_list_numbered" />
        <PolicyStat label="Daily views" value={policy.dailyViewLimit} icon="visibility" />
      </dl>
      <div className="flex flex-wrap gap-sm">
        <SoftBadge
          tone={policy.verifiedSpotPriority ? 'success' : 'neutral'}
          icon={policy.verifiedSpotPriority ? 'verified' : 'remove'}
        >
          Verified-spot priority
        </SoftBadge>
        <SoftBadge
          tone={policy.notificationPriority ? 'success' : 'neutral'}
          icon={policy.notificationPriority ? 'notifications_active' : 'remove'}
        >
          Notification priority
        </SoftBadge>
      </div>
    </div>
  );
}

function PolicyStat({ label, value, icon }: { label: string; value: string | number; icon: string }) {
  return (
    <div className="rounded-xl border border-outline-variant/40 bg-surface-container-low p-md">
      <dt className="m-0 flex items-center gap-xs text-label-sm text-on-surface-variant">
        <Icon name={icon} className="text-[14px] leading-none" />
        {label}
      </dt>
      <dd className="m-0 mt-xs text-title-lg text-on-surface">{value}</dd>
    </div>
  );
}

/** Full level roadmap (`GET /gamification/levels`) with the current level highlighted. */
function LevelsCard() {
  const levels = useQuery({ queryKey: ['levels'], queryFn: gamificationApi.getLevels });
  const standing = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });

  return (
    <Card title="Level roadmap">
      {levels.isPending ? (
        <LoadingState />
      ) : levels.isError ? (
        <FriendlyApiErrorMessage error={levels.error} />
      ) : levels.data.length === 0 ? (
        <EmptyState icon="stairs" title="No levels defined" />
      ) : (
        <ul className="m-0 flex list-none flex-col gap-sm p-0">
          {levels.data.map((rule) => (
            <LevelRuleItem
              key={rule.level}
              rule={rule}
              current={standing.data?.currentLevel === rule.level}
            />
          ))}
        </ul>
      )}
    </Card>
  );
}

function LevelRuleItem({ rule, current }: { rule: LevelRule; current: boolean }) {
  return (
    <li
      className={cn(
        'rounded-xl border p-md transition-colors duration-std',
        current
          ? 'border-l-4 border-primary bg-primary/5'
          : 'border-outline-variant/40 bg-surface-container-low',
      )}
    >
      <div className="flex items-center justify-between gap-sm">
        <span className="flex items-center gap-sm text-body-md font-semibold text-on-surface">
          <Icon name="military_tech" className="text-[18px] leading-none text-primary" />
          Level {rule.level}
          {current ? (
            <SoftBadge tone="primary" icon="person">
              You
            </SoftBadge>
          ) : null}
        </span>
        <span className="text-label-sm text-on-surface-variant">
          {rule.minPoints}
          {rule.maxPoints === null ? '+ pts' : `–${rule.maxPoints} pts`}
        </span>
      </div>
      <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
        {rule.searchRadiusMeters} m radius · {rule.resultLimit} results · {rule.dailyViewLimit}{' '}
        views/day
        {rule.verifiedSpotPriority ? ' · verified priority' : ''}
        {rule.notificationPriority ? ' · notification priority' : ''}
      </p>
    </li>
  );
}
