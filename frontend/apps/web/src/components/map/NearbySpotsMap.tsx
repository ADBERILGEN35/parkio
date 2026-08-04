import './maplibreSetup';
import type { MunicipalFacility, PublicSpot } from '@parkio/types';
import { cn, getSpotStatusVisual, getTrustFreshnessVisual } from '@parkio/ui';
import { memo, useCallback, useId, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import Map, { Marker } from 'react-map-gl/maplibre';
import { freshnessLabel, spotStatusLabel } from '@/lib/localized-status';
import { MapFloatingControls } from './MapFloatingControls';
import { MunicipalFacilityMarker } from './MunicipalFacilityMarker';
import { ParkedCarFocus } from './ParkedCarFocus';
import { ParkedCarMarker } from './ParkedCarMarker';
import { isUsableParkedCoordinate, type ParkedCarFocusRequest } from './parkedCarCoords';
import { DEFAULT_MAP_ZOOM, getMapStyle, type LatLng } from './mapConfig';
import { Recenter } from './Recenter';

export interface ParkedCarMapState {
  latitude: number;
  longitude: number;
}

export interface NearbySpotsMapProps {
  center: LatLng;
  zoom?: number;
  spots: PublicSpot[];
  /** Municipal facilities — separate inventory; omit or [] when flag off. */
  municipalFacilities?: MunicipalFacility[];
  onPickCenter: (lat: number, lng: number) => void;
  /** Currently selected community spot id (controlled — the preview card lives outside). */
  selectedId?: string | null;
  /** Currently selected municipal facility id (mutually exclusive in the page). */
  selectedMunicipalId?: string | null;
  /** Selection changes (marker tap, or `null` when the map background is tapped). */
  onSelectSpot?: (id: string | null) => void;
  onSelectMunicipalFacility?: (id: string | null) => void;
  height?: number | string;
  onLocate?: () => void;
  locating?: boolean;
  showFloatingControls?: boolean;
  /** ACTIVE Parking Session coordinates for the dedicated parked-car marker. */
  parkedCar?: ParkedCarMapState | null;
  /** Visual emphasis for the parked-car marker (shared with the Active card focus). */
  parkedCarSelected?: boolean;
  /** Marker click — focuses the Active Parking Session experience (not Spot Detail). */
  onSelectParkedCar?: () => void;
  /** Imperative flyTo request from card / recenter / marker. */
  parkedCarFocusRequest?: ParkedCarFocusRequest | null;
  /** Compact floating recenter control; omitted when there is no ACTIVE session. */
  onFocusParkedCar?: () => void;
  /** Accessible region label for the interactive map surface. */
  ariaLabel?: string;
  /** Non-visual instructions describing how the map and markers behave. */
  ariaDescription?: string;
  /** Live summary announced when selection changes outside the list. */
  selectionSummary?: string | null;
}

/** Premium, status-aware marker shown for each real spot. */
const SpotMarker = memo(function SpotMarker({
  spot,
  selected,
  onSelect,
}: {
  spot: PublicSpot;
  selected: boolean;
  /** Stable across renders so `memo` skips unaffected markers on selection. */
  onSelect: (id: string) => void;
}) {
  const { t } = useTranslation(['map', 'common']);
  const status = getSpotStatusVisual(spot.status);
  const { freshness } = getTrustFreshnessVisual(spot.updatedAt);
  const dimmed = freshness === 'aging' || freshness === 'stale';
  const statusText = spotStatusLabel(spot.status, t);
  const label = spot.addressText
    ? t('marker.parkingSpotNear', { status: statusText, address: spot.addressText })
    : t('marker.parkingSpot', { status: statusText });
  const freshnessText =
    freshness === 'fresh'
      ? t('marker.recentlyUpdated')
      : t('marker.freshness', { freshness: freshnessLabel(freshness, t) });

  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={selected}
      data-testid="community-spot-marker"
      onClick={(event) => {
        event.stopPropagation();
        onSelect(spot.id);
      }}
      className={cn(
        'group relative flex h-10 w-10 items-center justify-center rounded-full border-2 border-white bg-surface-container-lowest shadow-lg transition-all duration-std focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30 motion-safe:hover:-translate-y-0.5',
        selected && 'scale-110 shadow-xl ring-4 ring-primary/20',
        dimmed && !selected && 'opacity-75',
      )}
    >
      {selected ? (
        <span className={cn('absolute inset-0 rounded-full opacity-30 motion-safe:animate-ping', status.dotClassName)} />
      ) : null}
      <span
        className={cn(
          'relative flex h-6 w-6 items-center justify-center rounded-full text-[12px] font-black text-white shadow-sm transition-transform duration-std group-hover:scale-105',
          status.dotClassName,
        )}
      >
        P
      </span>
      <span className="sr-only">{freshnessText}</span>
    </button>
  );
});

