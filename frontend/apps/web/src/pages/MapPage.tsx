import { zodResolver } from '@hookform/resolvers/zod';
import type { LegalStatus, NearbySearchParams, PublicSpot } from '@parkio/types';
import {
  Button,
  EmptyState,
  ErrorMessage,
  Icon,
  Input,
  LoadingState,
  SoftBadge,
  StatusBadge,
  cn,
  getTrustFreshnessVisual,
  type BadgeTone,
} from '@parkio/ui';
import { nearbySearchSchema, type NearbySearchFormValues } from '@parkio/validation';
import type { UseQueryResult } from '@tanstack/react-query';
import { useQuery } from '@tanstack/react-query';
import { Suspense, lazy, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { parkingApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { DEFAULT_CENTER, isValidLatLng } from '@/components/map/mapConfig';
import { formatInstant, formatRelativeAgo, formatRemaining, humanizeEnum } from '@/lib/format';

const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

type GeoStatus = 'idle' | 'locating' | 'error';

/**
 * Map Experience V3 (`/map`): full-bleed map canvas with floating glass search
 * overlay, slide-in results sidebar, and floating map controls.
 * Search behavior (geolocation, manual lat/lng, click-to-set-center, nearby
 * query params) is unchanged.
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
    <div className="fixed inset-x-0 bottom-16 top-16 z-0 overflow-hidden bg-background md:bottom-0">
      {/* Full-bleed map canvas */}
      <div className="absolute inset-0 z-0">
        <Suspense fallback={<LoadingState label="Loading map…" />}>
          <NearbySpotsMap
            center={center}
            spots={search.data ?? []}
            onPickCenter={applyCoords}
            height="100%"
            onLocate={locate}
            locating={geoStatus === 'locating'}
            showFloatingControls
          />
        </Suspense>
      </div>

      {/* Floating search overlay */}
      <div className="pointer-events-none absolute inset-x-0 top-md z-[1100] flex justify-center px-md md:justify-start md:pl-lg">
        <div className="pointer-events-auto w-full max-w-md animate-fade-in-up glass-panel rounded-2xl p-md shadow-deep">
          <h2 className="m-0 text-title-lg text-on-surface">Find parking</h2>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            Click the map to set search center.
          </p>
          {geoStatus === 'error' && geoError ? (
            <div className="mt-sm">
              <ErrorMessage message={geoError} />
            </div>
          ) : null}
          <form onSubmit={onSubmit} className="mt-md">
            <fieldset
              disabled={search.isFetching}
              className="m-0 flex flex-col gap-sm border-0 p-0"
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
                <Icon name="travel_explore" className="text-[16px] leading-none" />
                {search.isFetching ? 'Searching…' : 'Search nearby'}
              </Button>
            </fieldset>
          </form>
        </div>
      </div>

      {/* Floating results sidebar / mobile bottom sheet */}
      <aside
        aria-label="Search results"
        className={cn(
          'pointer-events-none absolute z-[1050] flex flex-col',
          'inset-x-0 bottom-16 max-h-[50vh] md:bottom-0 md:left-auto md:right-0 md:top-0 md:max-h-none md:w-[400px]',
        )}
      >
        <div
          className={cn(
            'pointer-events-auto flex h-full min-h-0 flex-col overflow-hidden glass-panel shadow-sheet-left',
            'rounded-t-3xl md:animate-slide-in-right md:rounded-none md:rounded-l-[2rem]',
          )}
        >
          <div
            aria-hidden
            className="mx-auto mt-sm h-1.5 w-12 shrink-0 rounded-full bg-outline-variant md:hidden"
          />
          <div className="flex min-h-0 flex-1 flex-col gap-sm overflow-y-auto p-md hide-scrollbar">
            <ResultsPanel params={params} search={search} />
          </div>
        </div>
      </aside>
    </div>
  );
}

function ResultsPanel({
  params,
  search,
}: {
  params: NearbySearchParams | null;
  search: UseQueryResult<PublicSpot[], Error>;
}) {
  if (params === null) {
    return (
      <EmptyState
        icon="travel_explore"
        title="Search for nearby spots"
        description="Use my location, click the map to set search center, or enter coordinates — then search."
      />
    );
  }
  if (search.isPending) {
    return <LoadingState label="Searching…" />;
  }
  if (search.isError) {
    return <FriendlyApiErrorMessage error={search.error} />;
  }
  if (!search.data || search.data.length === 0) {
    return (
      <EmptyState
        icon="location_off"
        title="No spots nearby"
        description="No spots found in this area. Try a larger radius or a different search center."
      />
    );
  }

  return (
    <>
      <div>
        <h2 className="m-0 text-title-lg text-on-surface">
          {search.data.length} {search.data.length === 1 ? 'spot' : 'spots'} nearby
        </h2>
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          Freshness reflects each record&apos;s last update — the backend doesn&apos;t expose
          lastVerifiedAt yet.
        </p>
      </div>
      <ul className="m-0 flex list-none flex-col gap-sm p-0">
        {search.data.map((spot) => (
          <NearbySpotCard key={spot.id} spot={spot} />
        ))}
      </ul>
    </>
  );
}

const LEGAL_STATUS_TONES: Record<LegalStatus, BadgeTone> = {
  LEGAL: 'success',
  UNCERTAIN: 'warning',
  ILLEGAL_OR_RISKY: 'danger',
};

function NearbySpotCard({ spot }: { spot: PublicSpot }) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  return (
    <li className="hover-lift rounded-[1.5rem] border border-surface-container-highest bg-surface-container-lowest p-md shadow-soft transition-shadow duration-std hover:shadow-deep">
      <div className="flex items-start justify-between gap-sm">
        <Link
          to={`/spots/${spot.id}`}
          className="min-w-0 break-words text-body-md font-semibold text-on-surface no-underline hover:text-primary hover:underline"
        >
          {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
        </Link>
        <StatusBadge status={spot.status} className="shrink-0" />
      </div>
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
