import { zodResolver } from '@hookform/resolvers/zod';
import { isParkioApiError, type ParkioApiError } from '@parkio/api-client';
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
  SkeletonBlock,
  SoftBadge,
  SpotDetailSkeleton,
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
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SpotMap } from '@/components/map/SpotMap';
import {
  useSpotDetailQuery,
  useSpotMediaAccessUrlQuery,
} from '@/data/hooks/useParkingQueries';
import {
  useClaimSpotMutation,
  useReportSpotMutation,
  useVerifySpotMutation,
} from '@/data/hooks/useParkingMutations';
import { mapParkingActionError, mapParkingReportError } from '@/data/parking/errors';
import { enumLabel, formatInstant, formatRelativeAgo, formatRemaining } from '@/lib/format';
import { freshnessLabel, spotStatusLabel } from '@/lib/localized-status';
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
  const { t } = useTranslation('parking');
  const { spotId } = useParams<{ spotId: string }>();

  const spotQuery = useSpotDetailQuery(spotId ?? '');

  return (
    <div className="mx-auto w-full max-w-7xl px-md py-lg text-on-background md:px-xl">
      <nav className="mb-lg">
        <Link
          to="/map"
          className="inline-flex items-center gap-xs rounded-full px-sm py-xs text-label-md text-on-surface-variant no-underline transition-colors duration-std hover:bg-surface-container hover:text-primary"
        >
          <Icon name="arrow_back" className="text-[16px] leading-none" />
          {t('spotDetail.backToMap')}
        </Link>
      </nav>

      {spotQuery.isPending ? (
        <SpotDetailSkeleton />
      ) : spotQuery.isError ? (
        <Surface level="raised" className="rounded-3xl p-xl">
          {isParkioApiError(spotQuery.error) && spotQuery.error.status === 404 ? (
            <EmptyState
              icon="search_off"
              title={t('spotDetail.notFound')}
              description={t('spotDetail.notFound')}
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
  const { t } = useTranslation('parking');
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  const metrics = readOptionalMetrics(spot);

  return (
    <Surface level="raised" className="rounded-3xl p-lg shadow-deep">
      <div className="flex flex-wrap items-center gap-sm">
        <StatusBadge status={spot.status} label={spotStatusLabel(spot.status, t)} />
        <span
          className={cn(
            'inline-flex items-center gap-xs text-label-sm font-semibold',
            freshness.className,
          )}
        >
          <Icon name={freshness.icon} className="text-[14px] leading-none" />
          {freshnessLabel(freshness.freshness, t)}
        </span>
        <SoftBadge tone={LEGAL_STATUS_TONES[spot.legalStatus]}>
          {enumLabel(spot.legalStatus, t, ['legalStatus'])}
        </SoftBadge>
      </div>

      <h2 className="m-0 mt-md text-headline-md text-on-surface">
        {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
      </h2>

      <p className="m-0 mt-xs flex items-center gap-xs text-label-sm text-on-surface-variant">
        <Icon name="schedule" className="text-[14px] leading-none" />
        {formatRemaining(spot.expiresAt)} ·{' '}
        {t('spotDetail.updatedAgo', { time: formatRelativeAgo(spot.updatedAt) })}
      </p>

      <div className="mt-lg grid grid-cols-2 gap-sm">
        <TrustTile label={t('spotDetail.expires')} value={formatRemaining(spot.expiresAt)} />
        <TrustTile
          label={t('spotDetail.parkingContext')}
          value={enumLabel(spot.parkingContext, t, ['parkingContext'])}
        />
        {metrics.confidenceScore !== undefined ? (
          <TrustTile label={t('spotDetail.confidence')} value={String(metrics.confidenceScore)} />
        ) : null}
        {metrics.verificationCount !== undefined ? (
          <TrustTile
            label={t('spotDetail.verificationsLabel')}
            value={String(metrics.verificationCount)}
          />
        ) : null}
        {metrics.filledReportCount !== undefined ? (
          <TrustTile
            label={t('spotDetail.filledReportsLabel')}
            value={String(metrics.filledReportCount)}
          />
        ) : null}
      </div>

      {spot.suitableVehicleTypes.length > 0 ? (
        <div className="mt-md">
          <p className="m-0 mb-xs text-label-sm uppercase tracking-wider text-on-surface-variant">
            {t('spotDetail.suitableFor')}
          </p>
          <div className="flex flex-wrap gap-xs">
            {spot.suitableVehicleTypes.map((type) => (
              <span
                key={type}
                className="inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant"
              >
                <Icon name="directions_car" className="text-[14px] leading-none" />
                {enumLabel(type, t)}
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
  const { t } = useTranslation('parking');
  const mediaQuery = useSpotMediaAccessUrlQuery(spotId);

  return (
    <section className="overflow-hidden rounded-3xl shadow-deep ring-1 ring-outline-variant/10">
      {mediaQuery.isPending ? (
        <div className="aspect-[4/3] bg-surface-container-low md:aspect-[16/9]" role="status" aria-label={t("spotDetail.loadingPhoto")}>
          <SkeletonBlock className="h-full w-full" rounded="sm" />
        </div>
      ) : mediaQuery.isError ? (
        <div className="flex aspect-[4/3] flex-col items-center justify-center gap-md bg-surface-container-low p-lg md:aspect-[16/9]">
          <EmptyState
            icon="no_photography"
            title={t("spotDetail.photoUnavailable")}
            description={t("spotDetail.photoUnavailableDesc")}
            action={
              <Button
                variant="secondary"
                onClick={() => mediaQuery.refetch()}
                disabled={mediaQuery.isFetching}
              >
                {mediaQuery.isFetching ? t('spotDetail.retrying') : t('spotDetail.retry')}
              </Button>
            }
          />
        </div>
      ) : (
        <>
          <img
            src={mediaQuery.data.accessUrl}
            alt={t("spotDetail.photoAlt")}
            className="aspect-[4/3] w-full bg-surface-container object-cover md:aspect-[16/9]"
          />
          <div className="glass-panel flex flex-wrap items-center justify-between gap-sm border-t border-outline-variant/10 px-lg py-md">
            <p className="m-0 text-label-sm text-on-surface-variant">
              {t('spotDetail.photoExpires', { time: formatInstant(mediaQuery.data.expiresAt) })}
            </p>
            <Button
              variant="secondary"
              onClick={() => mediaQuery.refetch()}
              disabled={mediaQuery.isFetching}
            >
              {mediaQuery.isFetching ? t('spotDetail.refreshing') : t('spotDetail.refreshPhoto')}
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
  const { t } = useTranslation('parking');
  return (
    <PremiumSection title={t("spotDetail.overview")} icon="description">
      <div className="flex flex-col gap-sm">
        <DetailRow label={t("spotDetail.address")}>{spot.addressText ?? '—'}</DetailRow>
        <DetailRow label={t("spotDetail.description")}>{spot.description ?? '—'}</DetailRow>
        <DetailRow label={t("spotDetail.coordinates")}>
          {spot.latitude}, {spot.longitude}
        </DetailRow>
      </div>
    </PremiumSection>
  );
}

function SpotAttributesSection({ spot }: { spot: PublicSpot }) {
  const { t } = useTranslation('parking');
  return (
    <PremiumSection title={t("spotDetail.parkingAttributes")} icon="local_parking">
      <div className="flex flex-wrap items-center gap-xs">
        <span className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant">
          {enumLabel(spot.parkingContext, t, ['parkingContext'])}
        </span>
        <SoftBadge tone={LEGAL_STATUS_TONES[spot.legalStatus]}>
          {enumLabel(spot.legalStatus, t, ['legalStatus'])}
        </SoftBadge>
        {spot.manualLocationEdited ? (
          <span className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant">
            {t('spotDetail.locationAdjusted')}
          </span>
        ) : null}
        {spot.violationReasons.map((reason) => (
          <SoftBadge key={reason} tone="danger" icon="warning">
            {enumLabel(reason, t, ['violationReason'])}
          </SoftBadge>
        ))}
      </div>

      <h3 className="m-0 mb-sm mt-lg text-body-md font-semibold text-on-surface">
        {t('spotDetail.vehicleSuitability')}
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
              {enumLabel(type, t)}
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
  const { t } = useTranslation('parking');
  const metrics = readOptionalMetrics(spot);

  return (
    <PremiumSection
      title={t("spotDetail.communitySignal")}
      icon="groups"
      description={t("spotDetail.communitySignalDesc")}
    >
      <div className="flex flex-col gap-sm">
        <SignalRow icon="update" label={t("spotDetail.lastUpdated")} value={formatInstant(spot.updatedAt)} />
        <SignalRow icon="add_circle" label={t("spotDetail.created")} value={formatInstant(spot.createdAt)} />
        <SignalRow icon="timer" label={t("spotDetail.expires")} value={formatInstant(spot.expiresAt)} />
        {metrics.verificationCount !== undefined ? (
          <SignalRow
            icon="verified"
            label={t("spotDetail.verificationCount")}
            value={String(metrics.verificationCount)}
          />
        ) : null}
        {metrics.filledReportCount !== undefined ? (
          <SignalRow
            icon="report"
            label={t("spotDetail.filledReportCount")}
            value={String(metrics.filledReportCount)}
          />
        ) : null}
      </div>
      <p className="m-0 mt-md text-label-sm text-on-surface-variant">
        {t('spotDetail.freshnessNote')}
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
  const { t } = useTranslation('parking');
  return (
    <PremiumSection
      title={t("spotDetail.location")}
      icon="location_on"
      description={t("spotDetail.locationDesc")}
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
  const { t } = useTranslation(['parking', 'common']);
  const [claimed, setClaimed] = useState(false);
  // Claiming flips the spot to FILLED for *every* user and can't be undone, so we
  // gate it behind an explicit in-place confirmation rather than a single tap.
  const [confirmingClaim, setConfirmingClaim] = useState(false);

  const {
    register: registerVerify,
    handleSubmit: handleVerifySubmit,
    reset: resetVerify,
    formState: { errors: verifyErrors },
  } = useForm<VerifySpotFormValues>({ resolver: zodResolver(verifySpotSchema) });

  const verifyMutation = useVerifySpotMutation(spot.id);
  const claimMutation = useClaimSpotMutation(spot.id);
  const {
    register: registerReport,
    handleSubmit: handleReportSubmit,
    reset: resetReport,
    formState: { errors: reportErrors },
  } = useForm<ReportSpotFormValues>({
    resolver: zodResolver(reportSpotFormSchema),
    defaultValues: { description: '' },
  });
  const reportMutation = useReportSpotMutation(spot.id);

  const pending =
    verifyMutation.isPending || claimMutation.isPending || reportMutation.isPending;
  const terminal = TERMINAL_STATUSES.includes(spot.status);
  const disabled = pending || terminal;

  const onVerify = handleVerifySubmit((values) => {
    verifyMutation.mutate(values, {
      onSuccess: () => {
        resetVerify();
        showSuccess(t('spotDetail.verifySubmitted'));
      },
      onError: (error) => {
        showError(mapParkingActionError(error as ParkioApiError) ?? t('spotDetail.verifyError'));
      },
    });
  });

  const onClaim = () => {
    claimMutation.mutate(undefined, {
      onSuccess: () => {
        setClaimed(true);
        showSuccess(t('spotDetail.claimSuccess'));
      },
      onError: (error) => {
        showError(mapParkingActionError(error as ParkioApiError) ?? t('spotDetail.claimError'));
      },
    });
  };

  const onReport = handleReportSubmit((values) => {
    reportMutation.mutate(values, {
      onSuccess: () => {
        resetReport();
        showSuccess(t('spotDetail.reportSubmitted'));
      },
      onError: (error) => {
        showError(mapParkingReportError(error as ParkioApiError) ?? t('spotDetail.reportError'));
      },
    });
  });

  return (
    <Surface level="raised" className="rounded-3xl p-lg shadow-deep">
      <h2 className="m-0 flex items-center gap-sm text-title-lg text-on-surface">
        <Icon name="bolt" className="text-primary" />
        {t('spotDetail.actions')}
      </h2>
      <p className="m-0 mt-xs text-body-md text-on-surface-variant">
        {t('spotDetail.actionsHint')}
      </p>

      {terminal ? (
        <p className="m-0 mt-md rounded-2xl bg-surface-container-low px-md py-sm text-body-md text-on-surface-variant">
          {t('spotDetail.terminalStatus', { status: spot.status.toLowerCase() })}
        </p>
      ) : null}

      {/* Verify availability */}
      <form onSubmit={onVerify} className="mt-lg">
        <fieldset disabled={disabled} className="m-0 flex flex-col gap-sm border-0 p-0">
          <h3 className="m-0 text-body-md font-semibold text-on-surface">{t('spotDetail.verify')}</h3>
          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            {t('spotDetail.verifyObserve')}
            <select defaultValue="" className={FIELD_CLASSES} {...registerVerify('result')}>
              <option value="">{t('spotDetail.select')}</option>
              {VERIFICATION_RESULTS.map((result) => (
                <option key={result} value={result}>
                  {enumLabel(result, t, ['verificationResult'])}
                </option>
              ))}
            </select>
          </label>
          {verifyErrors.result ? (
            <p className="m-0 text-label-sm text-error">{verifyErrors.result.message}</p>
          ) : null}
          <Button type="submit" disabled={disabled} className="w-full">
{verifyMutation.isPending ? t('spotDetail.submitting') : t('spotDetail.submitVerification')}
          </Button>
        </fieldset>
      </form>
      {verifyMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={verifyMutation.error} mapper={mapParkingActionError} />
        </div>
      ) : null}
      {verifyMutation.isSuccess ? (
        <p className="m-0 mt-sm flex items-center gap-xs text-body-md font-medium text-secondary">
          <Icon name="check_circle" className="text-[16px] leading-none" />
          {t('spotDetail.verifyThanks')}
        </p>
      ) : null}

      {/* Claim as filled */}
      <div className="mt-lg flex flex-col gap-sm border-t border-outline-variant/30 pt-lg">
        <h3 className="m-0 text-body-md font-semibold text-on-surface">{t('spotDetail.claim')}</h3>
        <p className="m-0 text-label-sm text-on-surface-variant">
          {t('spotDetail.claimHint')}
        </p>
        {claimed ? (
          <p className="m-0 flex items-center gap-xs text-body-md font-medium text-secondary">
            <Icon name="check_circle" className="text-[16px] leading-none" />
            {t('spotDetail.claimConfirmed')}
          </p>
        ) : confirmingClaim ? (
          // Explicit confirmation for an irreversible, everyone-visible change.
          <div className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md">
            <p className="m-0 flex items-start gap-xs text-label-sm font-medium text-on-surface">
              <Icon name="warning" className="text-[16px] leading-none text-tertiary" />
              {t('spotDetail.claimConfirmPrompt')}
            </p>
            <div className="flex gap-sm">
              <Button
                variant="secondary"
                onClick={onClaim}
                disabled={disabled}
                className="flex-1"
              >
{claimMutation.isPending ? t('spotDetail.claiming') : t('spotDetail.claimConfirmYes')}
              </Button>
              <Button
                variant="ghost"
                onClick={() => setConfirmingClaim(false)}
                disabled={claimMutation.isPending}
                className="flex-1"
              >
                {t('actions.cancel', { ns: 'common' })}
              </Button>
            </div>
          </div>
        ) : (
          <Button
            variant="secondary"
            onClick={() => setConfirmingClaim(true)}
            disabled={disabled}
            className="w-full"
          >
            {t('spotDetail.claimThisSpot')}
          </Button>
        )}
        {claimMutation.isError ? (
          <FriendlyApiErrorMessage error={claimMutation.error} mapper={mapParkingActionError} />
        ) : null}
      </div>

      {/* Report issue */}
      <form onSubmit={onReport} className="mt-lg border-t border-outline-variant/30 pt-lg">
        <fieldset
          disabled={reportMutation.isPending}
          className="m-0 flex flex-col gap-sm border-0 p-0"
        >
          <h3 className="m-0 text-body-md font-semibold text-on-surface">{t('spotDetail.report')}</h3>
          <p className="m-0 text-label-sm text-on-surface-variant">
            {t('spotDetail.reportHint')}
          </p>
          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            {t('spotDetail.reportWhatWrong')}
            <select defaultValue="" className={FIELD_CLASSES} {...registerReport('reason')}>
              <option value="">{t('spotDetail.selectReason')}</option>
              {MODERATION_REASONS.map((reason) => (
                <option key={reason} value={reason}>
                  {enumLabel(reason, t, ['reportReason'])}
                </option>
              ))}
            </select>
          </label>
          {reportErrors.reason ? (
            <p className="m-0 text-label-sm text-error">{reportErrors.reason.message}</p>
          ) : null}

          <label className="flex flex-col gap-xs text-label-sm font-medium text-on-surface-variant">
            {t('spotDetail.detailsOptional')}
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
{reportMutation.isPending ? t('spotDetail.reporting') : t('spotDetail.reportThisSpot')}
          </Button>
        </fieldset>
      </form>
      {reportMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={reportMutation.error} mapper={mapParkingReportError} />
        </div>
      ) : null}
      {reportMutation.isSuccess ? (
        <p className="m-0 mt-sm text-body-md font-medium text-secondary">
          {t('spotDetail.reportThanks')}{' '}
          <Link to="/reports" className="text-primary">
            {t('spotDetail.viewMyReports')}
          </Link>
        </p>
      ) : null}
    </Surface>
  );
}
