import { type LeaderboardEntry, type PublicProfile } from '@parkio/types';
import {
  EmptyState,
  Icon,
  LeaderboardSkeleton,
  MetricCard,
  SoftBadge,
  Surface,
  cn,
} from '@parkio/ui';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { LeaderboardRow, initialsFor, labelFor } from '@/components/product/LeaderboardRow';

/** "Show more" steps over the existing `limit` param (no real pagination). */
const LIMIT_STEPS = [10, 20, 50, 100] as const;

/** Medal classes for the top-3 ranks (one-off podium colours, design system §1.1). */
const MEDAL_CLASSES: Record<number, string> = {
  1: 'bg-[#FFD700] text-on-surface',
  2: 'bg-[#C0C0C0] text-on-surface',
  3: 'bg-[#CD7F32] text-on-primary',
};

/** Avatar ring per podium rank (1st gets the strongest treatment). */
const PODIUM_RING: Record<number, string> = {
  1: 'ring-4 ring-[#FFD700]',
  2: 'ring-2 ring-[#C0C0C0]',
  3: 'ring-2 ring-[#CD7F32]',
};

/**
 * Leaderboard ("Top Contributors") — P1 Stitch fidelity pass.
 *
 * Built on existing APIs only: `GET /gamification/leaderboard?limit=` (rank,
 * userId, totalPoints, currentLevel) enriched per-row with
 * `GET /users/{userId}/public-profile` (displayName, trustBand) and the caller's
 * own id from `GET /gamification/me/progress`. No weekly/monthly periods, spot
 * counts, rank movement, streaks, pagination or avatars are shown — the backend
 * exposes none of these and nothing is invented.
 */
