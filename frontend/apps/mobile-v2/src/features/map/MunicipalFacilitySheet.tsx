import { useCallback, useEffect, useMemo, useRef } from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import BottomSheet, { BottomSheetScrollView } from '@gorhom/bottom-sheet';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import * as Linking from 'expo-linking';
import type { MunicipalFacility } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { presentMunicipalFacility } from '@/features/municipal/presentMunicipalFacility';
import {
  buildParkingMapsHttpsUrl,
  buildParkingNavigationUrl,
} from '@/features/parking/parkingLocationLinks';
import { useParkHereAtTarget } from '@/features/parking/useParkHereAtTarget';
import { municipalParkTarget } from '@parkio/validation';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';
import { trackNavigationStarted } from '@/services/spaTelemetry';
import { formatDistance } from '@/lib/time';
import { radius as radiusTokens, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface MunicipalFacilitySheetProps {
  facility: MunicipalFacility | null;
  distanceMeters: number | null;
  onClose: () => void;
  /**
   * Optional detail navigation for MOBILE-MUNI-V2-03.
   * When omitted, no detail CTA is rendered (avoids a dead button).
   */
  onOpenDetail?: (facilityId: string) => void;
  /** Explicit municipal Park Here when authenticated and no ACTIVE session. */
  parkHereEnabled?: boolean;
}

const PEEK_HEIGHT = 200;

/**
 * Municipal facility preview sheet — Pencil / Living Signal, modeled on SpotSheet
 * but not overloaded with PublicSpot. No raw source keys or coordinates.
 */
export function MunicipalFacilitySheet({
  facility,
  distanceMeters,
  onClose,
  onOpenDetail,
  parkHereEnabled = false,
}: MunicipalFacilitySheetProps) {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const { locale } = useLocale();
  const sheetRef = useRef<BottomSheet>(null);
  const { colors } = theme;
  const parkHere = useParkHereAtTarget();

  const snapPoints = useMemo(() => [PEEK_HEIGHT, '48%'], []);

  useEffect(() => {
    if (facility) {
      sheetRef.current?.snapToIndex(0);
    }
  }, [facility?.id, facility]);

  const handleChange = useCallback(
    (index: number) => {
      if (index === -1) {
        onClose();
      }
    },
    [onClose],
  );

  const openInMaps = useCallback(async () => {
    if (!facility) return;
    const label = facility.displayName?.trim() || t('map.municipal.unnamed');
    const platform =
      Platform.OS === 'ios' ? 'ios' : Platform.OS === 'android' ? 'android' : 'default';
    trackNavigationStarted('MUNICIPAL_FACILITY');
    try {
      const primary = buildParkingNavigationUrl(
        facility.latitude,
        facility.longitude,
        platform,
        label,
      );
      const canOpen = await Linking.canOpenURL(primary);
      await Linking.openURL(
        canOpen ? primary : buildParkingMapsHttpsUrl(facility.latitude, facility.longitude),
      );
    } catch {
      toast.show(t('map.municipal.openInMapsFailed'), 'error');
    }
  }, [facility, t, toast]);

  const onParkHere = useCallback(async () => {
    if (!facility) return;
    const label = facility.displayName?.trim() || t('map.municipal.unnamed');
    const outcome = await parkHere.start({
      latitude: facility.latitude,
      longitude: facility.longitude,
      target: municipalParkTarget(facility.id, label),
      originSurface: 'municipal_preview',
    });
    if (outcome.status === 'busy') return;
    if (outcome.status === 'success') {
      toast.show(t('parkedCar.parkHere.success'), 'success');
      parkHere.reset();
      onClose();
      return;
    }
    if (outcome.status === 'conflict') {
      toast.show(t('parkedCar.parkHere.alreadyActive'), 'neutral');
      parkHere.reset();
      return;
    }
    if (outcome.status === 'offline') {
      toast.show(t('parkingSession.start.offlineBody'), 'error');
      parkHere.reset();
      return;
    }
    toast.show(t('parkedCar.parkHere.failed'), 'error');
    parkHere.reset();
  }, [facility, onClose, parkHere, t, toast]);

  if (!facility) {
    return null;
  }

  const presented = presentMunicipalFacility(facility);
  const showType =
    presented.facilityType === 'ON_STREET' || presented.facilityType === 'OFF_STREET';
  const isLiveMetrics =
    (presented.occupancyKind === 'live' || presented.occupancyKind === 'aging') &&
    presented.availableSpaces != null;

  const occupancyBadge = occupancyBadgeVisual(presented.occupancyKind, theme);
  const occupancyLabel = occupancyStatusLabel(presented.occupancyKind, t);

  const a11ySummary = [
    presented.displayName?.trim() || t('map.municipal.unnamed'),
    occupancyLabel,
    presented.sourceLine ?? '',
  ]
    .filter(Boolean)
    .join('. ');

  return (
    <BottomSheet
      ref={sheetRef}
      snapPoints={snapPoints}
      enablePanDownToClose
      onChange={handleChange}
      backgroundStyle={[
        { backgroundColor: colors.surface, borderRadius: radiusTokens.sheet },
        shadows.ambientDeep,
      ]}
      handleIndicatorStyle={{ backgroundColor: colors.outlineVariant, width: 36 }}
      accessibilityLabel={a11ySummary}
    >
      <BottomSheetScrollView contentContainerStyle={styles.content}>
        <View style={styles.peekRow}>
          <View
            style={[
              styles.glyphWrap,
              { backgroundColor: colors.surfaceContainer2, borderColor: occupancyBadge.fg },
            ]}
            accessible={false}
          >
            <MaterialCommunityIcons
              name="parking"
              size={22}
              color={occupancyBadge.fg}
              accessibilityElementsHidden
            />
          </View>
          <View style={styles.peekLabels}>
            <AppText variant="titleMd" numberOfLines={2} accessibilityRole="header">
              {presented.displayName?.trim() || t('map.municipal.unnamed')}
            </AppText>
            <View style={styles.metaRow}>
              {presented.sourceLine ? (
                <AppText
                  variant="bodySm"
                  color={colors.onSurfaceVariant}
                  numberOfLines={1}
                  style={styles.metaText}
                >
                  {presented.sourceLine}
                  {typeof distanceMeters === 'number'
                    ? ` · ${formatDistance(distanceMeters, locale)}`
                    : ''}
                </AppText>
              ) : typeof distanceMeters === 'number' ? (
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {formatDistance(distanceMeters, locale)}
                </AppText>
              ) : null}
            </View>
          </View>
        </View>

        <View style={styles.badgeRow}>
          <Badge
            label={occupancyLabel}
            icon={occupancyBadge.icon}
            fg={occupancyBadge.fg}
            bg={occupancyBadge.bg}
            size="sm"
          />
          {showType ? (
            <Badge
              label={t(
                presented.facilityType === 'ON_STREET'
                  ? 'map.municipal.type.onStreet'
                  : 'map.municipal.type.offStreet',
              )}
              icon="road-variant"
              fg={colors.onSurfaceVariant}
              bg={colors.surfaceContainer2}
              size="sm"
            />
          ) : null}
        </View>

        {isLiveMetrics ? (
          <View style={[styles.metricCard, { backgroundColor: colors.surfaceContainer1 }]}>
            <AppText variant="countdownLg" tabular color={colors.primary}>
              {String(presented.availableSpaces)}
            </AppText>
            <AppText variant="bodySm" color={colors.onSurfaceVariant}>
              {presented.capacityTotal != null
                ? t('map.municipal.spacesAvailable', {
                    available: String(presented.availableSpaces),
                    capacity: String(presented.capacityTotal),
                  })
                : t('map.municipal.availableOnly', {
                    available: String(presented.availableSpaces),
                  })}
            </AppText>
          </View>
        ) : presented.occupancyKind === 'stale_live' ? (
          <AppText variant="bodyMd" color={colors.onSurface}>
            {t('map.municipal.availabilityStaleLive')}
          </AppText>
        ) : presented.occupancyKind === 'invalid' ? (
          <AppText variant="bodyMd" color={colors.onSurface}>
            {t('map.municipal.availabilityInvalid')}
          </AppText>
        ) : (
          <AppText variant="bodyMd" color={colors.onSurface}>
            {t('map.municipal.availabilityStatic')}
          </AppText>
        )}

        {presented.addressText?.trim() ? (
          <View style={styles.addressRow}>
            <MaterialCommunityIcons name="map-marker-outline" size={16} color={colors.outline} />
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.metaText}>
              {presented.addressText.trim()}
            </AppText>
          </View>
        ) : null}

        {parkHereEnabled ? (
          <Button
            label={
              parkHere.busy
                ? t('parkedCar.parkHere.saving')
                : t('parkedCar.parkHere.cta')
            }
            variant="primary"
            size="md"
            icon="car-outline"
            loading={parkHere.busy}
            disabled={parkHere.busy}
            onPress={() => void onParkHere()}
            accessibilityHint={t('parkedCar.parkHere.a11y')}
          />
        ) : null}

        <Button
          label={t('map.municipal.openInMaps')}
          variant="tonal"
          size="md"
          icon="map-outline"
          onPress={() => void openInMaps()}
          accessibilityHint={t('map.municipal.openInMapsHint')}
        />

        {onOpenDetail ? (
          <Button
            label={t('map.municipal.openDetail')}
            variant="ghost"
            size="md"
            icon="chevron-right"
            onPress={() => onOpenDetail(facility.id)}
            accessibilityHint={t('map.municipal.detail.title')}
          />
        ) : null}
      </BottomSheetScrollView>
    </BottomSheet>
  );
}

