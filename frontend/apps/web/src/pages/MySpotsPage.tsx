import type { Spot } from '@parkio/types';
import { Card, LoadingState, PageShell, colors, radius, spacing } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { parkingApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatInstant } from '@/lib/format';

export function MySpotsPage() {
  const query = useQuery({ queryKey: ['parking', 'my-spots'], queryFn: parkingApi.getMySpots });

  return (
    <PageShell title="My spots">
      <AppNav />

      <Card title="Spots I shared">
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ApiErrorMessage error={query.error} />
        ) : query.data.length === 0 ? (
          <p style={{ margin: 0, color: colors.textMuted }}>You haven't shared any spots yet.</p>
        ) : (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
            {query.data.map((spot) => (
              <MySpotItem key={spot.id} spot={spot} />
            ))}
          </ul>
        )}
      </Card>
    </PageShell>
  );
}

function MySpotItem({ spot }: { spot: Spot }) {
  return (
    <li
      style={{
        padding: spacing.sm,
        border: `1px solid ${colors.border}`,
        borderRadius: radius.md,
      }}
    >
      <Link to={`/spots/${spot.id}`}>{spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}</Link>
      <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
        Status: {spot.status} · Expires: {formatInstant(spot.expiresAt)}
      </p>
      <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
        Verifications: {spot.verificationCount} · Confidence: {spot.confidenceScore}
      </p>
    </li>
  );
}
