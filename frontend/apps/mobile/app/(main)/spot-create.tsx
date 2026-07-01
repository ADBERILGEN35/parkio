import { Stack, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef } from 'react';
import { Image, KeyboardAvoidingView, Platform, Pressable, StyleSheet, TextInput, View } from 'react-native';
import type { LatLng } from '@parkio/geo';
import { PARKING_CONTEXTS, SPOT_VEHICLE_TYPES, type ParkingContext, type SpotVehicleType } from '@parkio/types';
import { AppText, Button, Screen, StateView } from '@/components/ui';
import { MapSurface } from '@/features/map/webmap/MapSurface';
import { useCreateSpotSubmit } from '@/features/spot-create/hooks/useCreateSpotSubmit';
import { useSpotCreationLocation } from '@/features/spot-create/hooks/useSpotCreationLocation';
import {
  WARNING_GPS_ACCURACY_METERS,
  formatAccuracy,
  isGpsAccuracyAcceptable,
} from '@/features/spot-create/lib/locationAccuracy';
import { useSpotCreationDraftStore } from '@/features/spot-create/state/spotCreationDraftStore';
import { MIN_TOUCH_TARGET, useTheme } from '@/theme';

const DEFAULT_CENTER: LatLng = { lat: 41.0082, lng: 28.9784 };
const MARKER_NUDGE_DEGREES = 0.00004;

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

