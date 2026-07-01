import { Ionicons } from '@expo/vector-icons';
import BottomSheet, { BottomSheetView } from '@gorhom/bottom-sheet';
import { memo, useCallback, useEffect, useMemo, useRef } from 'react';
import { Image, Linking, Platform, StyleSheet, View } from 'react-native';
import { formatDistance, formatRelativeTime, presentSpot, type SpotWithDistance } from '@parkio/geo';
import { AppText, Badge, Button } from '@/components/ui';
import type { BadgeTone } from '@/components/ui';
import { useTheme } from '@/theme';
import { useSpotPhoto } from '../hooks/useSpotPhoto';

export interface SpotSheetProps {
  spot: SpotWithDistance | null;
  onClose: () => void;
}

const TONE_TO_BADGE: Record<ReturnType<typeof presentSpot>['tone'], BadgeTone> = {
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

function openDirections(spot: SpotWithDistance) {
  const label = encodeURIComponent(spot.addressText ?? 'Parking spot');
  const { latitude: lat, longitude: lng } = spot;
  const url = Platform.select({
    ios: `maps://?daddr=${lat},${lng}&q=${label}`,
    android: `geo:${lat},${lng}?q=${lat},${lng}(${label})`,
    default: `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=18/${lat}/${lng}`,
  });
  void Linking.openURL(url).catch(() => {
    void Linking.openURL(`https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=18/${lat}/${lng}`);
  });
}

/**
 * Spot detail bottom sheet (@gorhom/bottom-sheet): draggable, snap points,
 * pan-to-close, restore on reselect, keyboard-safe. All facts shown are real
 * `PublicSpot` fields — availability/confidence are projected from the spot's
 * status via the shared {@link presentSpot} (no fabricated values).
 */
function SpotSheetImpl({ spot, onClose }: SpotSheetProps) {
  const theme = useTheme();
  const sheetRef = useRef<BottomSheet>(null);
  const snapPoints = useMemo(() => ['32%', '70%'], []);
  const photo = useSpotPhoto(spot?.id ?? null);

  useEffect(() => {
    if (spot) sheetRef.current?.snapToIndex(0);
    else sheetRef.current?.close();
  }, [spot]);

  const handleChange = useCallback(
    (index: number) => {
      if (index === -1 && spot) onClose();
    },
    [onClose, spot],
  );

  const presentation = spot ? presentSpot(spot) : null;

  return (
    <BottomSheet
      ref={sheetRef}
      index={-1}
      snapPoints={snapPoints}
      enablePanDownToClose
      onChange={handleChange}
      backgroundStyle={{ backgroundColor: theme.colors.surface }}
      handleIndicatorStyle={{ backgroundColor: theme.colors.borderStrong }}
    >
      <BottomSheetView style={styles.content}>
        {spot && presentation ? (
          <View style={styles.body} accessible accessibilityLabel={`Parking spot, ${presentation.availabilityLabel}`}>
            <View style={styles.headerRow}>
              <Badge label={presentation.availabilityLabel} tone={TONE_TO_BADGE[presentation.tone]} />
              <Badge label={presentation.confidenceLabel} tone="neutral" />
            </View>

            <AppText variant="title" numberOfLines={2}>
              {spot.addressText ?? 'Parking spot'}
            </AppText>

            <View style={styles.metaRow}>
              {spot.distanceMeters !== null ? (
                <Meta icon="walk-outline" text={formatDistance(spot.distanceMeters)} />
              ) : null}
              <Meta icon="time-outline" text={`Updated ${formatRelativeTime(spot.updatedAt)}`} />
              <Meta
                icon={presentation.legalLabel.startsWith('Legal') ? 'shield-checkmark-outline' : 'alert-circle-outline'}
                text={presentation.legalLabel}
              />
            </View>

            {photo.url ? (
              <Image
                source={{ uri: photo.url }}
                style={[styles.photo, { backgroundColor: theme.colors.surfaceMuted }]}
                accessibilityIgnoresInvertColors
                accessibilityLabel="Photo of the parking spot"
              />
            ) : null}

            {spot.description ? (
              <AppText variant="body" tone="muted">
                {spot.description}
              </AppText>
            ) : null}

            <Button
              label="Get directions"
              testID="map.spot.directions"
              leading={<Ionicons name="navigate" size={18} color={theme.colors.onPrimary} />}
              onPress={() => openDirections(spot)}
            />
          </View>
        ) : null}
      </BottomSheetView>
    </BottomSheet>
  );
}

function Meta({ icon, text }: { icon: keyof typeof Ionicons.glyphMap; text: string }) {
  const theme = useTheme();
  return (
    <View style={styles.meta}>
      <Ionicons name={icon} size={15} color={theme.colors.textMuted} />
      <AppText variant="caption" tone="muted">
        {text}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { flex: 1 },
  body: { paddingHorizontal: 20, paddingTop: 4, paddingBottom: 28, gap: 14 },
  headerRow: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  metaRow: { gap: 8 },
  meta: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  photo: { width: '100%', height: 160, borderRadius: 12 },
});

export const SpotSheet = memo(SpotSheetImpl);
