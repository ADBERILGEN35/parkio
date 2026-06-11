import { zodResolver } from '@hookform/resolvers/zod';
import type { NearbySearchParams, PublicSpot } from '@parkio/types';
import { Button, Card, Input, LoadingState, PageShell, colors, radius, spacing } from '@parkio/ui';
import { nearbySearchSchema, type NearbySearchFormValues } from '@parkio/validation';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { parkingApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatInstant } from '@/lib/format';

export function MapPage() {
  const [params, setParams] = useState<NearbySearchParams | null>(null);

  const search = useQuery({
    queryKey: ['parking', 'nearby', params],
    queryFn: () => parkingApi.getNearbySpots(params as NearbySearchParams),
    enabled: params !== null,
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<NearbySearchFormValues>({ resolver: zodResolver(nearbySearchSchema) });

  const onSubmit = handleSubmit((values) => setParams(values));

  return (
    <PageShell title="Map">
      <AppNav />

      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', maxWidth: '40rem' }}>
        <div
          style={{
            padding: spacing.lg,
            borderRadius: radius.md,
            border: `1px dashed ${colors.border}`,
            color: colors.textMuted,
            textAlign: 'center',
          }}
        >
          Map area — no map provider integrated yet. Enter coordinates below.
        </div>

        <Card title="Search nearby">
          <form onSubmit={onSubmit}>
            <fieldset
              disabled={search.isFetching}
              style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}
            >
              <div style={{ display: 'flex', gap: '1rem' }}>
                <Input label="Latitude" inputMode="decimal" error={errors.lat?.message} {...register('lat')} />
                <Input label="Longitude" inputMode="decimal" error={errors.lng?.message} {...register('lng')} />
              </div>
              <div style={{ display: 'flex', gap: '1rem' }}>
                <Input
                  label="Radius (m, default 1000)"
                  inputMode="numeric"
                  error={errors.radius?.message}
                  {...register('radius')}
                />
                <Input
                  label="Limit (default 10)"
                  inputMode="numeric"
                  error={errors.limit?.message}
                  {...register('limit')}
                />
              </div>
              <Button type="submit" disabled={search.isFetching}>
                {search.isFetching ? 'Searching…' : 'Search nearby'}
              </Button>
            </fieldset>
          </form>
        </Card>

        <Card title="Results">
          {params === null ? (
            <p style={{ margin: 0, color: colors.textMuted }}>
              Enter coordinates and search to list nearby spots.
            </p>
          ) : search.isPending ? (
            <LoadingState label="Searching…" />
          ) : search.isError ? (
            <ApiErrorMessage error={search.error} />
          ) : search.data.length === 0 ? (
            <p style={{ margin: 0, color: colors.textMuted }}>No spots found in this area.</p>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
              {search.data.map((spot) => (
                <NearbySpotItem key={spot.id} spot={spot} />
              ))}
            </ul>
          )}
        </Card>
      </div>
    </PageShell>
  );
}

function NearbySpotItem({ spot }: { spot: PublicSpot }) {
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
        Vehicles: {spot.suitableVehicleTypes.join(', ') || '—'} · {spot.parkingContext} · {spot.legalStatus}
      </p>
    </li>
  );
}
