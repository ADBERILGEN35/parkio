import { LEADERBOARD_DEFAULT_LIMIT, type LeaderboardEntry } from '@parkio/types';
import {
  Card,
  EmptyState,
  Icon,
  LoadingState,
  PageShell,
  SoftBadge,
  cn,
} from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { gamificationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { AppNav } from '@/components/AppNav';

const LIMIT_OPTIONS = [10, 20, 50, 100] as const;

/** Medal classes for the top-3 ranks (one-off podium colours, design system §1.1). */
const MEDAL_CLASSES: Record<number, string> = {
  1: 'bg-[#FFD700] text-on-surface',
  2: 'bg-[#C0C0C0] text-on-surface',
  3: 'bg-[#CD7F32] text-on-primary',
};

function shortId(userId: string): string {
  return `${userId.slice(0, 8)}…`;
}

export function LeaderboardPage() {
  const [limit, setLimit] = useState<number>(LEADERBOARD_DEFAULT_LIMIT);

  const query = useQuery({
    queryKey: ['leaderboard', limit],
    queryFn: () => gamificationApi.getLeaderboard(limit),
  });

  // Best-effort: match the caller's platform user id (from their own progress)
  // against the visible rows so we can highlight + surface their rank. The
  // leaderboard response itself has no "is me" flag.
  const myProgress = useQuery({ queryKey: ['progress'], queryFn: gamificationApi.getMyProgress });
  const myUserId = myProgress.data?.userId ?? null;
  const myEntry = myUserId ? query.data?.find((e) => e.userId === myUserId) ?? null : null;

  return (
    <PageShell title="Leaderboard">
      <AppNav />

      <Card>
        <div className="mb-md flex flex-wrap items-center justify-between gap-sm">
          <h2 className="m-0 text-title-lg text-on-surface">Top contributors</h2>
          <label className="flex items-center gap-sm text-label-sm text-on-surface-variant">
            Show
            <select
              value={limit}
              onChange={(event) => setLimit(Number(event.target.value))}
              className="rounded-lg border-0 bg-surface px-sm py-xs text-body-md text-on-surface shadow-sm ring-1 ring-outline-variant/40 focus:outline-none focus:ring-2 focus:ring-primary"
              aria-label="Number of contributors to show"
            >
              {LIMIT_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  Top {option}
                </option>
              ))}
            </select>
          </label>
        </div>

        {myEntry ? (
          <div className="mb-md flex items-center gap-sm rounded-xl bg-primary/5 p-md">
            <Icon name="person_pin_circle" className="text-[20px] leading-none text-primary" />
            <span className="text-body-md text-on-surface">
              Your rank: <strong>#{myEntry.rank}</strong> · {myEntry.totalPoints} points · Level{' '}
              {myEntry.currentLevel}
            </span>
          </div>
        ) : null}

        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : query.data.length === 0 ? (
          <EmptyState
            icon="leaderboard"
            title="No ranked users yet"
            description="Share and verify spots to earn points and climb the ranks."
          />
        ) : (
          <ol className="m-0 flex list-none flex-col gap-sm p-0">
            {query.data.map((entry) => (
              <LeaderboardItem key={entry.userId} entry={entry} isMe={entry.userId === myUserId} />
            ))}
          </ol>
        )}

        <p className="m-0 mt-md text-label-sm text-on-surface-variant">
          The leaderboard exposes user ids only — no display names in this response yet, so each
          row shows a shortened id.
        </p>
      </Card>
    </PageShell>
  );
}

function LeaderboardItem({ entry, isMe }: { entry: LeaderboardEntry; isMe: boolean }) {
  const medal = MEDAL_CLASSES[entry.rank];
  return (
    <li
      className={cn(
        'flex items-center gap-md rounded-xl border p-md transition-colors duration-std',
        isMe ? 'border-l-4 border-primary bg-primary/5' : 'border-outline-variant/40 bg-surface-container-low',
      )}
    >
      <span
        className={cn(
          'flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-label-md font-bold',
          medal ?? 'bg-surface-container-high text-on-surface-variant',
        )}
      >
        {entry.rank}
      </span>
      <span className="min-w-0 flex-1 truncate font-mono text-body-md text-on-surface" title={entry.userId}>
        {shortId(entry.userId)}
        {isMe ? <span className="ml-sm font-sans text-label-sm text-primary">(you)</span> : null}
      </span>
      <SoftBadge tone="neutral">Level {entry.currentLevel}</SoftBadge>
      <span className="shrink-0 text-body-md font-semibold text-on-surface">
        {entry.totalPoints} pts
      </span>
    </li>
  );
}
