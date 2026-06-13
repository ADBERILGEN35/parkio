import { zodResolver } from '@hookform/resolvers/zod';
import { createIdempotencyKey, isParkioApiError, type ParkioApiError } from '@parkio/api-client';
import {
  MODERATION_REASONS,
  VERIFICATION_RESULTS,
  type LegalStatus,
  type PublicSpot,
} from '@parkio/types';
import {
  Button,
  EmptyState,
  Icon,
  LoadingState,
  PageShell,
  SectionHeader,
  SoftBadge,
  StatusBadge,
  cn,
  getTrustFreshnessVisual,
  type BadgeTone,
} from '@parkio/ui';
import {
  reportSpotFormSchema,
  verifySpotSchema,
  type ReportSpotFormValues,
  type VerifySpotFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { moderationApi, parkingApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SpotMap } from '@/components/map/SpotMap';
import { formatInstant, formatRelativeAgo, formatRemaining, humanizeEnum } from '@/lib/format';

/**
 * Spot Detail Beta (`/spots/:spotId`): image-forward, trust-first layout —
 * summary header above the fold, photo + detail sections in the main column,
 * actions (verify/claim/report) in a sticky right sidebar on desktop. All data
 * comes from the public spot view; PublicSpotResponse exposes no
 * confidence/verification counts, so none are rendered.
 */
export function SpotDetailPage() {
  const { spotId } = useParams<{ spotId: string }>();

  const spotQuery = useQuery({
    queryKey: ['parking', 'spot', spotId],
    queryFn: () => parkingApi.getSpot(spotId as string),
    enabled: Boolean(spotId),
  });

  return (
    <PageShell title="Spot detail">
      <nav className="mb-md">
        <Link
          to="/map"
          className="inline-flex items-center gap-xs text-label-md text-on-surface-variant no-underline transition-colors hover:text-primary"
        >
          <Icon name="arrow_back" className="text-[16px] leading-none" />
          Back to map
        </Link>
      </nav>

      {spotQuery.isPending ? (
        <Section title="Spot">
          <LoadingState />
        </Section>
      ) : spotQuery.isError ? (
        <Section title="Spot">
          {isParkioApiError(spotQuery.error) && spotQuery.error.status === 404 ? (
            <EmptyState
              icon="search_off"
              title="Spot not found"
              description="This spot was not found — it may have expired, been filled, or been removed."
            />
          ) : (
            <FriendlyApiErrorMessage error={spotQuery.error} />
          )}
        </Section>
      ) : (
        <div className="flex flex-col gap-lg">
          <SpotSummaryHeader spot={spotQuery.data} />

          <div className="flex flex-col gap-lg lg:flex-row lg:items-start">
            {/* Main column — photo + detail sections */}
            <div className="flex min-w-0 flex-1 flex-col gap-lg">
              <SpotPhotoSection spotId={spotId as string} />
              <SpotOverviewSection spot={spotQuery.data} />
              <SpotAttributesSection spot={spotQuery.data} />
              <SpotValiditySection spot={spotQuery.data} />
              <Section title="Location map" icon="location_on">
                <div className="overflow-hidden rounded-xl border border-outline-variant">
                  <SpotMap latitude={spotQuery.data.latitude} longitude={spotQuery.data.longitude} />
                </div>
              </Section>
            </div>

            {/* Action sidebar — sticky on desktop, stacked after content on mobile */}
            <aside className="w-full lg:w-[360px] lg:shrink-0">
              <div className="flex flex-col gap-lg lg:sticky lg:top-md">
                <SpotActionsSection spot={spotQuery.data} />
                <ReportSpotSection spot={spotQuery.data} />
              </div>
            </aside>
          </div>
        </div>
      )}
    </PageShell>
  );
}

/** Solid-fill section container (Production Beta: 1px borders, no glass). */
function Section({
  title,
  icon,
  description,
  className,
  children,
}: {
  title: string;
  icon?: string;
  description?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section
      className={cn('rounded-xl border border-outline-variant bg-surface-container-lowest p-md', className)}
    >
      <SectionHeader title={title} icon={icon} description={description} />
      {children}
    </section>
  );
}

const LEGAL_STATUS_TONES: Record<LegalStatus, BadgeTone> = {
  LEGAL: 'success',
  UNCERTAIN: 'warning',
  ILLEGAL_OR_RISKY: 'danger',
};

/** Trust/status summary above the fold: status, freshness, remaining validity. */
function SpotSummaryHeader({ spot }: { spot: PublicSpot }) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  return (
    <header className="rounded-xl border border-outline-variant bg-surface-container-lowest p-md">
      <div className="flex flex-wrap items-center gap-sm">
        <StatusBadge status={spot.status} />
        <span
          className={cn(
            'inline-flex items-center gap-xs text-label-sm font-semibold',
            freshness.className,
          )}
        >
          <Icon name={freshness.icon} className="text-[14px] leading-none" />
          {freshness.label} · updated {formatRelativeAgo(spot.updatedAt)}
        </span>
        <span className="inline-flex items-center gap-xs text-label-sm text-on-surface-variant">
          <Icon name="schedule" className="text-[14px] leading-none" />
          {formatRemaining(spot.expiresAt)}
        </span>
        <SoftBadge tone={LEGAL_STATUS_TONES[spot.legalStatus]}>
          {humanizeEnum(spot.legalStatus)}
        </SoftBadge>
      </div>
      <h2 className="m-0 mt-sm text-headline-md text-on-surface">
        {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
      </h2>
    </header>
  );
}

/**
 * Signed photo URL is fetched on demand after the spot loads and never cached
 * beyond the visit — URLs expire (~5m); "Refresh photo URL" re-requests one.
 * Media access goes through the parking-mediated endpoint only.
 */
function SpotPhotoSection({ spotId }: { spotId: string }) {
  const mediaQuery = useQuery({
    queryKey: ['parking', 'spot', spotId, 'media-access-url'],
    queryFn: () => parkingApi.getSpotMediaAccessUrl(spotId),
    staleTime: 0,
    gcTime: 0,
  });

  return (
    <section className="overflow-hidden rounded-2xl border border-outline-variant bg-surface-container-lowest">
      {mediaQuery.isPending ? (
        <div className="p-md">
          <LoadingState label="Loading photo…" />
        </div>
      ) : mediaQuery.isError ? (
        <div className="flex flex-col gap-sm p-md">
          <EmptyState
            icon="no_photography"
            title="Photo unavailable"
            description="The photo is temporarily unavailable."
            action={
              <Button
                variant="secondary"
                onClick={() => mediaQuery.refetch()}
                disabled={mediaQuery.isFetching}
              >
                {mediaQuery.isFetching ? 'Retrying…' : 'Retry'}
              </Button>
            }
          />
          <FriendlyApiErrorMessage error={mediaQuery.error} />
        </div>
      ) : (
        <>
          <img
            src={mediaQuery.data.accessUrl}
            alt="Parking spot"
            className="aspect-video w-full bg-surface-container object-cover"
          />
          <div className="flex flex-wrap items-center justify-between gap-sm p-md">
            <p className="m-0 text-label-sm text-on-surface-variant">
              Photo URL expires at {formatInstant(mediaQuery.data.expiresAt)}. If the image stops
              loading, refresh it.
            </p>
            <Button
              variant="secondary"
              onClick={() => mediaQuery.refetch()}
              disabled={mediaQuery.isFetching}
            >
              {mediaQuery.isFetching ? 'Refreshing…' : 'Refresh photo URL'}
            </Button>
          </div>
        </>
      )}
    </section>
  );
}

/** Muted label + value row for the overview list. */
function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">{label}</p>
      <p className="m-0 mt-xs text-body-md text-on-surface">{children}</p>
    </div>
  );
}