export function LeaderboardPage() {
  const { gamificationApi, usersApi } = useParkioSdk();
  const { t } = useTranslation('parking');
  const [limitStep, setLimitStep] = useState(0);
  const limit = LIMIT_STEPS[limitStep]!;
  const canShowMore = limitStep < LIMIT_STEPS.length - 1;

  const query = useQuery({
    queryKey: ['leaderboard', limit],
    queryFn: () => gamificationApi.getLeaderboard(limit),
  });

  // Best-effort: match the caller's platform user id (from their own progress)
  // against the visible rows so we can highlight + surface their standing. The
  // leaderboard response itself has no "is me" flag.
  const myProgress = useQuery({ queryKey: ['progress'], queryFn: gamificationApi.getMyProgress });
  const myUserId = myProgress.data?.userId ?? null;

  const entries = query.data ?? [];

  // Enrich each visible row with its public profile (cached per userId). Failures
  // are tolerated — the row falls back to a shortened id and the page keeps rendering.
  const profileQueries = useQueries({
    queries: entries.map((entry) => ({
      queryKey: ['public-profile', entry.userId],
      queryFn: () => usersApi.getPublicProfile(entry.userId),
      staleTime: 5 * 60 * 1000,
      retry: false,
    })),
  });
  const profileByUserId = new Map<string, PublicProfile | null>();
  entries.forEach((entry, index) => {
    profileByUserId.set(entry.userId, profileQueries[index]?.data ?? null);
  });

  const myEntry = myUserId ? entries.find((entry) => entry.userId === myUserId) ?? null : null;
  const podium = entries.slice(0, 3);
  const rest = entries.slice(3);

  return (
    <div className="mx-auto w-full max-w-4xl min-w-0 px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg min-w-0">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          <Icon name="leaderboard" className="text-[16px] leading-none" />
          {t('gamification.eyebrow')}
        </p>
        <h1 className="m-0 mt-sm break-words text-headline-lg-mobile text-on-surface md:text-headline-lg">
          {t('leaderboard.title')}
        </h1>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">
          {t('leaderboard.description')}
        </p>
      </header>

      {query.isPending ? (
        <LeaderboardSkeleton />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : entries.length === 0 ? (
        <EmptyState
          icon="leaderboard"
          title={t('leaderboard.emptyTitle')}
          description={t('leaderboard.emptyDescription')}
        />
      ) : (
        <div className="flex flex-col gap-lg">
          <YourStanding entry={myEntry} hasProgress={myProgress.data != null} />

          <Podium entries={podium} profiles={profileByUserId} myUserId={myUserId} />

          {rest.length > 0 ? (
            <RankingTable rows={rest} profiles={profileByUserId} myUserId={myUserId} />
          ) : null}

          <div className="flex flex-col items-center gap-sm">
            {canShowMore ? (
              <button
                type="button"
                onClick={() => setLimitStep((step) => Math.min(step + 1, LIMIT_STEPS.length - 1))}
                disabled={query.isFetching}
                className="inline-flex items-center gap-xs rounded-full bg-surface-container px-lg py-sm text-label-md font-semibold text-on-surface transition-colors hover:bg-surface-container-high disabled:opacity-60"
              >
                <Icon name="expand_more" className="text-[18px] leading-none" />
                {query.isFetching ? t('notifications.loading') : t('leaderboard.showMore')}
              </button>
            ) : null}
            <p className="m-0 text-label-sm text-on-surface-variant">
              {t('leaderboard.showingTop', { limit })}
            </p>
          </div>
        </div>
      )}

      <p className="m-0 mt-lg text-label-sm text-on-surface-variant">{t('leaderboard.footerNote')}</p>
    </div>
  );
}

/* ------------------------------------------------------------------------- */
/* Your standing                                                             */
/* ------------------------------------------------------------------------- */

function YourStanding({ entry, hasProgress }: { entry: LeaderboardEntry | null; hasProgress: boolean }) {
  const { t } = useTranslation('parking');
  if (entry) {
    return (
      <Surface level="raised" className="rounded-3xl p-lg">
        <p className="m-0 mb-md flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          <Icon name="person_pin_circle" className="text-[18px] leading-none" />
          {t('leaderboard.yourStanding')}
        </p>
        <div className="grid min-w-0 grid-cols-3 gap-sm sm:gap-md">
          <MetricCard dense label={t('leaderboard.rank')} value={`#${entry.rank}`} icon="trophy" />
          <MetricCard dense label={t('leaderboard.points')} value={entry.totalPoints} icon="stars" />
          <MetricCard dense label={t('leaderboard.level')} value={entry.currentLevel} icon="military_tech" />
        </div>
      </Surface>
    );
  }

  // Only make the honest "not ranked" claim once we actually know the caller id.
  if (!hasProgress) return null;

  return (
    <Surface level="flat" className="rounded-2xl p-md">
      <p className="m-0 flex items-center gap-sm text-body-md text-on-surface-variant">
        <Icon name="info" className="text-[18px] leading-none text-primary" />
        {t('leaderboard.notRanked')}
      </p>
    </Surface>
  );
}

/* ------------------------------------------------------------------------- */
/* Podium                                                                     */
/* ------------------------------------------------------------------------- */

function Podium({
  entries,
  profiles,
  myUserId,
}: {
  entries: LeaderboardEntry[];
  profiles: Map<string, PublicProfile | null>;
  myUserId: string | null;
}) {
  const { t } = useTranslation('parking');
  // Display order puts 1st in the centre: [2nd, 1st, 3rd]. With fewer than three
  // entries we keep natural order and still render gracefully.
  const ordered = entries.length === 3 ? [entries[1]!, entries[0]!, entries[2]!] : entries;

  return (
    <section aria-label={t('leaderboard.podium')} className="flex items-end justify-center gap-md sm:gap-lg">
      {ordered.map((entry) => (
        <PodiumCard
          key={entry.userId}
          entry={entry}
          profile={profiles.get(entry.userId) ?? null}
          isMe={entry.userId === myUserId}
        />
      ))}
    </section>
  );
}

function PodiumCard({
  entry,
  profile,
  isMe,
}: {
  entry: LeaderboardEntry;
  profile: PublicProfile | null;
  isMe: boolean;
}) {
  const { t } = useTranslation('parking');
  const label = labelFor(entry.userId, profile);
  const isFirst = entry.rank === 1;
  // Staggered podium heights (1st tallest).
  const padTop = entry.rank === 1 ? 'pt-lg' : entry.rank === 2 ? 'pt-md' : 'pt-sm';
  const avatarSize = isFirst ? 'h-20 w-20 text-title-lg' : 'h-16 w-16 text-title-md';

  return (
    <Surface
      level={isFirst ? 'raised' : 'card'}
      className={cn(
        'relative flex w-full max-w-[10rem] flex-col items-center gap-xs rounded-3xl px-sm pb-md text-center',
        padTop,
        isMe ? 'ring-2 ring-primary' : null,
      )}
    >
      <div className="relative">
        <span
          className={cn(
            'flex shrink-0 items-center justify-center rounded-full bg-primary-container font-bold text-on-primary-container',
            avatarSize,
            PODIUM_RING[entry.rank],
          )}
          aria-hidden
        >
          {initialsFor(label)}
        </span>
        <span
          className={cn(
            'absolute -right-1 -top-1 flex h-7 w-7 items-center justify-center rounded-full text-label-md font-bold shadow-soft',
            MEDAL_CLASSES[entry.rank] ?? 'bg-surface-container-high text-on-surface-variant',
          )}
        >
          {entry.rank}
        </span>
      </div>

      <p className="m-0 mt-xs line-clamp-1 w-full text-body-md font-semibold text-on-surface" title={label}>
        {label}
        {isMe ? (
          <span className="ml-xs text-label-sm font-normal text-primary">{t('leaderboard.you')}</span>
        ) : null}
      </p>

      <p className="m-0 text-headline-md text-primary">{entry.totalPoints}</p>
      <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">
        {t('leaderboard.points')}
      </p>

      <SoftBadge tone="neutral">
        {t('leaderboard.levelLabel', { level: entry.currentLevel })}
      </SoftBadge>
    </Surface>
  );
}

/* ------------------------------------------------------------------------- */
/* Ranking table                                                             */
/* ------------------------------------------------------------------------- */

function RankingTable({
  rows,
  profiles,
  myUserId,
}: {
  rows: LeaderboardEntry[];
  profiles: Map<string, PublicProfile | null>;
  myUserId: string | null;
}) {
  return (
    <Surface level="card" className="overflow-hidden rounded-3xl">
      <ol className="m-0 flex list-none flex-col p-0">
        {rows.map((entry) => (
          <LeaderboardRow
            key={entry.userId}
            entry={entry}
            profile={profiles.get(entry.userId) ?? null}
            isMe={entry.userId === myUserId}
          />
        ))}
      </ol>
    </Surface>
  );
}
