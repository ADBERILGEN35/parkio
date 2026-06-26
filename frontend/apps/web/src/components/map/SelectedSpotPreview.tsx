import { Icon, IconButton, StatusBadge, cn, getTrustFreshnessVisual } from '@parkio/ui';
import { Link } from 'react-router-dom';
import { formatRelativeAgo, formatRemaining, humanizeEnum } from '@/lib/format';
import { formatDistance, type SpotWithDistance } from '@/lib/spotDiscovery';

export interface SelectedSpotPreviewProps {
  spot: SpotWithDistance;
  onClose: () => void;
  className?: string;
}

/**
 * Compact, map-anchored preview for the selected marker — the Google Maps / Uber
 * "tap a pin → card slides up" pattern.
 *
 * Photo: `PublicSpot` exposes only `mediaId` (a private object key that needs a
 * separate short-lived signed-URL fetch); there is no thumbnail on the nearby
 * payload, so we intentionally show a status-tinted glyph instead of fabricating
 * an image. See README caveats.
 */
export function SelectedSpotPreview({ spot, onClose, className }: SelectedSpotPreviewProps) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  const address = spot.addressText ?? `${spot.latitude.toFixed(5)}, ${spot.longitude.toFixed(5)}`;

  return (
    <div
      role="group"
      aria-label={`Selected spot: ${address}`}
      data-testid="selected-spot-preview"
      className={cn(
        'pointer-events-auto animate-fade-in-up rounded-3xl glass-panel p-md shadow-deep ring-1 ring-primary/10',
        className,
      )}
    >
      <div className="flex items-start gap-md">
        <span
          aria-hidden
          className="relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-primary text-on-primary shadow-md"
        >
          <Icon name="local_parking" className="text-[28px] leading-none" filled />
          <span className="absolute -bottom-1 -right-1 rounded-full bg-surface p-0.5 shadow-sm">
            <span className="block h-3 w-3 rounded-full bg-secondary" />
          </span>
        </span>

        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-sm">
            <p className="m-0 truncate text-body-md font-semibold text-on-surface" title={address}>
              {address}
            </p>
            <IconButton
              aria-label="Close preview"
              icon="close"
              variant="ghost"
              onClick={onClose}
              className="-mr-2 -mt-2 h-11 w-11 shrink-0"
            />
          </div>

          <div className="mt-xs flex flex-wrap items-center gap-x-sm gap-y-xs text-label-sm text-on-surface-variant">
            <StatusBadge status={spot.status} className="font-semibold" />
            {spot.distanceMeters !== null ? (
              <span className="inline-flex items-center gap-xs rounded-full bg-primary/10 px-sm py-xs font-semibold text-primary">
                <Icon name="near_me" className="text-[14px] leading-none" />
                {formatDistance(spot.distanceMeters)}
              </span>
            ) : null}
            <span className={cn('inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs font-semibold', freshness.className)}>
              <Icon name={freshness.icon} className="text-[14px] leading-none" />
              {formatRelativeAgo(spot.updatedAt)}
            </span>
          </div>

          <p className="m-0 mt-sm flex items-center gap-xs text-label-sm font-medium text-on-surface-variant">
            <Icon name="schedule" className="text-[14px] leading-none" />
            {formatRemaining(spot.expiresAt)} · {humanizeEnum(spot.parkingContext)}
          </p>
        </div>
      </div>

      <Link
        to={`/spots/${spot.id}`}
        className="mt-md inline-flex w-full items-center justify-center gap-xs rounded-full bg-primary px-lg py-md text-label-md font-semibold text-on-primary no-underline shadow-md transition-colors hover:bg-primary-container focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
      >
        <Icon name="arrow_forward" className="text-[18px] leading-none" />
        View spot details
      </Link>
    </div>
  );
}