function SpotOverviewSection({ spot }: { spot: PublicSpot }) {
  return (
    <Section title="Overview" icon="description">
      <div className="flex flex-col gap-md">
        <DetailRow label="Address">{spot.addressText ?? '—'}</DetailRow>
        <DetailRow label="Description">{spot.description ?? '—'}</DetailRow>
        <DetailRow label="Coordinates">
          {spot.latitude}, {spot.longitude}
        </DetailRow>
      </div>
    </Section>
  );
}

/** Parking attributes + vehicle suitability — chips per the design system. */
function SpotAttributesSection({ spot }: { spot: PublicSpot }) {
  return (
    <Section title="Parking attributes" icon="local_parking">
      <div className="flex flex-wrap items-center gap-xs">
        <span className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant">
          {humanizeEnum(spot.parkingContext)}
        </span>
        <SoftBadge tone={LEGAL_STATUS_TONES[spot.legalStatus]}>
          {humanizeEnum(spot.legalStatus)}
        </SoftBadge>
        {spot.manualLocationEdited ? (
          <span className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant">
            Location adjusted manually
          </span>
        ) : null}
        {spot.violationReasons.map((reason) => (
          <SoftBadge key={reason} tone="danger" icon="warning">
            {humanizeEnum(reason)}
          </SoftBadge>
        ))}
      </div>

      <h3 className="m-0 mb-sm mt-lg text-body-md font-semibold text-on-surface">
        Vehicle suitability
      </h3>
      <div className="flex flex-wrap items-center gap-xs">
        {spot.suitableVehicleTypes.length === 0 ? (
          <span className="text-body-md text-on-surface-variant">—</span>
        ) : (
          spot.suitableVehicleTypes.map((type) => (
            <span
              key={type}
              className="inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant"
            >
              <Icon name="directions_car" className="text-[14px] leading-none" />
              {humanizeEnum(type)}
            </span>
          ))
        )}
      </div>
    </Section>
  );
}

