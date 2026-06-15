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
  SoftBadge,
  Surface,
  cn,
} from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { gamificationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { formatRelativeAgo, humanizeEnum } from '@/lib/format';

/**
 * Your Impact (`/gamification`, labelled "Impact" in the nav). A user-facing
 * impact/rewards view — a level/points hero, recent activity, current benefits
 * and a level roadmap — composed of independent read-only queries via
 * `gamificationApi` (one key per endpoint). Uses existing fields only: no
 * streaks, achievements, heatmaps, rewards or level names are invented.
 */
export function GamificationPage() {
  return (
    <div className="mx-auto w-full max-w-5xl px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          <Icon name="auto_awesome" className="text-[16px] leading-none" />
          Community
        </p>
        <h1 className="m-0 mt-sm text-headline-lg-mobile text-on-surface md:text-headline-lg">
          Your Impact
        </h1>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">
          Track your contributions and unlock more ways to help the community.
        </p>
      </header>

      <div className="flex flex-col gap-lg">
        <LevelHero />
        <div className="grid grid-cols-1 gap-lg lg:grid-cols-2 lg:items-start">
          <RecentActivityCard />
          <BenefitsCard />
        </div>
        <LevelsCard />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------------- */
/* Hero                                                                       */
/* ------------------------------------------------------------------------- */

/** Level + progress hero, driven by `GET /gamification/me/level`. */
function LevelHero() {
  const query = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });

  return (
    <Surface level="raised" className="rounded-3xl p-lg">
      {query.isPending ? (
        <LoadingState label="Loading your progress…" />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <LevelHeroContent level={query.data} />
      )}
    </Surface>
  );
}

function LevelHeroContent({ level }: { level: LevelStanding }) {
  const atMax = level.nextLevelMinPoints === null || level.pointsToNextLevel === null;

  // Progress within the current level band (clamped 0–100).
  const span = atMax ? 0 : (level.nextLevelMinPoints as number) - level.currentLevelMinPoints;
  const earnedInBand = level.totalPoints - level.currentLevelMinPoints;
  const pct = atMax || span <= 0 ? 100 : Math.min(100, Math.max(0, (earnedInBand / span) * 100));

  return (
    <div className="flex flex-col gap-lg">
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

      <div className="grid grid-cols-2 gap-md sm:grid-cols-3">
        <MetricCard label="Total points" value={level.totalPoints} icon="stars" />
        <MetricCard label="Current level" value={level.currentLevel} icon="military_tech" />
        <MetricCard
          label={atMax ? 'Status' : 'Points to next'}
          value={atMax ? 'Top level' : (level.pointsToNextLevel as number)}
          icon={atMax ? 'workspace_premium' : 'flag'}
        />
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

/* ------------------------------------------------------------------------- */
/* Recent activity                                                           */
/* ------------------------------------------------------------------------- */

/** Recent point history (`GET /gamification/me/points`). */
function RecentActivityCard() {
  const query = useQuery({ queryKey: ['points'], queryFn: gamificationApi.getMyPoints });

  return (
    <Card title="Recent activity">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.recentTransactions.length === 0 ? (
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
    </Card>
  );
}

function TransactionItem({ entry }: { entry: PointTransactionEntry }) {
  const earned = entry.direction === 'EARNED';
  return (
    <li className="flex items-center justify-between gap-sm rounded-xl border border-outline-variant/40 bg-surface-container-low p-md">
      <div className="flex min-w-0 items-center gap-sm">
        <span
          className={cn(
            'flex h-9 w-9 shrink-0 items-center justify-center rounded-full',
            earned ? 'bg-secondary/10 text-secondary' : 'bg-error/10 text-error',
          )}
        >
          <Icon name={earned ? 'add_circle' : 'remove_circle'} className="text-[20px] leading-none" />
        </span>
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
      </div>
      <SoftBadge tone={earned ? 'success' : 'danger'}>
        {earned ? '+' : '−'}
        {entry.points}
      </SoftBadge>
    </li>
  );
}

/* ------------------------------------------------------------------------- */
/* Current benefits                                                          */
/* ------------------------------------------------------------------------- */

/** Level-based perks (`GET /gamification/me/access-policy`), presented as benefits. */
function BenefitsCard() {
  const query = useQuery({
    queryKey: ['access-policy'],
    queryFn: gamificationApi.getMyAccessPolicy,
  });

  return (
    <Card title="Your current benefits">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <BenefitsContent policy={query.data} />
      )}
    </Card>
  );
}

function BenefitsContent({ policy }: { policy: GamificationAccessPolicy }) {
  return (
    <div className="flex flex-col gap-md">
      <p className="m-0 text-body-md text-on-surface-variant">
        Perks unlocked at level {policy.currentLevel}. Keep contributing to level up and unlock
        more.
      </p>
      <dl className="m-0 grid grid-cols-1 gap-sm sm:grid-cols-3">
        <BenefitStat
          label="Search radius"
          value={`${policy.searchRadiusMeters} m`}
          icon="my_location"
        />
        <BenefitStat label="Results per search" value={policy.resultLimit} icon="format_list_numbered" />
        <BenefitStat label="Daily views" value={policy.dailyViewLimit} icon="visibility" />
      </dl>
      <div className="flex flex-wrap gap-sm">
        <SoftBadge
          tone={policy.verifiedSpotPriority ? 'success' : 'neutral'}
          icon={policy.verifiedSpotPriority ? 'verified' : 'lock'}
        >
          Verified-spot priority
        </SoftBadge>
        <SoftBadge
          tone={policy.notificationPriority ? 'success' : 'neutral'}
          icon={policy.notificationPriority ? 'notifications_active' : 'lock'}
        >
          Notification priority
        </SoftBadge>
      </div>
    </div>
  );
}

function BenefitStat({ label, value, icon }: { label: string; value: string | number; icon: string }) {
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

/* ------------------------------------------------------------------------- */
/* Level roadmap                                                             */
/* ------------------------------------------------------------------------- */

/** Full level roadmap (`GET /gamification/levels`) with the current level highlighted. */
function LevelsCard() {
  const levels = useQuery({ queryKey: ['levels'], queryFn: gamificationApi.getLevels });
  const standing = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });
  const currentLevel = standing.data?.currentLevel ?? null;

  return (
    <Card title="Level roadmap">
      {levels.isPending ? (
        <LoadingState />
      ) : levels.isError ? (
        <FriendlyApiErrorMessage error={levels.error} />
      ) : levels.data.length === 0 ? (
        <EmptyState icon="stairs" title="No levels defined" />
      ) : (
        <ol className="m-0 flex list-none flex-col gap-sm p-0">
          {levels.data.map((rule) => (
            <LevelRuleItem
              key={rule.level}
              rule={rule}
              current={currentLevel === rule.level}
              completed={currentLevel !== null && rule.level < currentLevel}
            />
          ))}
        </ol>
      )}
    </Card>
  );
}

function LevelRuleItem({
  rule,
  current,
  completed,
}: {
  rule: LevelRule;
  current: boolean;
  completed: boolean;
}) {
  // Future (locked) levels are visually muted; completed levels get a subtle tick.
  const locked = !current && !completed;
  const statusIcon = current ? 'military_tech' : completed ? 'check_circle' : 'lock';
  const statusTone = current ? 'text-primary' : completed ? 'text-secondary' : 'text-on-surface-variant';

  return (
    <li
      className={cn(
        'rounded-xl border p-md transition-colors duration-std',
        current
          ? 'border-l-4 border-primary bg-primary/5'
          : completed
            ? 'border-outline-variant/40 bg-surface-container-low'
            : 'border-outline-variant/30 bg-surface-container-low opacity-70',
      )}
    >
      <div className="flex items-center justify-between gap-sm">
        <span className="flex items-center gap-sm text-body-md font-semibold text-on-surface">
          <Icon name={statusIcon} className={cn('text-[18px] leading-none', statusTone)} />
          Level {rule.level}
          {current ? (
            <SoftBadge tone="primary" icon="person">
              You
            </SoftBadge>
          ) : locked ? (
            <SoftBadge tone="neutral" icon="lock">
              Locked
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
