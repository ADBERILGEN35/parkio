import type { LegalStatus, PublicSpot, Spot, SpotVehicleType, VehicleType } from '@parkio/types';
import {
  Icon,
  IconButton,
  SoftBadge,
  StatusBadge,
  cn,
  getTrustFreshnessVisual,
  type BadgeTone,
} from '@parkio/ui';
import { memo } from 'react';
import { Link } from 'react-router-dom';
import { formatInstant, formatRelativeAgo, formatRemaining, humanizeEnum } from '@/lib/format';
import { formatDistance } from '@/lib/spotDiscovery';
import { ProductCard } from './ProductCard';

const LEGAL_STATUS_TONES: Record<LegalStatus, BadgeTone> = {
  LEGAL: 'success',
  UNCERTAIN: 'warning',
  ILLEGAL_OR_RISKY: 'danger',
};

type OwnerMetrics = Partial<Pick<Spot, 'confidenceScore' | 'verificationCount' | 'filledReportCount'>>;

function readOwnerMetrics(spot: PublicSpot): OwnerMetrics {
  const raw = spot as PublicSpot & OwnerMetrics;
  return {
    confidenceScore: typeof raw.confidenceScore === 'number' ? raw.confidenceScore : undefined,
    verificationCount: typeof raw.verificationCount === 'number' ? raw.verificationCount : undefined,
    filledReportCount: typeof raw.filledReportCount === 'number' ? raw.filledReportCount : undefined,
  };
}

export interface SpotResultCardProps {
  spot: PublicSpot;
  className?: string;
  compact?: boolean;
  showOwnerMetrics?: boolean;
  /** Current user's saved vehicle type, when already available from user-service. */
  userVehicleType?: VehicleType | null;
  /** Straight-line distance from the search center, in meters (discovery mode). */
  distanceMeters?: number | null;
  /** Highlight as the currently selected result. */
  selected?: boolean;
  /** When provided, renders a "Show on map" control that selects this spot. */
  onSelect?: () => void;
}

