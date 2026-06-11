import type { LeaderboardEntry } from '@parkio/types';
import { Card, LoadingState, PageShell, colors, radius, spacing } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { gamificationApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { AppNav } from '@/components/AppNav';

export function LeaderboardPage() {
  const query = useQuery({
    queryKey: ['leaderboard'],
    queryFn: () => gamificationApi.getLeaderboard(),
  });

  return (
    <PageShell title="Leaderboard">
      <AppNav />

      <Card title="Top contributors">
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ApiErrorMessage error={query.error} />
        ) : query.data.length === 0 ? (
          <p style={{ margin: 0, color: colors.textMuted }}>No ranked users yet.</p>
        ) : (
          <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
            {query.data.map((entry) => (
              <LeaderboardItem key={entry.userId} entry={entry} />
            ))}
          </ol>
        )}
        <p style={{ margin: `${spacing.md} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
          The leaderboard exposes user ids only — no display names in this response yet.
        </p>
      </Card>
    </PageShell>
  );
}

function LeaderboardItem({ entry }: { entry: LeaderboardEntry }) {
  return (
    <li
      style={{
        padding: spacing.sm,
        border: `1px solid ${colors.border}`,
        borderRadius: radius.md,
        display: 'flex',
        gap: spacing.md,
        alignItems: 'baseline',
      }}
    >
      <strong>#{entry.rank}</strong>
      <span style={{ fontFamily: 'monospace' }} title={entry.userId}>
        {entry.userId.slice(0, 8)}…
      </span>
      <span>{entry.totalPoints} points</span>
      <span style={{ color: colors.textMuted }}>Level {entry.currentLevel}</span>
    </li>
  );
}
