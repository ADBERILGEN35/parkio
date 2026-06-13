import type { LegalStatus, Spot } from '@parkio/types';
import {
  Card,
  EmptyState,
  Icon,
  LoadingState,
  PageShell,
  SoftBadge,
  StatusBadge,
  type BadgeTone,
} from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { parkingApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatInstant, formatRemaining, humanizeEnum } from '@/lib/format';

const LEGAL_STATUS_TONES: Record<LegalStatus, BadgeTone> = {
  LEGAL: 'success',
  UNCERTAIN: 'warning',
  ILLEGAL_OR_RISKY: 'danger',
};

export function MySpotsPage() {
  const query = useQuery({ queryKey: ['parking', 'my-spots'], queryFn: parkingApi.getMySpots });

  return (
    <PageShell title="My spots">
      <AppNav />

      <Card title="Spots I shared">
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : query.data.length === 0 ? (
          <EmptyState
            icon="local_parking"
            title="No spots yet"
            description="Spots you share will appear here, with their live status and verification activity."
            action={
              <Link
                to="/upload"
                className="inline-flex items-center gap-xs rounded-full bg-primary px-lg py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90"
              >
                <Icon name="add_a_photo" className="text-[16px] leading-none" />
                Share your first spot
              </Link>
            }
          />
        ) : (
          <ul className="m-0 flex list-none flex-col gap-sm p-0">
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
    <li className="rounded-xl border border-outline-variant/40 bg-surface-container-low p-md transition-colors duration-std hover:border-primary/50">
      <div className="flex items-start justify-between gap-sm">
        <Link
          to={`/spots/${spot.id}`}
          className="min-w-0 break-words text-body-md font-semibold text-on-surface no-underline hover:text-primary hover:underline"
        >
          {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
        </Link>
        <StatusBadge status={spot.status} className="shrink-0" />
      </div>

      <p className="m-0 mt-sm flex items-center gap-xs text-label-sm text-on-surface-variant">
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

      {/* Owner-only signals returned by GET /parking/my-spots (SpotResponse). */}
      <div className="mt-sm flex flex-wrap gap-md text-label-sm text-on-surface-variant">
        <span className="flex items-center gap-xs">
          <Icon name="verified" className="text-[14px] leading-none" />
          {spot.verificationCount} verification{spot.verificationCount === 1 ? '' : 's'}
        </span>
        <span className="flex items-center gap-xs">
          <Icon name="speed" className="text-[14px] leading-none" />
          Confidence {spot.confidenceScore}
        </span>
        <span className="flex items-center gap-xs">
          <Icon name="report" className="text-[14px] leading-none" />
          {spot.filledReportCount} filled report{spot.filledReportCount === 1 ? '' : 's'}
        </span>
      </div>
    </li>
  );
}
