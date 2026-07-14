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
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { gamificationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { enumLabel, formatRelativeAgo } from '@/lib/format';

/**
 * Your Impact (`/gamification`, labelled "Impact" in the nav). A user-facing
 * impact/rewards view — a level/points hero, recent activity, current benefits
 * and a level roadmap — composed of independent read-only queries via
 * `gamificationApi` (one key per endpoint). Uses existing fields only: no
 * streaks, achievements, heatmaps, rewards or level names are invented.
 */
export function GamificationPage() {
  const { t } = useTranslation('parking');
  return (
    <div className="mx-auto w-full max-w-5xl px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          <Icon name="auto_awesome" className="text-[16px] leading-none" />
          {t('gamification.eyebrow')}
        </p>
        <h1 className="m-0 mt-sm text-headline-lg-mobile text-on-surface md:text-headline-lg">
          {t('gamification.title')}
        </h1>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">
          {t('gamification.description')}
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
  const { t } = useTranslation('parking');
  const query = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });

  return (
    <Surface level="raised" className="rounded-3xl p-lg">
      {query.isPending ? (
        <LoadingState label={t('gamification.loadingProgress')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <LevelHeroContent level={query.data} />
      )}
    </Surface>
  );
}

function LevelHeroContent({ level }: { level: LevelStanding }) {
  const { t } = useTranslation('parking');
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
            {t('gamification.currentLevelLabel')}
          </p>
          <h2 className="m-0 text-headline-md text-on-surface">
            {t('gamification.levelNumber', { level: level.currentLevel })}
          </h2>
          <p className="m-0 mt-xs text-body-md text-on-surface-variant">
            {t('gamification.totalPointsValue', { count: level.totalPoints })}
          </p>
        </div>
        {atMax ? (
          <SoftBadge tone="success" icon="workspace_premium">
            {t('gamification.maxLevelReached')}
          </SoftBadge>
        ) : (
          <SoftBadge tone="primary" icon="trending_up">
            {t('gamification.ptsToLevel', {
              points: level.pointsToNextLevel,
              level: level.currentLevel + 1,
            })}
          </SoftBadge>
        )}
      </div>

      <div className="grid grid-cols-2 gap-md sm:grid-cols-3">
        <MetricCard
          label={t('gamification.totalPoints')}
          value={level.totalPoints}
          icon="stars"
        />
        <MetricCard
          label={t('gamification.currentLevelLabel')}
          value={level.currentLevel}
          icon="military_tech"
        />
        <MetricCard
          label={atMax ? t('gamification.status') : t('gamification.pointsToNext')}
          value={atMax ? t('gamification.topLevel') : (level.pointsToNextLevel as number)}
          icon={atMax ? 'workspace_premium' : 'flag'}
        />
      </div>

      <div>
        <div className="flex items-center justify-between text-label-sm text-on-surface-variant">
          <span>{t('gamification.levelNumber', { level: level.currentLevel })}</span>
          <span>
            {atMax
              ? t('gamification.topLevel')
              : t('gamification.levelNumber', { level: level.currentLevel + 1 })}
          </span>
        </div>
        <div
          className="mt-xs h-2 w-full overflow-hidden rounded-full bg-surface-container-high"
          role="progressbar"
          aria-label={t('gamification.levelProgressAria')}
          aria-valuenow={Math.round(pct)}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
        </div>
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          {atMax
            ? t('gamification.atHighestLevel')
            : t('gamification.pointsTowardNext', {
                current: level.totalPoints,
                next: level.nextLevelMinPoints,
              })}
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
  const { t } = useTranslation('parking');
  const query = useQuery({ queryKey: ['points'], queryFn: gamificationApi.getMyPoints });

  return (
    <Card title={t('gamification.recentActivity')}>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.recentTransactions.length === 0 ? (
        <EmptyState
          icon="receipt_long"
          title={t('gamification.emptyActivityTitle')}
          description={t('gamification.emptyActivityDescription')}
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
  const { t } = useTranslation(['parking', 'common']);
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
          <p className="m-0 text-body-md text-on-surface">
            {enumLabel(entry.sourceType, t, ['pointSource'])}
          </p>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {formatRelativeAgo(entry.createdAt)}
            {entry.relatedSpotId ? (
              <>
                {' · '}
                <Link to={`/spots/${entry.relatedSpotId}`} className="text-primary hover:underline">
                  {t('gamification.viewSpot')}
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
  const { t } = useTranslation('parking');
  const query = useQuery({
    queryKey: ['access-policy'],
    queryFn: gamificationApi.getMyAccessPolicy,
  });

  return (
    <Card title={t('gamification.currentBenefits')}>
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
  const { t } = useTranslation('parking');
  return (
    <div className="flex flex-col gap-md">
      <p className="m-0 text-body-md text-on-surface-variant">
        {t('gamification.benefitsIntro', { level: policy.currentLevel })}
      </p>
      <dl className="m-0 grid grid-cols-1 gap-sm sm:grid-cols-3">
        <BenefitStat
          label={t('gamification.searchRadius')}
          value={t('gamification.radiusMeters', { meters: policy.searchRadiusMeters })}
          icon="my_location"
        />
        <BenefitStat
          label={t('gamification.resultsPerSearch')}
          value={policy.resultLimit}
          icon="format_list_numbered"
        />
        <BenefitStat
          label={t('gamification.dailyViews')}
          value={policy.dailyViewLimit}
          icon="visibility"
        />
      </dl>
      <div className="flex flex-wrap gap-sm">
        <SoftBadge
          tone={policy.verifiedSpotPriority ? 'success' : 'neutral'}
          icon={policy.verifiedSpotPriority ? 'verified' : 'lock'}
        >
          {t('gamification.verifiedSpotPriority')}
        </SoftBadge>
        <SoftBadge
          tone={policy.notificationPriority ? 'success' : 'neutral'}
          icon={policy.notificationPriority ? 'notifications_active' : 'lock'}
        >
          {t('gamification.notificationPriority')}
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
  const { t } = useTranslation('parking');
  const levels = useQuery({ queryKey: ['levels'], queryFn: gamificationApi.getLevels });
  const standing = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });
  const currentLevel = standing.data?.currentLevel ?? null;

  return (
    <Card title={t('gamification.levelRoadmap')}>
      {levels.isPending ? (
        <LoadingState />
      ) : levels.isError ? (
        <FriendlyApiErrorMessage error={levels.error} />
      ) : levels.data.length === 0 ? (
        <EmptyState icon="stairs" title={t('gamification.noLevelsDefined')} />
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
  const { t } = useTranslation('parking');
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
          {t('gamification.levelNumber', { level: rule.level })}
          {current ? (
            <SoftBadge tone="primary" icon="person">
              {t('gamification.you')}
            </SoftBadge>
          ) : locked ? (
            <SoftBadge tone="neutral" icon="lock">
              {t('gamification.locked')}
            </SoftBadge>
          ) : null}
        </span>
        <span className="text-label-sm text-on-surface-variant">
          {rule.minPoints}
          {rule.maxPoints === null
            ? t('gamification.ptsPlus')
            : t('gamification.ptsRange', { max: rule.maxPoints })}
        </span>
      </div>
      <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
        {t('gamification.ruleSummary', {
          radius: rule.searchRadiusMeters,
          results: rule.resultLimit,
          views: rule.dailyViewLimit,
        })}
        {rule.verifiedSpotPriority ? t('gamification.verifiedPrioritySuffix') : ''}
        {rule.notificationPriority ? t('gamification.notificationPrioritySuffix') : ''}
      </p>
    </li>
  );
}