/** Compact stat tile for the validity window. */
function ValidityTile({ label, value, caption }: { label: string; value: string; caption: string }) {
  return (
    <div className="rounded-lg border border-outline-variant bg-surface p-sm">
      <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">{label}</p>
      <p className="m-0 mt-xs text-body-lg font-semibold text-on-surface">{value}</p>
      <p className="m-0 mt-xs text-label-sm text-on-surface-variant">{caption}</p>
    </div>
  );
}

function SpotValiditySection({ spot }: { spot: PublicSpot }) {
  return (
    <Section title="Validity window" icon="schedule">
      <div className="grid grid-cols-1 gap-sm sm:grid-cols-3">
        <ValidityTile
          label="Expires"
          value={formatRemaining(spot.expiresAt)}
          caption={formatInstant(spot.expiresAt)}
        />
        <ValidityTile
          label="Last updated"
          value={formatRelativeAgo(spot.updatedAt)}
          caption={formatInstant(spot.updatedAt)}
        />
        <ValidityTile
          label="Created"
          value={formatRelativeAgo(spot.createdAt)}
          caption={formatInstant(spot.createdAt)}
        />
      </div>
      {/* Honest trust signal: PublicSpotResponse has no lastVerifiedAt. */}
      <p className="m-0 mt-sm text-label-sm text-on-surface-variant">
        Freshness reflects the record's last update — the backend doesn't expose lastVerifiedAt
        yet.
      </p>
    </Section>
  );
}

/** Shared select/textarea field styling (matches the Input primitive). */
const FIELD_CLASSES =
  'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm ' +
  'ring-1 ring-outline-variant/40 transition-shadow focus:outline-none focus:ring-2 focus:ring-primary';

/** Statuses where verify/claim can no longer succeed — actions are disabled. */
const TERMINAL_STATUSES: ReadonlyArray<PublicSpot['status']> = ['FILLED', 'EXPIRED', 'REJECTED'];

/**
 * Verify + claim actions. The owner cannot verify/claim their own spot, but the
 * public spot view does not expose the owner — the backend enforces that (403)
 * along with all other business rules; the UI only disables terminal states.
 */
function SpotActionsSection({ spot }: { spot: PublicSpot }) {
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
    <Section title="Community actions" icon="groups">
      <div className="flex flex-col gap-md">
        {terminal ? (
          <p className="m-0 rounded-lg bg-surface-container px-md py-sm text-body-md text-on-surface-variant">
            This spot is {spot.status.toLowerCase()} — it can no longer be verified or claimed.
          </p>
        ) : null}

        {/* Verify availability */}
        <form onSubmit={onVerify}>
          <fieldset disabled={disabled} className="m-0 flex flex-col gap-sm border-0 p-0">
            <h3 className="m-0 text-body-md font-semibold text-on-surface">Verify availability</h3>
            <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
              Verify — what did you observe?
              <select defaultValue="" className={FIELD_CLASSES} {...register('result')}>
                <option value="">Select…</option>
                {VERIFICATION_RESULTS.map((result) => (
                  <option key={result} value={result}>
                    {humanizeEnum(result)}
                  </option>
                ))}
              </select>
            </label>
            {errors.result ? (
              <p className="m-0 text-label-sm text-error">{errors.result.message}</p>
            ) : null}
            <Button type="submit" disabled={disabled} className="w-full">
              {verifyMutation.isPending ? 'Submitting…' : 'Submit verification'}
            </Button>
          </fieldset>
        </form>
        {verifyMutation.isError ? (
          <FriendlyApiErrorMessage error={verifyMutation.error} mapper={mapActionError} />
        ) : null}
        {verifyMutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-body-md font-medium text-secondary">
            <Icon name="check_circle" className="text-[16px] leading-none" />
            Thanks — your report was recorded.
          </p>
        ) : null}

        {/* Claim as filled */}
        <div className="flex flex-col gap-sm border-t border-outline-variant/60 pt-md">
          <h3 className="m-0 text-body-md font-semibold text-on-surface">Claim as filled</h3>
          <p className="m-0 text-label-sm text-on-surface-variant">
            Parked here? Claiming marks the spot as filled for everyone.
          </p>
          <Button
            variant="secondary"
            onClick={() => claimMutation.mutate()}
            disabled={disabled || claimed}
            className="w-full"
          >
            {claimMutation.isPending ? 'Claiming…' : 'Claim this spot'}
          </Button>
        </div>
        {claimMutation.isError ? (
          <FriendlyApiErrorMessage error={claimMutation.error} mapper={mapActionError} />
        ) : null}
        {claimed ? (
          <p className="m-0 flex items-center gap-xs text-body-md font-medium text-secondary">
            <Icon name="check_circle" className="text-[16px] leading-none" />
            Spot claimed — it is now marked as filled.
          </p>
        ) : null}
      </div>
    </Section>
  );
}