function SpotResultCardImpl({
  spot,
  className,
  compact = false,
  showOwnerMetrics = false,
  userVehicleType = null,
  distanceMeters = null,
  selected = false,
  onSelect,
}: SpotResultCardProps) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  const ownerMetrics = showOwnerMetrics ? readOwnerMetrics(spot) : {};
  const address = spot.addressText ?? `${spot.latitude}, ${spot.longitude}`;
  const discovery = typeof onSelect === 'function';
  const compatibility = getVehicleCompatibility(spot.suitableVehicleTypes, userVehicleType);

  return (
    <ProductCard
      interactive
      selected={selected}
      className={cn(
        discovery
          ? 'rounded-2xl p-sm'
          : compact
            ? 'rounded-xl p-md'
            : 'rounded-[1.5rem] p-md',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-xs">
        <Link
          to={`/spots/${spot.id}`}
          className="block min-w-0 flex-1 break-words text-body-md font-semibold leading-snug text-on-surface no-underline hover:text-primary hover:underline"
        >
          {address}
        </Link>
        <div className="flex shrink-0 items-center gap-xs">
          {distanceMeters !== null ? (
            <span className="inline-flex items-center gap-0.5 whitespace-nowrap rounded-full bg-primary/10 px-sm py-xs text-label-sm font-semibold text-primary">
              <Icon name="near_me" className="text-[13px] leading-none" />
              {formatDistance(distanceMeters)}
            </span>
          ) : null}
          <StatusBadge status={spot.status} />
        </div>
      </div>

      <p className={cn('m-0 mt-xs flex items-center gap-xs text-label-sm font-semibold', freshness.className)}>
        <Icon name={freshness.icon} className="text-[14px] leading-none" />
        {freshness.label} · updated {formatRelativeAgo(spot.updatedAt)}
      </p>

      <p className="m-0 mt-xs flex items-center gap-xs text-label-sm text-on-surface-variant">
        <Icon name="schedule" className="text-[14px] leading-none" />
        {formatRemaining(spot.expiresAt)} · expires {formatInstant(spot.expiresAt)}
      </p>

      {spot.description ? (
        <p className={cn('m-0 text-body-md text-on-surface-variant', discovery ? 'mt-xs line-clamp-1' : 'mt-sm line-clamp-2')}>
          {spot.description}
        </p>
      ) : null}

      <div className="mt-xs flex flex-wrap items-center gap-xs">
        {spot.suitableVehicleTypes.map((type) => (
          <span
            key={type}
            className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant"
          >
            {humanizeEnum(type)}
          </span>
        ))}
        {compatibility ? (
          <span
            className={cn(
              'inline-flex items-center gap-0.5 rounded-full px-sm py-xs text-label-sm font-semibold',
              compatibility.compatible
                ? 'bg-success/10 text-success'
                : 'bg-warning/10 text-warning',
            )}
          >
            <Icon
              name={compatibility.compatible ? 'check_circle' : 'info'}
              className="text-[14px] leading-none"
            />
            {compatibility.label}
          </span>
        ) : null}
        <span className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant">
          {humanizeEnum(spot.parkingContext)}
        </span>
        <SoftBadge tone={LEGAL_STATUS_TONES[spot.legalStatus]}>
          {humanizeEnum(spot.legalStatus)}
        </SoftBadge>
      </div>

      {showOwnerMetrics ? (
        <div className="mt-sm flex flex-wrap gap-md text-label-sm text-on-surface-variant">
          {ownerMetrics.verificationCount !== undefined ? (
            <span className="flex items-center gap-xs">
              <Icon name="verified" className="text-[14px] leading-none" />
              {ownerMetrics.verificationCount} verification{ownerMetrics.verificationCount === 1 ? '' : 's'}
            </span>
          ) : null}
          {ownerMetrics.confidenceScore !== undefined ? (
            <span className="flex items-center gap-xs">
              <Icon name="speed" className="text-[14px] leading-none" />
              Confidence {ownerMetrics.confidenceScore}
            </span>
          ) : null}
          {ownerMetrics.filledReportCount !== undefined ? (
            <span className="flex items-center gap-xs">
              <Icon name="report" className="text-[14px] leading-none" />
              {ownerMetrics.filledReportCount} filled report{ownerMetrics.filledReportCount === 1 ? '' : 's'}
            </span>
          ) : null}
        </div>
      ) : null}

      {discovery ? (
        <div className="mt-sm flex items-center gap-sm">
          <Link
            to={`/spots/${spot.id}`}
            className="inline-flex flex-1 items-center justify-center gap-xs rounded-full bg-primary px-md py-sm text-label-md font-semibold text-on-primary no-underline shadow-sm transition-colors hover:bg-primary-container focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
          >
            <Icon name="arrow_forward" className="text-[16px] leading-none" />
            View details
          </Link>
          <IconButton
            aria-label={`Show ${address} on map`}
            aria-pressed={selected}
            icon="map"
            variant="tonal"
            filled={selected}
            onClick={onSelect}
            className={cn(selected && 'bg-primary/10 text-primary')}
          />
        </div>
      ) : null}
    </ProductCard>
  );
}

/** Memoized: discovery lists re-render on sort/filter; only changed cards repaint. */
export const SpotResultCard = memo(SpotResultCardImpl);

const USER_TO_SPOT_VEHICLE: Record<VehicleType, SpotVehicleType[]> = {
  MOTORCYCLE: ['MOTORCYCLE'],
  SMALL_CAR: ['HATCHBACK'],
  SEDAN: ['SEDAN'],
  SUV: ['SUV'],
  VAN: ['VAN'],
  TRUCK: [],
};

function getVehicleCompatibility(
  spotTypes: SpotVehicleType[],
  userVehicleType: VehicleType | null,
): { compatible: boolean; label: string } | null {
  if (!userVehicleType) return null;
  const compatible =
    spotTypes.includes('ANY') ||
    USER_TO_SPOT_VEHICLE[userVehicleType].some((spotType) => spotTypes.includes(spotType));
  return {
    compatible,
    label: compatible
      ? `Fits your ${humanizeEnum(userVehicleType)}`
      : `Not listed for ${humanizeEnum(userVehicleType)}`,
  };
}
