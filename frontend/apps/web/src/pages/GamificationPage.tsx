import type { LevelRule, PointTransactionEntry } from '@parkio/types';
import { Card, LoadingState, PageShell, colors, radius, spacing } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { gamificationApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatInstant } from '@/lib/format';

export function GamificationPage() {
  return (
    <PageShell title="Progress">
      <AppNav />
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', maxWidth: '40rem' }}>
        <ProgressCard />
        <LevelCard />
        <AccessPolicyCard />
        <PointsCard />
        <LevelsCard />
      </div>
    </PageShell>
  );
}

function ProgressCard() {
  const query = useQuery({ queryKey: ['progress'], queryFn: gamificationApi.getMyProgress });

  return (
    <Card title="Progress">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : (
        <>
          <p style={{ margin: '0.25rem 0' }}>Total points: {query.data.totalPoints}</p>
          <p style={{ margin: '0.25rem 0' }}>Current level: {query.data.currentLevel}</p>
          <p style={{ margin: '0.25rem 0', color: colors.textMuted, fontSize: '0.875rem' }}>
            Last updated: {formatInstant(query.data.updatedAt)}
          </p>
        </>
      )}
    </Card>
  );
}

function LevelCard() {
  const query = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });

  return (
    <Card title="Level standing">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : (
        <>
          <p style={{ margin: '0.25rem 0' }}>
            Level {query.data.currentLevel} (from {query.data.currentLevelMinPoints} points)
          </p>
          {query.data.nextLevelMinPoints === null ? (
            <p style={{ margin: '0.25rem 0', color: colors.textMuted }}>
              You are at the highest level.
            </p>
          ) : (
            <p style={{ margin: '0.25rem 0' }}>
              Next level at {query.data.nextLevelMinPoints} points —{' '}
              {query.data.pointsToNextLevel} points to go.
            </p>
          )}
        </>
      )}
    </Card>
  );
}

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
        <ApiErrorMessage error={query.error} />
      ) : (
        <>
          <p style={{ margin: '0.25rem 0' }}>Search radius: {query.data.searchRadiusMeters} m</p>
          <p style={{ margin: '0.25rem 0' }}>Results per search: {query.data.resultLimit}</p>
          <p style={{ margin: '0.25rem 0' }}>Daily view limit: {query.data.dailyViewLimit}</p>
          <p style={{ margin: '0.25rem 0' }}>
            Verified-spot priority: {query.data.verifiedSpotPriority ? 'yes' : 'no'}
          </p>
          <p style={{ margin: '0.25rem 0' }}>
            Notification priority: {query.data.notificationPriority ? 'yes' : 'no'}
          </p>
        </>
      )}
    </Card>
  );
}

function PointsCard() {
  const query = useQuery({ queryKey: ['points'], queryFn: gamificationApi.getMyPoints });

  return (
    <Card title="Points history">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : query.data.recentTransactions.length === 0 ? (
        <p style={{ margin: 0, color: colors.textMuted }}>
          No point transactions yet — share or verify spots to earn points.
        </p>
      ) : (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
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
    <li
      style={{
        padding: spacing.sm,
        border: `1px solid ${colors.border}`,
        borderRadius: radius.md,
      }}
    >
      <span style={{ color: earned ? colors.success : colors.error }}>
        {earned ? '+' : '−'}
        {entry.points}
      </span>{' '}
      {entry.sourceType}
      <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
        {formatInstant(entry.createdAt)}
        {entry.relatedSpotId ? (
          <>
            {' · '}
            <Link to={`/spots/${entry.relatedSpotId}`}>View spot</Link>
          </>
        ) : null}
      </p>
    </li>
  );
}

function LevelsCard() {
  const query = useQuery({ queryKey: ['levels'], queryFn: gamificationApi.getLevels });

  return (
    <Card title="All levels">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
          {query.data.map((rule) => (
            <LevelRuleItem key={rule.level} rule={rule} />
          ))}
        </ul>
      )}
    </Card>
  );
}

function LevelRuleItem({ rule }: { rule: LevelRule }) {
  return (
    <li
      style={{
        padding: spacing.sm,
        border: `1px solid ${colors.border}`,
        borderRadius: radius.md,
      }}
    >
      <strong>Level {rule.level}</strong> — {rule.minPoints}
      {rule.maxPoints === null ? '+ points' : `–${rule.maxPoints} points`}
      <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
        Radius {rule.searchRadiusMeters} m · {rule.resultLimit} results · {rule.dailyViewLimit}{' '}
        views/day
        {rule.verifiedSpotPriority ? ' · verified-spot priority' : ''}
        {rule.notificationPriority ? ' · notification priority' : ''}
      </p>
    </li>
  );
}