/**
 * Report this spot to moderation. Reasons mirror the backend `ModerationReason`
 * enum; the description is optional (max 2000 chars). Duplicate reports for the
 * same reason are rejected by the backend (409 DUPLICATE_REPORT).
 */
function ReportSpotSection({ spot }: { spot: PublicSpot }) {
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ReportSpotFormValues>({
    resolver: zodResolver(reportSpotFormSchema),
    defaultValues: { description: '' },
  });

  const reportMutation = useMutation({
    mutationFn: (values: ReportSpotFormValues) =>
      moderationApi.createReport({
        targetType: 'PARKING_SPOT',
        targetId: spot.id,
        reason: values.reason,
        description: values.description === '' ? null : values.description,
      }),
    onSuccess: async () => {
      reset();
      await queryClient.invalidateQueries({ queryKey: ['reports'] });
    },
  });

  const onSubmit = handleSubmit((values) => reportMutation.mutate(values));

  return (
    <Section
      title="Report issue"
      icon="flag"
      description="Something wrong here? Reports go to moderation."
    >
      <form onSubmit={onSubmit}>
        <fieldset
          disabled={reportMutation.isPending}
          className="m-0 flex flex-col gap-sm border-0 p-0"
        >
          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            What is wrong with this spot?
            <select defaultValue="" className={FIELD_CLASSES} {...register('reason')}>
              <option value="">Select a reason…</option>
              {MODERATION_REASONS.map((reason) => (
                <option key={reason} value={reason}>
                  {humanizeEnum(reason)}
                </option>
              ))}
            </select>
          </label>
          {errors.reason ? (
            <p className="m-0 text-label-sm text-error">{errors.reason.message}</p>
          ) : null}

          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            Details (optional)
            <textarea rows={3} className={cn(FIELD_CLASSES, 'font-sans')} {...register('description')} />
          </label>
          {errors.description ? (
            <p className="m-0 text-label-sm text-error">{errors.description.message}</p>
          ) : null}

          <Button type="submit" variant="destructive-soft" disabled={reportMutation.isPending} className="w-full">
            {reportMutation.isPending ? 'Reporting…' : 'Report this spot'}
          </Button>
        </fieldset>
      </form>
      {reportMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={reportMutation.error} mapper={mapReportError} />
        </div>
      ) : null}
      {reportMutation.isSuccess ? (
        <p className="m-0 mt-sm text-body-md font-medium text-secondary">
          Report submitted — thanks for helping keep Parkio accurate.{' '}
          <Link to="/reports" className="text-primary">
            View my reports
          </Link>
        </p>
      ) : null}
    </Section>
  );
}

/** Friendly wording for expected report failures; null falls back to the raw ApiError. */
function mapReportError(error: ParkioApiError): string | null {
  if (error.status === 409 && error.code === 'DUPLICATE_REPORT') {
    return 'You have already reported this spot for this reason.';
  }
  if (error.status === 404) {
    return 'This spot was not found — it may have expired or been removed.';
  }
  return null;
}

/** Friendly wording for expected verify/claim failures; null falls back to the raw ApiError. */
function mapActionError(error: ParkioApiError): string | null {
  if (error.status === 404) {
    return 'This spot was not found — it may have expired or been removed.';
  }
  if (error.status === 409) {
    return error.code === 'ALREADY_VERIFIED'
      ? 'You have already verified this spot.'
      : 'This spot is no longer available for this action.';
  }
  return null;
}
