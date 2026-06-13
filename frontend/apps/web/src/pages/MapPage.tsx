import { zodResolver } from '@hookform/resolvers/zod';
import type { LegalStatus, NearbySearchParams, PublicSpot } from '@parkio/types';
import {
  Button,
  EmptyState,
  ErrorMessage,
  Icon,
  Input,
  LoadingState,
  PageShell,
  SoftBadge,
  StatusBadge,
  cn,
  getTrustFreshnessVisual,
  type BadgeTone,
} from '@parkio/ui';
import { nearbySearchSchema, type NearbySearchFormValues } from '@parkio/validation';
import { useQuery } from '@tanstack/react-query';
import { Suspense, lazy, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { parkingApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { DEFAULT_CENTER, isValidLatLng } from '@/components/map/mapConfig';
import { formatInstant, formatRelativeAgo, formatRemaining, humanizeEnum } from '@/lib/format';

// Lazily loaded so Leaflet lands in its own chunk instead of the eager /map entry.
const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

type GeoStatus = 'idle' | 'locating' | 'error';

/**
 * Map Experience Beta (`/map`): map-dominant layout with a high-density result
 * sidebar on desktop; on mobile the map stays first and the search/results
 * panel stacks below it. Search behavior (geolocation, manual lat/lng,
 * click-to-set-center, nearby query params) is unchanged.
 */
export function MapPage() {
  const [params, setParams] = useState<NearbySearchParams | null>(null);
  const [geoStatus, setGeoStatus] = useState<GeoStatus>('idle');
  const [geoError, setGeoError] = useState<string | null>(null);

  const search = useQuery({
    queryKey: ['parking', 'nearby', params],
    queryFn: () => parkingApi.getNearbySpots(params as NearbySearchParams),
    enabled: params !== null,
  });

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<NearbySearchFormValues>({ resolver: zodResolver(nearbySearchSchema) });

  const onSubmit = handleSubmit((values) => setParams(values));

  // Derive the map center from the current lat/lng fields, falling back to a
  // default city center until the user locates/picks coordinates.
  const latValue = Number(watch('lat'));
  const lngValue = Number(watch('lng'));
  const hasCenter = isValidLatLng(latValue, lngValue);
  const center = hasCenter ? { lat: latValue, lng: lngValue } : DEFAULT_CENTER;

  const applyCoords = (lat: number, lng: number) => {
    setValue('lat', Number(lat.toFixed(6)), { shouldValidate: true });
    setValue('lng', Number(lng.toFixed(6)), { shouldValidate: true });
  };

  const locate = () => {
    if (!('geolocation' in navigator)) {
      setGeoStatus('error');
      setGeoError('Geolocation is not available in this browser. Enter coordinates manually.');
      return;
    }
    setGeoStatus('locating');
    setGeoError(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        applyCoords(position.coords.latitude, position.coords.longitude);
        setGeoStatus('idle');
      },
      (error) => {
        setGeoStatus('error');
        setGeoError(
          error.code === error.PERMISSION_DENIED
            ? 'Location permission denied. Enter coordinates manually below.'
            : 'Could not determine your location. Enter coordinates manually below.',
        );
      },
      { enableHighAccuracy: false, timeout: 10_000 },
    );
  };

  return (
    <PageShell title="Map">
      <AppNav />

      <div className="flex flex-col gap-lg lg:h-[calc(100vh-14rem)] lg:min-h-[520px] lg:flex-row lg:items-stretch">
        {/* Result sidebar — second on mobile, left column on desktop */}
        <aside className="order-2 flex w-full min-w-0 flex-col gap-md lg:order-1 lg:w-[372px] lg:min-h-0 lg:shrink-0 lg:overflow-y-auto lg:pr-xs">
          {/* Search controls */}
          <section className="rounded-xl border border-outline-variant bg-surface-container-lowest p-md">
            <h2 className="m-0 text-title-lg text-on-surface">Find parking</h2>
            <p className="m-0 mt-xs text-body-md text-on-surface-variant">
              Click the map to set search center.
            </p>
            <Button
              type="button"
              onClick={locate}
              disabled={geoStatus === 'locating'}
              className="mt-md w-full"
            >
              <Icon name="my_location" className="text-[16px] leading-none" />
              {geoStatus === 'locating' ? 'Locating…' : 'Use my location'}
            </Button>
            <p className="m-0 mt-sm text-label-sm text-on-surface-variant">
              No location permission? Enter coordinates manually.
            </p>
            {geoStatus === 'error' && geoError ? (
              <div className="mt-sm">
                <ErrorMessage message={geoError} />
              </div>
            ) : null}
            <form onSubmit={onSubmit} className="mt-md">
              <fieldset
                disabled={search.isFetching}
                className="m-0 flex flex-col gap-md border-0 p-0"
              >
                <div className="grid grid-cols-2 gap-sm">
                  <Input label="Latitude" inputMode="decimal" error={errors.lat?.message} {...register('lat')} />
                  <Input label="Longitude" inputMode="decimal" error={errors.lng?.message} {...register('lng')} />
                </div>
                <div className="grid grid-cols-2 gap-sm">
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
                <Button type="submit" disabled={search.isFetching} className="w-full">
                  {search.isFetching ? 'Searching…' : 'Search nearby'}
                </Button>
              </fieldset>
            </form>
          </section>

          {/* Results */}
          <section className="flex min-w-0 flex-col gap-sm" aria-label="Search results">
            {params === null ? (
              <EmptyState
                icon="travel_explore"
                title="Search for nearby spots"
                description="Use my location, click the map to set search center, or enter coordinates manually — then search."
              />
            ) : search.isPending ? (
              <LoadingState label="Searching…" />
            ) : search.isError ? (
              <FriendlyApiErrorMessage error={search.error} />
            ) : search.data.length === 0 ? (
              <EmptyState
                icon="location_off"
                title="No spots nearby"
                description="No spots found in this area. Try a larger radius or a different search center."
              />
            ) : (
              <>
                <div>
                  <h2 className="m-0 text-title-lg text-on-surface">
                    {search.data.length} {search.data.length === 1 ? 'spot' : 'spots'} nearby
                  </h2>
                  {/* Honest trust signal: PublicSpotResponse has no lastVerifiedAt. */}
                  <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
                    Freshness reflects each record's last update — the backend doesn't expose
                    lastVerifiedAt yet.
                  </p>
                </div>
                <ul className="m-0 flex list-none flex-col gap-sm p-0">
                  {search.data.map((spot) => (
                    <NearbySpotCard key={spot.id} spot={spot} />
                  ))}
                </ul>
              </>
            )}
          </section>
        </aside>

        {/* Map canvas — first on mobile, dominant right pane on desktop */}
        <section className="relative order-1 h-[45vh] min-h-[280px] overflow-hidden rounded-2xl border border-outline-variant bg-surface-container-low lg:order-2 lg:h-auto lg:min-h-0 lg:flex-1">
          <div className="pointer-events-none absolute left-sm top-sm z-[1000] rounded border border-outline-variant bg-surface-container-lowest px-sm py-xs text-label-sm text-on-surface-variant">
            Click the map to set search center
          </div>
          <Suspense fallback={<LoadingState label="Loading map…" />}>
            <NearbySpotsMap
              center={center}
              spots={search.data ?? []}
              onPickCenter={applyCoords}
              height="100%"
            />
          </Suspense>
        </section>
      </div>
    </PageShell>
  );
}

const LEGAL_STATUS_TONES: Record<LegalStatus, BadgeTone> = {
  LEGAL: 'success',
  UNCERTAIN: 'warning',
  ILLEGAL_OR_RISKY: 'danger',
};

/**
 * High-density result card. Shows public fields only — PublicSpotResponse has
 * no price/confidence/verificationCount, so none are rendered. Freshness is
 * derived from `updatedAt` (no lastVerifiedAt on the backend yet).
 */
function NearbySpotCard({ spot }: { spot: PublicSpot }) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  return (
    <li className="rounded-xl border border-outline-variant bg-surface-container-lowest p-md transition-colors duration-std hover:border-primary/50">
      <div className="flex items-start justify-between gap-sm">
        <Link
          to={`/spots/${spot.id}`}
          className="min-w-0 break-words text-body-md font-semibold text-on-surface no-underline hover:text-primary hover:underline"
        >
          {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
        </Link>
        <StatusBadge status={spot.status} className="shrink-0" />
      </div>

      {/* Trust freshness — primary signal */}
      <p
        className={cn(
          'm-0 mt-sm flex items-center gap-xs text-label-sm font-semibold',
          freshness.className,
        )}
      >
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
    </li>
  );
}