export function NearbySpotsMap({
  center,
  zoom = DEFAULT_MAP_ZOOM,
  spots,
  municipalFacilities = [],
  onPickCenter,
  selectedId = null,
  selectedMunicipalId = null,
  onSelectSpot,
  onSelectMunicipalFacility,
  height = 320,
  onLocate,
  locating = false,
  showFloatingControls = false,
  parkedCar = null,
  parkedCarSelected = false,
  onSelectParkedCar,
  parkedCarFocusRequest = null,
  onFocusParkedCar,
  ariaLabel,
  ariaDescription,
  selectionSummary = null,
}: NearbySpotsMapProps) {
  const descriptionId = useId();
  const selectionId = useId();
  // Stable selection handler: passed by reference to every marker so the memoized
  // `SpotMarker` only re-renders when *its own* `selected` flag flips. Selecting a
  // spot therefore re-renders two markers (the old + new selection), not all N.
  const handleSelect = useCallback((id: string) => onSelectSpot?.(id), [onSelectSpot]);
  const handleSelectMunicipal = useCallback(
    (id: string) => onSelectMunicipalFacility?.(id),
    [onSelectMunicipalFacility],
  );
  const handleSelectParkedCar = useCallback(() => onSelectParkedCar?.(), [onSelectParkedCar]);

  // Marker geometry/labels change only with the spot set; `selected` styling is a
  // cheap per-marker prop. Panning/dragging never rebuilds this list.
  const markers = useMemo(
    () =>
      spots.map((spot) => (
        <Marker key={`spot-${spot.id}`} longitude={spot.longitude} latitude={spot.latitude} anchor="center">
          <SpotMarker spot={spot} selected={selectedId === spot.id} onSelect={handleSelect} />
        </Marker>
      )),
    [spots, selectedId, handleSelect],
  );

  const municipalMarkers = useMemo(
    () =>
      municipalFacilities.map((facility) => (
        <Marker
          key={`facility-${facility.id}`}
          longitude={facility.longitude}
          latitude={facility.latitude}
          anchor="center"
        >
          <MunicipalFacilityMarker
            facility={facility}
            selected={selectedMunicipalId === facility.id}
            onSelect={handleSelectMunicipal}
          />
        </Marker>
      )),
    [municipalFacilities, selectedMunicipalId, handleSelectMunicipal],
  );

  const showParkedCar =
    parkedCar !== null && isUsableParkedCoordinate(parkedCar.latitude, parkedCar.longitude);

  return (
    <div
      role="region"
      aria-label={ariaLabel}
      aria-describedby={
        ariaDescription || selectionSummary ? [ariaDescription ? descriptionId : null, selectionSummary ? selectionId : null].filter(Boolean).join(' ') : undefined
      }
      className="h-full w-full"
      style={{ height, width: '100%' }}
    >
      {ariaDescription ? (
        <p id={descriptionId} className="sr-only">
          {ariaDescription}
        </p>
      ) : null}
      {selectionSummary ? (
        <p id={selectionId} className="sr-only" role="status" aria-live="polite" aria-atomic="true">
          {selectionSummary}
        </p>
      ) : null}
      <Map
        initialViewState={{ longitude: center.lng, latitude: center.lat, zoom }}
        mapStyle={getMapStyle()}
        dragRotate={false}
        pitchWithRotate={false}
        onClick={(event) => {
          onSelectSpot?.(null);
          onSelectMunicipalFacility?.(null);
          onPickCenter(event.lngLat.lat, event.lngLat.lng);
        }}
        style={{ height: '100%', width: '100%' }}
      >
        <Recenter lat={center.lat} lng={center.lng} zoom={zoom} />
        <ParkedCarFocus request={parkedCarFocusRequest} />
        {showFloatingControls && onLocate ? (
          <MapFloatingControls
            onLocate={onLocate}
            locating={locating}
            sidebarOpen
            onFocusParkedCar={showParkedCar ? onFocusParkedCar : undefined}
          />
        ) : null}

        {/* Current search center indicator is decorative; surrounding copy names it. */}
        <Marker longitude={center.lng} latitude={center.lat} anchor="center">
          <span
            aria-hidden="true"
            className="pointer-events-none block h-4 w-4 rounded-full border-2 border-white bg-primary/50 shadow-md"
          />
        </Marker>

        {municipalMarkers}
        {markers}

        {showParkedCar ? (
          <Marker
            longitude={parkedCar.longitude}
            latitude={parkedCar.latitude}
            anchor="center"
            style={{ zIndex: 2 }}
          >
            <ParkedCarMarker selected={parkedCarSelected} onSelect={handleSelectParkedCar} />
          </Marker>
        ) : null}
      </Map>
    </div>
  );
}
