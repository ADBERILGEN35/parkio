import { zodResolver } from '@hookform/resolvers/zod';
import { createIdempotencyKey, isParkioApiError, type ParkioApiError } from '@parkio/api-client';
import {
  MODERATION_REASONS,
  VERIFICATION_RESULTS,
  type LegalStatus,
  type PublicSpot,
  type Spot,
} from '@parkio/types';
import {
  Button,
  EmptyState,
  Icon,
  LoadingState,
  SoftBadge,
  StatusBadge,
  Surface,
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
import { showError, showSuccess } from '@/lib/toast';

/** Owner-only metrics that may appear on SpotResponse but not PublicSpotResponse. */
type OptionalSpotMetrics = Partial<
  Pick<Spot, 'confidenceScore' | 'verificationCount' | 'filledReportCount'>
>;

function readOptionalMetrics(spot: PublicSpot): OptionalSpotMetrics {
  const raw = spot as PublicSpot & OptionalSpotMetrics;
  return {
    confidenceScore:
      typeof raw.confidenceScore === 'number' ? raw.confidenceScore : undefined,
    verificationCount:
      typeof raw.verificationCount === 'number' ? raw.verificationCount : undefined,
    filledReportCount:
      typeof raw.filledReportCount === 'number' ? raw.filledReportCount : undefined,
  };
}

/**
 * Spot Detail Premium (`/spots/:spotId`): immersive split layout inside AppShell —
 * dominant photo hero + detail column on the left, sticky trust/status + action
 * panel on the right (desktop). Mobile stacks hero → summary → actions → details → map.
 * All data from `GET /parking/spots/{id}`; photo via parking-mediated signed URL only.
 */
export function SpotDetailPage() {
  const { spotId } = useParams<{ spotId: string }>();

  const spotQuery = useQuery({
    queryKey: ['parking', 'spot', spotId],
    queryFn: () => parkingApi.getSpot(spotId as string),
    enabled: Boolean(spotId),
  });

  return (
    <div className="mx-auto w-full max-w-7xl px-md py-lg text-on-background md:px-xl">
      <nav className="mb-lg">
        <Link
          to="/map"
          className="inline-flex items-center gap-xs rounded-full px-sm py-xs text-label-md text-on-surface-variant no-underline transition-colors duration-std hover:bg-surface-container hover:text-primary"
        >
          <Icon name="arrow_back" className="text-[16px] leading-none" />
          Back to map
        </Link>
      </nav>

      {spotQuery.isPending ? (
        <Surface level="raised" className="rounded-3xl p-xl">
          <LoadingState label="Loading spot…" />
        </Surface>
      ) : spotQuery.isError ? (
        <Surface level="raised" className="rounded-3xl p-xl">
          {isParkioApiError(spotQuery.error) && spotQuery.error.status === 404 ? (
            <EmptyState
              icon="search_off"
              title="Spot not found"
              description="This spot was not found — it may have expired, been filled, or been removed."
            />
          ) : (
            <FriendlyApiErrorMessage error={spotQuery.error} />
          )}
        </Surface>
      ) : (
        <div className="flex flex-col gap-lg lg:flex-row lg:items-start">
          {/* `contents` on mobile flattens children for ordering; becomes flex-col on lg */}
          <div className="contents lg:flex lg:min-w-0 lg:flex-1 lg:flex-col lg:gap-lg">
            <SpotPhotoHero spotId={spotId as string} />

            <div className="order-3 flex flex-col gap-lg lg:order-none">
              <SpotOverviewSection spot={spotQuery.data} />
              <SpotAttributesSection spot={spotQuery.data} />
              <CommunitySignalSection spot={spotQuery.data} />
            </div>

            <div className="order-4 lg:order-none">
              <SpotMapSection spot={spotQuery.data} />
            </div>
          </div>

          {/* Mobile order-2: summary + actions between hero and details; desktop sticky right rail */}
          <aside className="order-2 flex w-full shrink-0 flex-col gap-lg lg:order-none lg:w-[400px]">
            <div className="flex flex-col gap-lg lg:sticky lg:top-20">
              <TrustStatusPanel spot={spotQuery.data} />
              <PremiumActionCard spot={spotQuery.data} />
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}

const LEGAL_STATUS_TONES: Record<LegalStatus, BadgeTone> = {
  LEGAL: 'success',
  UNCERTAIN: 'warning',
  ILLEGAL_OR_RISKY: 'danger',
};

/** Premium raised surface wrapper — rounded-3xl + shadow-deep. */
function PremiumSection({
  title,
  icon,
  description,
  children,
  className,
}: {
  title: string;
  icon?: string;
  description?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Surface level="raised" className={cn('rounded-3xl p-lg', className)}>
      <div className="mb-md flex flex-wrap items-start justify-between gap-sm">
        <div className="min-w-0">
          <h2 className="m-0 flex items-center gap-sm text-title-lg text-on-surface">
            {icon ? <Icon name={icon} className="text-primary" /> : null}
            {title}
          </h2>
          {description ? (
            <p className="m-0 mt-xs text-body-md text-on-surface-variant">{description}</p>
          ) : null}
        </div>
      </div>
      {children}
    </Surface>
  );
}

/** Sticky trust/status panel — only fields present on the spot response. */
function TrustStatusPanel({ spot }: { spot: PublicSpot }) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  const metrics = readOptionalMetrics(spot);

  return (
    <Surface level="raised" className="rounded-3xl p-lg shadow-deep">
      <div className="flex flex-wrap items-center gap-sm">
        <StatusBadge status={spot.status} />
        <span
          className={cn(
            'inline-flex items-center gap-xs text-label-sm font-semibold',
            freshness.className,
          )}
        >
          <Icon name={freshness.icon} className="text-[14px] leading-none" />
          {freshness.label}
        </span>
        <SoftBadge tone={LEGAL_STATUS_TONES[spot.legalStatus]}>
          {humanizeEnum(spot.legalStatus)}
        </SoftBadge>
      </div>

      <h2 className="m-0 mt-md text-headline-md text-on-surface">
        {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
      </h2>

      <p className="m-0 mt-xs flex items-center gap-xs text-label-sm text-on-surface-variant">
        <Icon name="schedule" className="text-[14px] leading-none" />
        {formatRemaining(spot.expiresAt)} · updated {formatRelativeAgo(spot.updatedAt)}
      </p>

      <div className="mt-lg grid grid-cols-2 gap-sm">
        <TrustTile label="Expires" value={formatRemaining(spot.expiresAt)} />
        <TrustTile label="Parking context" value={humanizeEnum(spot.parkingContext)} />
        {metrics.confidenceScore !== undefined ? (
          <TrustTile label="Confidence" value={String(metrics.confidenceScore)} />
        ) : null}
        {metrics.verificationCount !== undefined ? (
          <TrustTile label="Verifications" value={String(metrics.verificationCount)} />
        ) : null}
        {metrics.filledReportCount !== undefined ? (
          <TrustTile label="Filled reports" value={String(metrics.filledReportCount)} />
        ) : null}
      </div>

      {spot.suitableVehicleTypes.length > 0 ? (
        <div className="mt-md">
          <p className="m-0 mb-xs text-label-sm uppercase tracking-wider text-on-surface-variant">
            Suitable for
          </p>
          <div className="flex flex-wrap gap-xs">
            {spot.suitableVehicleTypes.map((type) => (
              <span
                key={type}
                className="inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant"
              >
                <Icon name="directions_car" className="text-[14px] leading-none" />
                {humanizeEnum(type)}
              </span>
            ))}
          </div>
        </div>
      ) : null}
    </Surface>
  );
}

function TrustTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl bg-surface-container-low p-sm">
      <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">{label}</p>
      <p className="m-0 mt-xs text-body-md font-semibold text-on-surface">{value}</p>
    </div>
  );
}

/**
 * Dominant photo hero — signed URL via parking-mediated endpoint only.
 * Loading and unavailable states never hide spot details elsewhere on the page.
 */
function SpotPhotoHero({ spotId }: { spotId: string }) {
  const mediaQuery = useQuery({
    queryKey: ['parking', 'spot', spotId, 'media-access-url'],
    queryFn: () => parkingApi.getSpotMediaAccessUrl(spotId),
    staleTime: 0,
    gcTime: 0,
  });

  return (
    <section className="overflow-hidden rounded-3xl shadow-deep ring-1 ring-outline-variant/10">
      {mediaQuery.isPending ? (
        <div className="flex aspect-[4/3] items-center justify-center bg-surface-container-low md:aspect-[16/9]">
          <LoadingState label="Loading photo…" />
        </div>
      ) : mediaQuery.isError ? (
        <div className="flex aspect-[4/3] flex-col items-center justify-center gap-md bg-surface-container-low p-lg md:aspect-[16/9]">
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
            className="aspect-[4/3] w-full bg-surface-container object-cover md:aspect-[16/9]"
          />
          <div className="glass-panel flex flex-wrap items-center justify-between gap-sm border-t border-outline-variant/10 px-lg py-md">
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

function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded-2xl bg-surface-container-low p-md">
      <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">{label}</p>
      <p className="m-0 mt-xs text-body-md text-on-surface">{children}</p>
    </div>
  );
}

function SpotOverviewSection({ spot }: { spot: PublicSpot }) {
  return (
    <PremiumSection title="Overview" icon="description">
      <div className="flex flex-col gap-sm">
        <DetailRow label="Address">{spot.addressText ?? '—'}</DetailRow>
        <DetailRow label="Description">{spot.description ?? '—'}</DetailRow>
        <DetailRow label="Coordinates">
          {spot.latitude}, {spot.longitude}
        </DetailRow>
      </div>
    </PremiumSection>
  );
}

function SpotAttributesSection({ spot }: { spot: PublicSpot }) {
  return (
    <PremiumSection title="Parking attributes" icon="local_parking">
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
    </PremiumSection>
  );
}

/**
 * Compact community signal — not a verification timeline (no history endpoint).
 * Uses only timestamps and optional counts from the spot record.
 */
function CommunitySignalSection({ spot }: { spot: PublicSpot }) {
  const metrics = readOptionalMetrics(spot);

  return (
    <PremiumSection
      title="Community signal"
      icon="groups"
      description="Summary from this spot's record — not a full verification history."
    >
      <div className="flex flex-col gap-sm">
        <SignalRow icon="update" label="Last updated" value={formatInstant(spot.updatedAt)} />
        <SignalRow icon="add_circle" label="Created" value={formatInstant(spot.createdAt)} />
        <SignalRow icon="timer" label="Expires" value={formatInstant(spot.expiresAt)} />
        {metrics.verificationCount !== undefined ? (
          <SignalRow
            icon="verified"
            label="Verification count"
            value={String(metrics.verificationCount)}
          />
        ) : null}
        {metrics.filledReportCount !== undefined ? (
          <SignalRow
            icon="report"
            label="Filled report count"
            value={String(metrics.filledReportCount)}
          />
        ) : null}
      </div>
      <p className="m-0 mt-md text-label-sm text-on-surface-variant">
        Freshness reflects the record's last update — the backend does not expose a verification
        timeline or lastVerifiedAt yet.
      </p>
    </PremiumSection>
  );
}

function SignalRow({ icon, label, value }: { icon: string; label: string; value: string }) {
  return (
    <div className="flex items-center gap-md rounded-2xl bg-surface-container-low px-md py-sm">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface-container-high">
        <Icon name={icon} className="text-[18px] leading-none text-primary" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="m-0 text-label-sm text-on-surface-variant">{label}</p>
        <p className="m-0 text-body-md font-medium text-on-surface">{value}</p>
      </div>
    </div>
  );
}

function SpotMapSection({ spot }: { spot: PublicSpot }) {
  return (
    <PremiumSection
      title="Location"
      icon="location_on"
      description="Spot coordinates on the map — tap markers on the main map to browse nearby."
    >
      <div className="overflow-hidden rounded-3xl shadow-deep ring-1 ring-outline-variant/20">
        <SpotMap latitude={spot.latitude} longitude={spot.longitude} />
      </div>
    </PremiumSection>
  );
}

/** Shared select/textarea field styling (matches the Input primitive). */
const FIELD_CLASSES =
  'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm ' +
  'ring-1 ring-outline-variant/40 transition-shadow focus:outline-none focus:ring-2 focus:ring-primary';

/** Statuses where verify/claim can no longer succeed — actions are disabled. */
const TERMINAL_STATUSES: ReadonlyArray<PublicSpot['status']> = ['FILLED', 'EXPIRED', 'REJECTED'];

/**
 * Premium sticky action card — verify, claim, and report grouped in one raised surface.
 * Owner restrictions stay backend-enforced; UI only disables terminal statuses.
 */
function PremiumActionCard({ spot }: { spot: PublicSpot }) {
  const queryClient = useQueryClient();
  const [claimed, setClaimed] = useState(false);

  const invalidateSpotData = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['parking', 'spot', spot.id] }),
      queryClient.invalidateQueries({ queryKey: ['parking', 'nearby'] }),
      queryClient.invalidateQueries({ queryKey: ['parking', 'my-spots'] }),
    ]);

  const {
    register: registerVerify,
    handleSubmit: handleVerifySubmit,
    reset: resetVerify,
    formState: { errors: verifyErrors },
  } = useForm<VerifySpotFormValues>({ resolver: zodResolver(verifySpotSchema) });

  const verifyMutation = useMutation({
    mutationFn: (values: VerifySpotFormValues) =>
      parkingApi.verifySpot(spot.id, { result: values.result }, createIdempotencyKey()),
    onSuccess: async () => {
      resetVerify();
      await invalidateSpotData();
      showSuccess('Verification submitted.');
    },
    onError: (error) => {
      showError(mapActionError(error as ParkioApiError) ?? 'Could not submit verification.');
    },
  });

  const claimMutation = useMutation({
    mutationFn: () => parkingApi.claimSpot(spot.id, createIdempotencyKey()),
    onSuccess: async () => {
      setClaimed(true);
      await invalidateSpotData();
      showSuccess('Spot claimed as filled.');
    },
    onError: (error) => {
      showError(mapActionError(error as ParkioApiError) ?? 'Could not claim this spot.');
    },
  });

  const {
    register: registerReport,
    handleSubmit: handleReportSubmit,
    reset: resetReport,
    formState: { errors: reportErrors },
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
      resetReport();
      await queryClient.invalidateQueries({ queryKey: ['reports'] });
      showSuccess('Report submitted.');
    },
    onError: (error) => {
      showError(mapReportError(error as ParkioApiError) ?? 'Could not submit report.');
    },
  });

  const pending =
    verifyMutation.isPending || claimMutation.isPending || reportMutation.isPending;
  const terminal = TERMINAL_STATUSES.includes(spot.status);
  const disabled = pending || terminal;

  const onVerify = handleVerifySubmit((values) => verifyMutation.mutate(values));
  const onReport = handleReportSubmit((values) => reportMutation.mutate(values));

  return (
    <Surface level="raised" className="rounded-3xl p-lg shadow-deep">
      <h2 className="m-0 flex items-center gap-sm text-title-lg text-on-surface">
        <Icon name="bolt" className="text-primary" />
        Actions
      </h2>
      <p className="m-0 mt-xs text-body-md text-on-surface-variant">
        Help the community keep this spot accurate.
      </p>

      {terminal ? (
        <p className="m-0 mt-md rounded-2xl bg-surface-container-low px-md py-sm text-body-md text-on-surface-variant">
          This spot is {spot.status.toLowerCase()} — it can no longer be verified or claimed.
        </p>
      ) : null}

      {/* Verify availability */}
      <form onSubmit={onVerify} className="mt-lg">
        <fieldset disabled={disabled} className="m-0 flex flex-col gap-sm border-0 p-0">
          <h3 className="m-0 text-body-md font-semibold text-on-surface">Verify availability</h3>
          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            Verify — what did you observe?
            <select defaultValue="" className={FIELD_CLASSES} {...registerVerify('result')}>
              <option value="">Select…</option>
              {VERIFICATION_RESULTS.map((result) => (
                <option key={result} value={result}>
                  {humanizeEnum(result)}
                </option>
              ))}
            </select>
          </label>
          {verifyErrors.result ? (
            <p className="m-0 text-label-sm text-error">{verifyErrors.result.message}</p>
          ) : null}
          <Button type="submit" disabled={disabled} className="w-full">
            {verifyMutation.isPending ? 'Submitting…' : 'Submit verification'}
          </Button>
        </fieldset>
      </form>
      {verifyMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={verifyMutation.error} mapper={mapActionError} />
        </div>
      ) : null}
      {verifyMutation.isSuccess ? (
        <p className="m-0 mt-sm flex items-center gap-xs text-body-md font-medium text-secondary">
          <Icon name="check_circle" className="text-[16px] leading-none" />
          Thanks — your report was recorded.
        </p>
      ) : null}

      {/* Claim as filled */}
      <div className="mt-lg flex flex-col gap-sm border-t border-outline-variant/30 pt-lg">
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

      {/* Report issue */}
      <form onSubmit={onReport} className="mt-lg border-t border-outline-variant/30 pt-lg">
        <fieldset
          disabled={reportMutation.isPending}
          className="m-0 flex flex-col gap-sm border-0 p-0"
        >
          <h3 className="m-0 text-body-md font-semibold text-on-surface">Report issue</h3>
          <p className="m-0 text-label-sm text-on-surface-variant">
            Something wrong here? Reports go to moderation.
          </p>
          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            What is wrong with this spot?
            <select defaultValue="" className={FIELD_CLASSES} {...registerReport('reason')}>
              <option value="">Select a reason…</option>
              {MODERATION_REASONS.map((reason) => (
                <option key={reason} value={reason}>
                  {humanizeEnum(reason)}
                </option>
              ))}
            </select>
          </label>
          {reportErrors.reason ? (
            <p className="m-0 text-label-sm text-error">{reportErrors.reason.message}</p>
          ) : null}

          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            Details (optional)
            <textarea
              rows={3}
              className={cn(FIELD_CLASSES, 'font-sans')}
              {...registerReport('description')}
            />
          </label>
          {reportErrors.description ? (
            <p className="m-0 text-label-sm text-error">{reportErrors.description.message}</p>
          ) : null}

          <Button
            type="submit"
            variant="destructive-soft"
            disabled={reportMutation.isPending}
            className="w-full"
          >
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
    </Surface>
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
