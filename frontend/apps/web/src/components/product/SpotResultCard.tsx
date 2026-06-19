import type { LegalStatus, PublicSpot, Spot } from '@parkio/types';
import {
  Icon,
  SoftBadge,
  StatusBadge,
  cn,
  getTrustFreshnessVisual,
  type BadgeTone,
} from '@parkio/ui';
import { Link } from 'react-router-dom';
import { formatInstant, formatRelativeAgo, formatRemaining, humanizeEnum } from '@/lib/format';
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
}

export function SpotResultCard({
  spot,
  className,
  compact = false,
  showOwnerMetrics = false,
}: SpotResultCardProps) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  const ownerMetrics = showOwnerMetrics ? readOwnerMetrics(spot) : {};
  const address = spot.addressText ?? `${spot.latitude}, ${spot.longitude}`;

  return (
    <ProductCard interactive className={cn(compact ? 'rounded-xl p-md' : 'rounded-[1.5rem] p-md', className)}>
      <div className="flex items-start justify-between gap-sm">
        <Link
          to={`/spots/${spot.id}`}
          className="min-w-0 break-words text-body-md font-semibold text-on-surface no-underline hover:text-primary hover:underline"
        >
          {address}
        </Link>
        <StatusBadge status={spot.status} className="shrink-0" />
      </div>

      <p className={cn('m-0 mt-sm flex items-center gap-xs text-label-sm font-semibold', freshness.className)}>
        <Icon name={freshness.icon} className="text-[14px] leading-none" />
        {freshness.label} · updated {formatRelativeAgo(spot.updatedAt)}
      </p>

      <p className="m-0 mt-xs flex items-center gap-xs text-label-sm text-on-surface-variant">
        <Icon name="schedule" className="text-[14px] leading-none" />
        {formatRemaining(spot.expiresAt)} · expires {formatInstant(spot.expiresAt)}
      </p>

      {spot.description ? (
        <p className="m-0 mt-sm line-clamp-2 text-body-md text-on-surface-variant">
          {spot.description}
        </p>
      ) : null}

      <div className="mt-sm flex flex-wrap items-center gap-xs">
        {spot.suitableVehicleTypes.map((type) => (
          <span
            key={type}
            className="rounded-full bg-surface-container px-sm py-xs text-label-sm text-on-surface-variant"
          >
            {humanizeEnum(type)}
          </span>
        ))}
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
    </ProductCard>
  );
}