function occupancyStatusLabel(
  kind: ReturnType<typeof presentMunicipalFacility>['occupancyKind'],
  t: ReturnType<typeof useT>,
): string {
  switch (kind) {
    case 'aging':
      return t('map.municipal.occupancy.aging');
    case 'stale_live':
      return t('map.municipal.occupancy.staleLive');
    case 'invalid':
      return t('map.municipal.occupancy.invalid');
    case 'static':
      return t('map.municipal.occupancy.static');
    case 'live':
    default:
      return t('map.municipal.occupancy.live');
  }
}

function occupancyBadgeVisual(
  kind: ReturnType<typeof presentMunicipalFacility>['occupancyKind'],
  theme: ReturnType<typeof useTheme>,
): {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  fg: string;
  bg: string;
} {
  const { colors } = theme;
  switch (kind) {
    case 'live':
    case 'aging':
      return { icon: 'broadcast', fg: colors.secondary, bg: colors.secondaryContainer };
    case 'stale_live':
      return { icon: 'timer-sand', fg: colors.tertiary, bg: colors.tertiaryContainer };
    case 'invalid':
      return { icon: 'alert-circle-outline', fg: colors.error, bg: colors.errorContainer };
    case 'static':
    default:
      return { icon: 'information-outline', fg: colors.onSurfaceVariant, bg: colors.surfaceContainer2 };
  }
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: 16, paddingBottom: 28, gap: 12 },
  peekRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  glyphWrap: {
    width: 56,
    height: 56,
    borderRadius: 14,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  peekLabels: { flex: 1, gap: 3 },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  metaText: { flexShrink: 1 },
  badgeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  metricCard: {
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    gap: 4,
  },
  addressRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
});
