import { Ionicons } from '@expo/vector-icons';
import { Stack, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  TextInput,
  View,
  useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { LatLng } from '@parkio/geo';
import { PARKING_CONTEXTS, SPOT_VEHICLE_TYPES, type ParkingContext, type SpotVehicleType } from '@parkio/types';
import { AppText, Button, Screen, StateView } from '@/components/ui';
import { MapControls } from '@/features/map/components/MapControls';
import { MapSearchBar } from '@/features/map/components/MapSearchBar';
import { MapSurface } from '@/features/map/webmap/MapSurface';
import type { MapRegion, MapSurfaceHandle } from '@/features/map/webmap/types';
import { CenterPinOverlay } from '@/features/spot-create/components/CenterPinOverlay';
import { GpsStatusPill } from '@/features/spot-create/components/GpsStatusPill';
import { SelectedLocationCard } from '@/features/spot-create/components/SelectedLocationCard';
import { useCreateSpotSubmit } from '@/features/spot-create/hooks/useCreateSpotSubmit';
import { useSelectedPlace } from '@/features/spot-create/hooks/useSelectedPlace';
import { useSpotCreationLocation } from '@/features/spot-create/hooks/useSpotCreationLocation';
import { isGpsAccuracyAcceptable } from '@/features/spot-create/lib/locationAccuracy';
import { useSpotCreationDraftStore } from '@/features/spot-create/state/spotCreationDraftStore';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { useUnsavedChangesGuard } from '@/hooks/useUnsavedChangesGuard';
import { useLocale } from '@/i18n/LocaleProvider';

const DEFAULT_CENTER: LatLng = { lat: 41.0082, lng: 28.9784 };
const PICK_ZOOM = 18;
/** Coordinates closer than ~0.1 m are the same pin — drop no-op region echoes. */
const SAME_COORD_EPSILON = 1e-6;

const VEHICLE_LABELS: Record<SpotVehicleType, string> = {
  ANY: 'Any',
  SEDAN: 'Sedan',
  HATCHBACK: 'Hatchback',
  SUV: 'SUV',
  VAN: 'Van',
  MOTORCYCLE: 'Motorcycle',
};

const PARKING_LABELS: Record<ParkingContext, string> = {
  STREET_PARKING: 'Street',
  OPEN_PARKING_LOT: 'Open lot',
  INDOOR_PARKING: 'Indoor',
  MALL_PARKING: 'Mall',
  RESIDENTIAL_AREA: 'Residential',
  OFFICE_AREA: 'Office',
  UNKNOWN: 'Unsure',
};

function sameCoord(a: LatLng | null, b: LatLng | null): boolean {
  return (
    !!a && !!b && Math.abs(a.lat - b.lat) < SAME_COORD_EPSILON && Math.abs(a.lng - b.lng) < SAME_COORD_EPSILON
  );
}

/**
 * Spot Creation, map-first. The location is picked Uber/Airbnb-style: a fixed
 * native pin marks the map center and the map moves underneath it — every
 * `moveend` becomes the new draft location, so the whole map is the touch
 * target (no fiddly marker dragging, no debug nudge buttons). GPS, search and
 * recenter all animate the camera; the pin lifts while the camera moves and
 * drops on settle. Draft state, submit flow and GPS gating are unchanged.
 */
export default function SpotCreateScreen() {
  const router = useRouter();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { height: windowHeight } = useWindowDimensions();
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const patchDraft = useSpotCreationDraftStore((state) => state.patchDraft);
  const clearDraft = useSpotCreationDraftStore((state) => state.clearDraft);
  const location = useSpotCreationLocation();
  const submit = useCreateSpotSubmit();
  const { t } = useLocale();
  const allowNextNavigation = useUnsavedChangesGuard(Boolean(draft) && !submit.isSuccess);

  const mapRef = useRef<MapSurfaceHandle>(null);
  const requestedLocationRef = useRef(false);
  // Set right before a programmatic camera move (GPS recenter / search select);
  // the matching region event is then a settle echo, not a user edit.
  const pendingCameraMoveRef = useRef(false);
  const [pinLifted, setPinLifted] = useState(false);
  // While a finger is on the map, the outer ScrollView must not steal the pan.
  const [mapInteracting, setMapInteracting] = useState(false);
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

  if (submit.isSuccess && submit.data) {
    return (
      <>
        <Stack.Screen options={{ title: t('Spot created') }} />
        <Screen>
          <StateView
            icon="checkmark-circle-outline"
            title="Spot is live"
            description="Your spot was submitted and will appear in the map flow for nearby drivers."
            actionLabel="View map"
            onAction={() => router.replace('/(main)/map')}
          >
            <Button label="Share another spot" variant="secondary" onPress={() => router.replace('/(main)/upload')} />
          </StateView>
        </Screen>
      </>
    );
  }

  if (!draft) {
    return (
      <>
        <Stack.Screen options={{ title: t('Share spot') }} />
        <Screen>
          <StateView
            icon="image-outline"
            title="Upload a photo first"
            description="Spot Creation starts after a successful parking photo upload."
            actionLabel="Upload photo"
            onAction={() => router.replace('/(main)/upload')}
          />
        </Screen>
      </>
    );
  }

  const locating = location.status === 'prompting' || location.status === 'locating';
  const locationReady =
    draft.location && (draft.manualLocationEdited || isGpsAccuracyAcceptable(draft.gpsAccuracyMeters));
  const canSubmit = Boolean(locationReady) && !submit.isPending;
  const mapHeight = Math.min(Math.max(windowHeight * 0.42, 300), 440);

  return (
    <>
      <Stack.Screen
        options={{
          title: t('Share spot'),
          headerBackTitle: t('Photo'),
        }}
      />
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Screen contentStyle={styles.content} scrollEnabled={!mapInteracting} testID="spot-create-screen">
          <View style={[styles.previewFrame, { borderColor: theme.colors.border, borderRadius: theme.radius.lg }]}>
            <Image
              source={{ uri: draft.previewUri }}
              style={styles.previewImage}
              resizeMode="cover"
              accessibilityLabel="Photo of the parking spot"
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Change photo"
              hitSlop={HIT_SLOP}
              onPress={() => {
                allowNextNavigation();
                router.replace('/(main)/upload');
              }}
              style={({ pressed }) => [
                styles.changePhoto,
                {
                  backgroundColor: pressed ? theme.colors.surfaceMuted : theme.colors.surface,
                  borderRadius: theme.radius.full,
                  ...theme.elevation.card,
                },
              ]}
            >
              <Ionicons name="camera-outline" size={16} color={theme.colors.primary} />
              <AppText variant="label" tone="primary">
                Change photo
              </AppText>
            </Pressable>
          </View>

          <View
            style={[
              styles.mapCard,
              { height: mapHeight, borderColor: theme.colors.border, borderRadius: theme.radius.xl },
            ]}
            onTouchStart={() => setMapInteracting(true)}
            onTouchEnd={() => setMapInteracting(false)}
            onTouchCancel={() => setMapInteracting(false)}
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
                  Loading map…
                </AppText>
              </View>
            ) : null}
            {mapError ? (
              <View style={[styles.mapOverlay, { backgroundColor: theme.colors.surfaceMuted }]}>
                <AppText variant="subtitle">Map failed to load</AppText>
                <AppText variant="caption" tone="muted">
                  Check your connection and try again.
                </AppText>
                <Button label="Retry map" variant="secondary" onPress={retryMap} />
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

          <ChoiceGroup
            title="Vehicle type"
            value={draft.vehicleType}
            options={SPOT_VEHICLE_TYPES}
            labels={VEHICLE_LABELS}
            onChange={(vehicleType) => patchDraft({ vehicleType })}
          />

          <ChoiceGroup
            title="Parking type"
            value={draft.parkingContext}
            options={PARKING_CONTEXTS}
            labels={PARKING_LABELS}
            onChange={(parkingContext) => patchDraft({ parkingContext })}
          />

          <View style={styles.section}>
            <AppText variant="subtitle">Note</AppText>
            <NativeNoteInput value={draft.note} onChange={(note) => patchDraft({ note })} />
          </View>
        </Screen>

        <View
          style={[
            styles.footer,
            {
              backgroundColor: theme.colors.surface,
              borderTopColor: theme.colors.border,
              paddingBottom: Math.max(insets.bottom, 12),
              paddingHorizontal: theme.spacing.gutter,
            },
          ]}
        >
          {submit.errorMessage ? (
            <AppText variant="callout" tone="danger" accessibilityRole="alert">
              {submit.errorMessage}
            </AppText>
          ) : null}
          <Button label="Share spot" onPress={() => submit.mutate()} loading={submit.isPending} disabled={!canSubmit} />
          <Button
            label="Discard draft"
            variant="ghost"
            onPress={() => {
              clearDraft();
              allowNextNavigation();
              router.replace('/(main)/upload');
            }}
            disabled={submit.isPending}
          />
        </View>
      </KeyboardAvoidingView>
    </>
  );
}

function ChoiceGroup<T extends string>({
  title,
  value,
  options,
  labels,
  onChange,
}: {
  title: string;
  value: T;
  options: readonly T[];
  labels: Record<T, string>;
  onChange: (value: T) => void;
}) {
  const theme = useTheme();
  return (
    <View style={styles.section}>
      <AppText variant="subtitle">{title}</AppText>
      <View style={styles.choiceWrap}>
        {options.map((option) => {
          const selected = option === value;
          return (
            <Pressable
              key={option}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              accessibilityLabel={`${labels[option]} ${title}`}
              onPress={() => onChange(option)}
              style={[
                styles.choice,
                {
                  minHeight: MIN_TOUCH_TARGET,
                  borderColor: selected ? theme.colors.primary : theme.colors.border,
                  backgroundColor: selected ? theme.colors.primarySoft : theme.colors.surface,
                },
              ]}
            >
              <AppText variant="label" tone={selected ? 'primary' : 'default'}>
                {labels[option]}
              </AppText>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

function NativeNoteInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const theme = useTheme();
  return (
    <TextInput
      accessibilityLabel="Optional note"
      value={value}
      onChangeText={onChange}
      placeholder="Optional: entrance, landmarks, restrictions"
      placeholderTextColor={theme.colors.textMuted}
      multiline
      maxLength={1000}
      style={[
        styles.noteInput,
        {
          color: theme.colors.text,
          backgroundColor: theme.colors.surface,
          borderColor: theme.colors.border,
        },
      ]}
    />
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  content: { gap: 16, paddingBottom: 24 },
  previewFrame: {
    height: 120,
    borderWidth: 1,
    overflow: 'hidden',
    backgroundColor: '#000',
  },
  previewImage: { width: '100%', height: '100%' },
  changePhoto: {
    position: 'absolute',
    right: 10,
    bottom: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
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
  section: { gap: 10 },
  choiceWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  choice: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  noteInput: {
    minHeight: 96,
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    textAlignVertical: 'top',
  },
  footer: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 12,
    gap: 8,
  },
});
