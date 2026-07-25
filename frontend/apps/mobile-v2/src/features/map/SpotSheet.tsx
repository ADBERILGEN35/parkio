import { useCallback, useEffect, useMemo, useRef } from 'react';
import { Image, StyleSheet, View } from 'react-native';
import BottomSheet, { BottomSheetScrollView } from '@gorhom/bottom-sheet';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { PublicSpot } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { CountdownText } from '@/components/spots/CountdownText';
import { FreshnessRing, useNowTick } from '@/components/spots/FreshnessRing';
import { isLiveStatus, statusVisual } from '@/components/spots/statusVisuals';
import { spotChips, spotTitle } from '@/components/spots/spotChips';
import { SpotActions } from '@/features/spots/SpotActions';
import { useSpotPhoto } from '@/features/spots/useSpotPhoto';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { formatDistance, formatSharedAgo, remainingFraction, remainingMs } from '@/lib/time';
import { radius as radiusTokens, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface SpotSheetProps {
  spot: PublicSpot | null;
  distanceMeters: number | null;
  onClose: () => void;
  onOpenDetail: (spotId: string) => void;
}

const PEEK_HEIGHT = 176;

/**
 * The map spot bottom sheet (pen `VrgtM` peek / `xHdwd` expanded): peek shows
 * the evidence essentials + two actions; dragging up reveals the full photo,
 * chips, description and the detail link.
 */
export function SpotSheet({ spot, distanceMeters, onClose, onOpenDetail }: SpotSheetProps) {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const sheetRef = useRef<BottomSheet>(null);
  const now = useNowTick(1000);
  const photo = useSpotPhoto(spot?.id);
  const { colors } = theme;

  const snapPoints = useMemo(() => [PEEK_HEIGHT, '62%'], []);

  useEffect(() => {
    if (spot) {
      sheetRef.current?.snapToIndex(0);
    }
  }, [spot?.id, spot]);

  const handleChange = useCallback(
    (index: number) => {
      if (index === -1) {
        onClose();
      }
    },
    [onClose],
  );

  if (!spot) {
    return null;
  }

  const live = isLiveStatus(spot.status);
  const fraction =
    live && spot.expiresAt ? remainingFraction(spot.createdAt, spot.expiresAt, now) : 0;
  const remaining = live && spot.expiresAt ? remainingMs(spot.expiresAt, now) : 0;
  const visual = statusVisual(spot.status, theme);
  const chips = spotChips(spot, t);

  return (
    <BottomSheet
      ref={sheetRef}
      snapPoints={snapPoints}
      enablePanDownToClose
      onChange={handleChange}
      backgroundStyle={[{ backgroundColor: colors.surface, borderRadius: radiusTokens.sheet }, shadows.ambientDeep]}
      handleIndicatorStyle={{ backgroundColor: colors.outlineVariant, width: 36 }}
    >
      <BottomSheetScrollView contentContainerStyle={styles.content}>
        {/* Peek block */}
        <View style={styles.peekRow}>
          <View style={[styles.thumbWrap, { backgroundColor: colors.surfaceContainer2 }]}>
            {photo.data ? (
              <Image source={{ uri: photo.data }} style={styles.thumb} resizeMode="cover" />
            ) : (
              <MaterialCommunityIcons name="image-outline" size={22} color={colors.outline} />
            )}
          </View>
          <View style={styles.peekLabels}>
            <AppText variant="titleMd" numberOfLines={2}>
              {spotTitle(spot, t)}
            </AppText>
            <View style={styles.metaRow}>
              <MaterialCommunityIcons name={visual.icon} size={13} color={visual.fg} />
              <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1} style={styles.metaText}>
                {t(`status.${spot.status}`)}
                {typeof distanceMeters === 'number' ? ` · ${formatDistance(distanceMeters, locale)}` : ''}
              </AppText>
            </View>
          </View>
          {live && (
            <View style={styles.ringWrap}>
              <FreshnessRing fraction={fraction} size={48} strokeWidth={3}>
                <MaterialCommunityIcons name="clock-outline" size={16} color={colors.onSurfaceVariant} />
              </FreshnessRing>
              <CountdownText remainingMs={remaining} fraction={fraction} variant="labelSm" />
            </View>
          )}
        </View>

        <SpotActions spotId={spot.id} variant="peek" />

        {/* Expanded content */}
        <View style={styles.expanded}>
          <View style={[styles.photoCard, { backgroundColor: colors.surfaceContainer2 }]}>
            {photo.data ? (
              <Image source={{ uri: photo.data }} style={styles.photo} resizeMode="cover" />
            ) : (
              <View style={styles.photoFallback}>
                <MaterialCommunityIcons name="image-outline" size={30} color={colors.outline} />
              </View>
            )}
          </View>
          <AppText variant="bodySm" color={colors.onSurfaceVariant}>
            {formatSharedAgo(spot.createdAt, now, locale, t)}
          </AppText>
          {spot.description ? <AppText variant="bodyMd">{spot.description}</AppText> : null}
          <View style={styles.chips}>
            {chips.map((chip) => (
              <Chip key={chip.key} icon={chip.icon} label={chip.label} size="sm" />
            ))}
          </View>
          <Button
            label={t('spot.communitySignal')}
            variant="ghost"
            size="md"
            icon="chevron-right"
            onPress={() => onOpenDetail(spot.id)}
          />
        </View>
      </BottomSheetScrollView>
    </BottomSheet>
  );
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: 16, paddingBottom: 28, gap: 12 },
  peekRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  thumbWrap: {
    width: 56,
    height: 56,
    borderRadius: 14,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumb: { width: 56, height: 56 },
  peekLabels: { flex: 1, gap: 3 },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  metaText: { flexShrink: 1 },
  ringWrap: { alignItems: 'center', gap: 2 },
  // Enough top padding that the photo card stays below the fold at peek height.
  expanded: { gap: 10, paddingTop: 16 },
  photoCard: { height: 170, borderRadius: 16, overflow: 'hidden' },
  photo: { width: '100%', height: '100%' },
  photoFallback: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