export default function SpotCreateScreen() {
  const router = useRouter();
  const theme = useTheme();
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const patchDraft = useSpotCreationDraftStore((state) => state.patchDraft);
  const clearDraft = useSpotCreationDraftStore((state) => state.clearDraft);
  const location = useSpotCreationLocation();
  const submit = useCreateSpotSubmit();
  const requestedLocationRef = useRef(false);
  const draftLocation = draft?.location ?? null;

  useEffect(() => {
    if (!draft || draft.location || requestedLocationRef.current) return;
    requestedLocationRef.current = true;
    void location.acquire().then((fix) => {
      if (!fix) return;
      patchDraft({
        location: fix.center,
        gpsAccuracyMeters: fix.accuracyMeters,
        manualLocationEdited: false,
      });
    });
  }, [draft, location, patchDraft]);

  const retryLocation = useCallback(() => {
    void location.acquire().then((fix) => {
      if (!fix) return;
      patchDraft({
        location: fix.center,
        gpsAccuracyMeters: fix.accuracyMeters,
        manualLocationEdited: false,
      });
    });
  }, [location, patchDraft]);

  const moveMarker = useCallback(
    (center: LatLng) => {
      patchDraft({ location: center, manualLocationEdited: true });
    },
    [patchDraft],
  );

  const nudgeMarker = useCallback(
    (delta: Partial<LatLng>) => {
      if (!draftLocation) return;
      moveMarker({
        lat: draftLocation.lat + (delta.lat ?? 0),
        lng: draftLocation.lng + (delta.lng ?? 0),
      });
    },
    [draftLocation, moveMarker],
  );

  if (submit.isSuccess && submit.data) {
    return (
      <>
        <Stack.Screen options={{ title: 'Spot created' }} />
        <Screen>
          <StateView
            glyph="✓"
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
        <Stack.Screen options={{ title: 'Create spot' }} />
        <Screen>
          <StateView
            glyph="!"
            title="Upload a photo first"
            description="Spot Creation starts after a successful parking photo upload."
            actionLabel="Upload photo"
            onAction={() => router.replace('/(main)/upload')}
          />
        </Screen>
      </>
    );
  }

  const locationReady = draft.location && isGpsAccuracyAcceptable(draft.gpsAccuracyMeters);
  const canSubmit = Boolean(locationReady) && !submit.isPending;
  const showLowAccuracy =
    draft.gpsAccuracyMeters !== null && draft.gpsAccuracyMeters > WARNING_GPS_ACCURACY_METERS;

  return (
    <>
      <Stack.Screen
        options={{
          title: 'Create spot',
          headerBackTitle: 'Upload',
        }}
      />
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Screen contentStyle={styles.content} testID="spot-create-screen">
          <View style={styles.header}>
            <AppText variant="title">Place the spot</AppText>
            <AppText variant="body" tone="muted">
              Confirm the GPS fix, adjust the marker, then describe what fits here.
            </AppText>
          </View>

          <View style={[styles.previewFrame, { borderColor: theme.colors.border }]}>
            <Image source={{ uri: draft.previewUri }} style={styles.previewImage} resizeMode="cover" />
          </View>

          <View style={styles.section}>
            <View style={styles.sectionHeader}>
              <AppText variant="subtitle">GPS location</AppText>
              <AppText variant="caption" tone={locationReady ? 'success' : 'danger'}>
                {formatAccuracy(draft.gpsAccuracyMeters)}
              </AppText>
            </View>
            {location.status === 'prompting' || location.status === 'locating' ? (
              <AppText variant="callout" tone="muted" accessibilityRole="alert">
                Reading current GPS location…
              </AppText>
            ) : null}
            {location.status === 'denied' || location.status === 'blocked' ? (
              <View style={[styles.alertBox, { backgroundColor: theme.colors.dangerSoft }]}>
                <AppText variant="callout" tone="danger" accessibilityRole="alert">
                  Location permission is required to create a spot.
                </AppText>
                <Button
                  label={location.status === 'blocked' ? 'Open Settings' : 'Retry location'}
                  variant="secondary"
                  onPress={location.status === 'blocked' ? location.openSettings : retryLocation}
                />
              </View>
            ) : null}
            {location.status === 'unavailable' || location.error ? (
              <View style={[styles.alertBox, { backgroundColor: theme.colors.dangerSoft }]}>
                <AppText variant="callout" tone="danger" accessibilityRole="alert">
                  {location.error ?? 'GPS is unavailable. Try again.'}
                </AppText>
                <Button label="Retry GPS" variant="secondary" onPress={retryLocation} />
              </View>
            ) : null}
            {showLowAccuracy ? (
              <View style={[styles.alertBox, { backgroundColor: theme.colors.warningSoft }]}>
                <AppText variant="callout" tone={locationReady ? 'default' : 'danger'} accessibilityRole="alert">
                  {locationReady
                    ? 'Accuracy is usable, but a better fix helps drivers find the spot faster.'
                    : 'GPS accuracy is too low to submit. Move outside or retry before publishing.'}
                </AppText>
                <Button label="Refresh GPS" variant="secondary" onPress={retryLocation} />
              </View>
            ) : null}
          </View>

          <View style={[styles.mapShell, { borderColor: theme.colors.border }]}>
            <MapSurface
              initialCenter={draft.location ?? DEFAULT_CENTER}
              initialZoom={17}
              spots={[]}
              selectedSpotId={null}
              userLocation={draft.location}
              draftMarker={draft.location}
              onDraftMarkerChange={moveMarker}
              clusterSpots={false}
            />
          </View>
          <View style={styles.nudgeGrid} accessibilityLabel="Fine adjust marker">
            <Button label="Up" variant="secondary" onPress={() => nudgeMarker({ lat: MARKER_NUDGE_DEGREES })} />
            <View style={styles.nudgeRow}>
              <Button label="Left" variant="secondary" onPress={() => nudgeMarker({ lng: -MARKER_NUDGE_DEGREES })} />
              <Button label="Right" variant="secondary" onPress={() => nudgeMarker({ lng: MARKER_NUDGE_DEGREES })} />
            </View>
            <Button label="Down" variant="secondary" onPress={() => nudgeMarker({ lat: -MARKER_NUDGE_DEGREES })} />
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

          {submit.errorMessage ? (
            <AppText variant="callout" tone="danger" accessibilityRole="alert">
              {submit.errorMessage}
            </AppText>
          ) : null}

          {submit.isPending ? (
            <View style={[styles.alertBox, { backgroundColor: theme.colors.primarySoft }]}>
              <AppText variant="callout" tone="primary" accessibilityRole="alert">
                Publishing spot…
              </AppText>
            </View>
          ) : null}

          <View style={styles.actions}>
            <Button
              label="Submit spot"
              onPress={() => submit.mutate()}
              loading={submit.isPending}
              disabled={!canSubmit}
            />
            <Button
              label="Discard draft"
              variant="ghost"
              onPress={() => {
                clearDraft();
                router.replace('/(main)/upload');
              }}
              disabled={submit.isPending}
            />
          </View>
        </Screen>
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
  content: { gap: 18, paddingBottom: 28 },
  header: { gap: 6 },
  previewFrame: {
    height: 180,
    borderWidth: 1,
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: '#000',
  },
  previewImage: { width: '100%', height: '100%' },
  section: { gap: 10 },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  alertBox: { gap: 10, borderRadius: 12, padding: 12 },
  mapShell: {
    height: 260,
    minHeight: 220,
    borderWidth: 1,
    borderRadius: 12,
    overflow: 'hidden',
  },
  nudgeGrid: { gap: 8 },
  nudgeRow: { flexDirection: 'row', gap: 8 },
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
  actions: { gap: 10 },
});
