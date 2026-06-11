import { zodResolver } from '@hookform/resolvers/zod';
import { createIdempotencyKey, isParkioApiError } from '@parkio/api-client';
import { VERIFICATION_RESULTS, type PublicSpot } from '@parkio/types';
import { Button, Card, ErrorMessage, LoadingState, PageShell, colors, radius, spacing } from '@parkio/ui';
import { verifySpotSchema, type VerifySpotFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { parkingApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { formatInstant } from '@/lib/format';

export function SpotDetailPage() {
  const { spotId } = useParams<{ spotId: string }>();

  const spotQuery = useQuery({
    queryKey: ['parking', 'spot', spotId],
    queryFn: () => parkingApi.getSpot(spotId as string),
    enabled: Boolean(spotId),
  });

  return (
    <PageShell title="Spot detail">
      <nav style={{ marginBottom: spacing.md, fontSize: '0.875rem' }}>
        <Link to="/map">← Back to map</Link>
      </nav>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', maxWidth: '40rem' }}>
        {spotQuery.isPending ? (
          <Card title="Spot">
            <LoadingState />
          </Card>
        ) : spotQuery.isError ? (
          <Card title="Spot">
            {isParkioApiError(spotQuery.error) && spotQuery.error.status === 404 ? (
              <p style={{ margin: 0, color: colors.textMuted }}>
                This spot was not found — it may have expired, been filled, or been removed.
              </p>
            ) : (
              <ApiErrorMessage error={spotQuery.error} />
            )}
          </Card>
        ) : (
          <>
            <SpotPhotoCard spotId={spotId as string} />
            <SpotDetailsCard spot={spotQuery.data} />
            <SpotActionsCard spot={spotQuery.data} />
          </>
        )}
      </div>
    </PageShell>
  );
}

/**
 * Signed photo URL is fetched on demand after the spot loads and never cached
 * beyond the visit — URLs expire (~5m); "Refresh photo URL" re-requests one.
 */
function SpotPhotoCard({ spotId }: { spotId: string }) {
  const mediaQuery = useQuery({
    queryKey: ['parking', 'spot', spotId, 'media-access-url'],
    queryFn: () => parkingApi.getSpotMediaAccessUrl(spotId),
    staleTime: 0,
    gcTime: 0,
  });

  return (
    <Card title="Photo">
      {mediaQuery.isPending ? (
        <LoadingState label="Loading photo…" />
      ) : mediaQuery.isError ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
          <p style={{ margin: 0, color: colors.textMuted }}>The photo is temporarily unavailable.</p>
          <ApiErrorMessage error={mediaQuery.error} />
          <Button onClick={() => mediaQuery.refetch()} disabled={mediaQuery.isFetching}>
            {mediaQuery.isFetching ? 'Retrying…' : 'Retry'}
          </Button>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
          <img
            src={mediaQuery.data.accessUrl}
            alt="Parking spot"
            style={{ maxWidth: '100%', borderRadius: '0.5rem' }}
          />
          <p style={{ margin: 0, fontSize: '0.875rem', color: colors.textMuted }}>
            Photo URL expires at {formatInstant(mediaQuery.data.expiresAt)}. If the image stops
            loading, refresh it.
          </p>
          <Button onClick={() => mediaQuery.refetch()} disabled={mediaQuery.isFetching}>
            {mediaQuery.isFetching ? 'Refreshing…' : 'Refresh photo URL'}
          </Button>
        </div>
      )}
    </Card>
  );
}

function SpotDetailsCard({ spot }: { spot: PublicSpot }) {
  return (
    <Card title="Details">
      <p style={{ margin: '0.25rem 0' }}>Status: {spot.status}</p>
      <p style={{ margin: '0.25rem 0' }}>Address: {spot.addressText ?? '—'}</p>
      <p style={{ margin: '0.25rem 0' }}>Description: {spot.description ?? '—'}</p>
      <p style={{ margin: '0.25rem 0' }}>
        Location: {spot.latitude}, {spot.longitude}
      </p>
      <p style={{ margin: '0.25rem 0' }}>
        Suitable vehicles: {spot.suitableVehicleTypes.join(', ') || '—'}
      </p>
      <p style={{ margin: '0.25rem 0' }}>Context: {spot.parkingContext}</p>
      <p style={{ margin: '0.25rem 0' }}>Legal status: {spot.legalStatus}</p>
      {spot.violationReasons.length > 0 ? (
        <p style={{ margin: '0.25rem 0' }}>Violation reasons: {spot.violationReasons.join(', ')}</p>
      ) : null}
      <p style={{ margin: '0.25rem 0' }}>Expires: {formatInstant(spot.expiresAt)}</p>
      <p style={{ margin: '0.25rem 0' }}>Created: {formatInstant(spot.createdAt)}</p>
    </Card>
  );
}

/** Statuses where verify/claim can no longer succeed — actions are disabled. */
const TERMINAL_STATUSES: ReadonlyArray<PublicSpot['status']> = ['FILLED', 'EXPIRED', 'REJECTED'];

/**
 * Verify + claim actions. The owner cannot verify/claim their own spot, but the
 * public spot view does not expose the owner — the backend enforces that (403)
 * along with all other business rules; the UI only disables terminal states.
 */
function SpotActionsCard({ spot }: { spot: PublicSpot }) {
  const queryClient = useQueryClient();
  const [claimed, setClaimed] = useState(false);

  const invalidateSpotData = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['parking', 'spot', spot.id] }),
      queryClient.invalidateQueries({ queryKey: ['parking', 'nearby'] }),
      queryClient.invalidateQueries({ queryKey: ['parking', 'my-spots'] }),
    ]);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<VerifySpotFormValues>({ resolver: zodResolver(verifySpotSchema) });

  const verifyMutation = useMutation({
    mutationFn: (values: VerifySpotFormValues) =>
      parkingApi.verifySpot(spot.id, { result: values.result }, createIdempotencyKey()),
    onSuccess: async () => {
      reset();
      await invalidateSpotData();
    },
  });

  const claimMutation = useMutation({
    mutationFn: () => parkingApi.claimSpot(spot.id, createIdempotencyKey()),
    onSuccess: async () => {
      setClaimed(true);
      await invalidateSpotData();
    },
  });

  const pending = verifyMutation.isPending || claimMutation.isPending;
  const terminal = TERMINAL_STATUSES.includes(spot.status);
  const disabled = pending || terminal;

  const onVerify = handleSubmit((values) => verifyMutation.mutate(values));

  return (
    <Card title="Actions">
      <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.md }}>
        {terminal ? (
          <p style={{ margin: 0, color: colors.textMuted }}>
            This spot is {spot.status.toLowerCase()} — it can no longer be verified or claimed.
          </p>
        ) : null}

        <form onSubmit={onVerify}>
          <fieldset
            disabled={disabled}
            style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}
          >
            <label
              style={{ display: 'flex', flexDirection: 'column', gap: spacing.xs, fontSize: '0.875rem' }}
            >
              Verify — what did you observe?
              <select
                defaultValue=""
                style={{
                  padding: spacing.sm,
                  border: `1px solid ${colors.border}`,
                  borderRadius: radius.sm,
                }}
                {...register('result')}
              >
                <option value="">Select…</option>
                {VERIFICATION_RESULTS.map((result) => (
                  <option key={result} value={result}>
                    {result}
                  </option>
                ))}
              </select>
            </label>
            {errors.result ? (
              <p style={{ margin: 0, fontSize: '0.875rem', color: colors.error }}>
                {errors.result.message}
              </p>
            ) : null}
            <Button type="submit" disabled={disabled}>
              {verifyMutation.isPending ? 'Submitting…' : 'Submit verification'}
            </Button>
          </fieldset>
        </form>
        {verifyMutation.isError ? <ActionErrorMessage error={verifyMutation.error} /> : null}
        {verifyMutation.isSuccess ? (
          <p style={{ margin: 0, color: colors.success }}>Thanks — your report was recorded.</p>
        ) : null}

        <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
          <p style={{ margin: 0, fontSize: '0.875rem', color: colors.textMuted }}>
            Parked here? Claiming marks the spot as filled for everyone.
          </p>
          <Button onClick={() => claimMutation.mutate()} disabled={disabled || claimed}>
            {claimMutation.isPending ? 'Claiming…' : 'Claim this spot'}
          </Button>
        </div>
        {claimMutation.isError ? <ActionErrorMessage error={claimMutation.error} /> : null}
        {claimed ? (
          <p style={{ margin: 0, color: colors.success }}>Spot claimed — it is now marked as filled.</p>
        ) : null}
      </div>
    </Card>
  );
}

/** Friendly wording for expected verify/claim failures; falls back to the raw ApiError. */
function ActionErrorMessage({ error }: { error: unknown }) {
  if (isParkioApiError(error)) {
    let friendly: string | null = null;
    if (error.status === 404) {
      friendly = 'This spot was not found — it may have expired or been removed.';
    } else if (error.status === 409) {
      friendly =
        error.code === 'ALREADY_VERIFIED'
          ? 'You have already verified this spot.'
          : 'This spot is no longer available for this action.';
    }
    if (friendly) {
      return <ErrorMessage message={friendly} code={error.code} traceId={error.traceId || undefined} />;
    }
  }
  return <ApiErrorMessage error={error} />;
}
