import { Card, LoadingState } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { usersApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';

export function StatsCard() {
  const query = useQuery({ queryKey: ['me', 'stats'], queryFn: usersApi.getMyStats });

  return (
    <Card title="Stats">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : (
        <>
          <p style={{ margin: '0.25rem 0' }}>Total points: {query.data.totalPoints}</p>
          <p style={{ margin: '0.25rem 0' }}>Level: {query.data.currentLevel}</p>
          <p style={{ margin: '0.25rem 0' }}>
            Trust score: {query.data.trustScore} ({query.data.trustBand})
          </p>
          <p style={{ margin: '0.5rem 0 0', fontSize: '0.875rem' }}>
            <Link to="/gamification">View level progress and points history</Link>
          </p>
        </>
      )}
    </Card>
  );
}
