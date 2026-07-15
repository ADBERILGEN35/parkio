import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, View, useWindowDimensions } from 'react-native';
import type { LatLng } from '@parkio/geo';
import { AppText, Button } from '@/components/ui';
import { MapControls } from '@/features/map/components/MapControls';
import { MapSearchBar } from '@/features/map/components/MapSearchBar';
import { MapSurface } from '@/features/map/webmap/MapSurface';
import type { MapRegion, MapSurfaceHandle } from '@/features/map/webmap/types';
import { CenterPinOverlay } from '@/features/spot-create/components/CenterPinOverlay';
import { GpsStatusPill } from '@/features/spot-create/components/GpsStatusPill';
import { SelectedLocationCard } from '@/features/spot-create/components/SelectedLocationCard';
import { useSelectedPlace } from '@/features/spot-create/hooks/useSelectedPlace';
import { useSpotCreationLocation } from '@/features/spot-create/hooks/useSpotCreationLocation';
import { useSpotCreationDraftStore } from '@/features/spot-create/state/spotCreationDraftStore';
import { useLocale } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme';

const DEFAULT_CENTER: LatLng = { lat: 41.0082, lng: 28.9784 };
const PICK_ZOOM = 18;
/** Coordinates closer than ~0.1 m are the same pin — drop no-op region echoes. */
const SAME_COORD_EPSILON = 1e-6;

function sameCoord(a: LatLng | null, b: LatLng | null): boolean {
  return (
    !!a && !!b && Math.abs(a.lat - b.lat) < SAME_COORD_EPSILON && Math.abs(a.lng - b.lng) < SAME_COORD_EPSILON
  );
}

export interface LocationStepProps {
  /** While a finger is on the map, the outer ScrollView must not steal the pan. */
  onMapInteractingChange: (interacting: boolean) => void;
}

/**
 * Wizard step 2: map-first pin placement with GPS, search, and SelectedLocationCard.
 * The fixed center pin marks the map center; every `moveend` becomes the draft location.
 */
export function LocationStep({ onMapInteractingChange }: LocationStepProps) {
  const theme = useTheme();
  const { t } = useLocale();
  const { height: windowHeight } = useWindowDimensions();
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const patchDraft = useSpotCreationDraftStore((state) => state.patchDraft);
  const location = useSpotCreationLocation();

  const mapRef = useRef<MapSurfaceHandle>(null);
  const requestedLocationRef = useRef(false);
  // Set right before a programmatic camera move (GPS recenter / search select);
  // the matching region event is then a settle echo, not a user edit.
  const pendingCameraMoveRef = useRef(false);
  const [pinLifted, setPinLifted] = useState(false);
  const [mapReady, setMapReady] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);
  const [mapKey, setMapKey] = useState(0);

  const draftLocation = draft?.location ?? null;
  const selectedPlace = useSelectedPlace(draftLocation);

  const acquireGps = useCallback(() => {
    void location.acquire().then((fix) => {
      if (!fix) return;
      patchDraft({
        location: fix.center,
        gpsAccuracyMeters: fix.accuracyMeters,
        manualLocationEdited: false,
      });
      pendingCameraMoveRef.current = true;
      mapRef.current?.setCamera(fix.center, PICK_ZOOM, true);
    });
  }, [location, patchDraft]);

  useEffect(() => {
    if (!draft || draft.location || requestedLocationRef.current) return;
    requestedLocationRef.current = true;
    acquireGps();
  }, [acquireGps, draft]);

  const handleRegionChange = useCallback(
    (region: MapRegion) => {
      setPinLifted(false);
      const wasProgrammatic = pendingCameraMoveRef.current;
      pendingCameraMoveRef.current = false;
      if (wasProgrammatic) return; // settle echo of GPS/search camera move — already patched
      if (sameCoord(region.center, draftLocation)) return;
      patchDraft({ location: region.center, manualLocationEdited: true });
    },
    [draftLocation, patchDraft],
  );

  const handleSelectPlace = useCallback(
    (place: { primary: string; lat: number; lng: number }) => {
      const center = { lat: place.lat, lng: place.lng };
      patchDraft({ location: center, manualLocationEdited: true });
      pendingCameraMoveRef.current = true;
      mapRef.current?.setCamera(center, PICK_ZOOM, true);
    },
    [patchDraft],
  );

  const retryMap = useCallback(() => {
    setMapError(null);
    setMapReady(false);
    setMapKey((key) => key + 1);
  }, []);

  if (!draft) return null;

  const locating = location.status === 'prompting' || location.status === 'locating';
  const mapHeight = Math.min(Math.max(windowHeight * 0.42, 300), 440);

  return (
    <View style={styles.root} testID="wizard-location-step">
      <View
        style={[
          styles.mapCard,
          { height: mapHeight, borderColor: theme.colors.border, borderRadius: theme.radius.xl },
        ]}
        onTouchStart={() => onMapInteractingChange(true)}
        onTouchEnd={() => onMapInteractingChange(false)}
        onTouchCancel={() => onMapInteractingChange(false)}
      >
        <MapSurface
          key={mapKey}
          ref={mapRef}
          initialCenter={draft.location ?? DEFAULT_CENTER}
          initialZoom={PICK_ZOOM}
          spots={[]}
          selectedSpotId={null}
          userLocation={null}
          onReady={() => setMapReady(true)}
          onError={(reason) => setMapError(reason)}
          onMoveStart={() => setPinLifted(true)}
          onRegionChange={handleRegionChange}
          clusterSpots={false}
        />
        <CenterPinOverlay lifted={pinLifted} />
        <MapSearchBar topOffset={12} onSelectPlace={handleSelectPlace} />
        <MapControls
          bottomOffset={16}
          onRecenter={acquireGps}
          following={Boolean(draft.location) && !draft.manualLocationEdited}
          locating={locating}
        />
        {!mapReady && !mapError ? (
          <View style={[styles.mapOverlay, { backgroundColor: theme.colors.surfaceMuted }]}>
            <ActivityIndicator color={theme.colors.primary} />
            <AppText variant="callout" tone="muted">
              {t('Loading map…')}
            </AppText>
          </View>
        ) : null}
        {mapError ? (
          <View style={[styles.mapOverlay, { backgroundColor: theme.colors.surfaceMuted }]}>
            <AppText variant="subtitle">{t('Map failed to load')}</AppText>
            <AppText variant="caption" tone="muted">
              {t('Check your connection and try again.')}
            </AppText>
            <Button label={t('Retry map')} variant="secondary" onPress={retryMap} />
          </View>
        ) : null}
      </View>

      <View
        style={[
          styles.locationCard,
          { backgroundColor: theme.colors.surface, borderColor: theme.colors.border, borderRadius: theme.radius.lg },
        ]}
      >
        <GpsStatusPill
          status={location.status}
          accuracyMeters={draft.gpsAccuracyMeters}
          onRetry={acquireGps}
          onOpenSettings={location.openSettings}
        />
        <View style={[styles.divider, { backgroundColor: theme.colors.border }]} />
        <SelectedLocationCard
          place={selectedPlace.place}
          isResolving={selectedPlace.isResolving}
          isUnresolved={selectedPlace.isUnresolved}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: 16 },
  mapCard: {
    borderWidth: 1,
    overflow: 'hidden',
  },
  mapOverlay: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  locationCard: {
    borderWidth: 1,
    padding: 14,
    gap: 12,
  },
  divider: { height: StyleSheet.hairlineWidth, alignSelf: 'stretch' },
});
