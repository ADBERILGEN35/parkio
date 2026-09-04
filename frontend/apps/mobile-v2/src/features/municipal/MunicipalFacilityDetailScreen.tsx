import { useCallback, useMemo } from 'react';
import { Platform, ScrollView, StyleSheet, View } from 'react-native';
import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import * as Linking from 'expo-linking';
import { NotFoundError, isParkioApiError } from '@parkio/api-client';
import { appConfig } from '@/config/env';
import { AppText } from '@/components/ui/AppText';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { municipalFacilityDetailQueryOptions } from '@/data/query-options/parking';
import {
  buildMunicipalFacilityDetailFields,
  parseFacilityRouteId,
  parseOptionalDistanceMeters,
} from '@/features/municipal/municipalFacilityDetailFields';
import {
  buildParkingMapsHttpsUrl,
  buildParkingNavigationUrl,
} from '@/features/parking/parkingLocationLinks';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { formatDistance, formatShortDuration } from '@/lib/time';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { useNowTick } from '@/components/spots/FreshnessRing';

/**
 * Municipal facility detail — Pencil / Living Signal.
 * Dedicated screen (not spots/[id]). Flag-off → redirect; blank id → not-found.
 */
export function MunicipalFacilityDetailScreen() {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const { locale } = useLocale();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const now = useNowTick(60_000);
  const params = useLocalSearchParams<{ id?: string | string[]; distanceMeters?: string | string[] }>();
  const { colors } = theme;

  const facilityId = parseFacilityRouteId(
    Array.isArray(params.id) ? params.id[0] : params.id,
  );
  const distanceMeters = parseOptionalDistanceMeters(
    Array.isArray(params.distanceMeters) ? params.distanceMeters[0] : params.distanceMeters,
  );

  const municipalDiscovery = appConfig.features.municipalDiscovery;

  const detailQuery = useQuery({
    ...municipalFacilityDetailQueryOptions(facilityId),
    enabled: municipalDiscovery && facilityId.length > 0,
    retry: false,
  });

  const openInMaps = useCallback(async () => {
    const facility = detailQuery.data;
    if (!facility) return;
    const label = facility.displayName?.trim() || t('map.municipal.unnamed');
    const platform =
      Platform.OS === 'ios' ? 'ios' : Platform.OS === 'android' ? 'android' : 'default';
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
  }, [detailQuery.data, t, toast]);

  const fields = useMemo(() => {
    if (!detailQuery.data) return null;
    return buildMunicipalFacilityDetailFields(detailQuery.data, {
      unnamedLabel: t('map.municipal.unnamed'),
      distanceMeters,
    });
  }, [detailQuery.data, distanceMeters, t]);

  if (!municipalDiscovery) {
    return <Redirect href="/(main)/(tabs)/map" />;
  }

  const isNotFound =
    facilityId.length === 0 ||
    detailQuery.error instanceof NotFoundError ||
    (isParkioApiError(detailQuery.error) && detailQuery.error.status === 404);

  const showLoading = facilityId.length > 0 && detailQuery.isPending && !detailQuery.data;
  const showError =
    facilityId.length > 0 &&
    detailQuery.isError &&
    !detailQuery.data &&
    !isNotFound;
  const showNotFound = isNotFound && !showLoading;

  const occupancyLabel = fields
    ? occupancyStatusLabel(fields.occupancyKind, t)
    : t('map.municipal.occupancy.static');

  const updatedLabel =
    fields?.lastUpdatedAt && fields.showLiveMetrics
      ? formatUpdatedAgo(fields.lastUpdatedAt, now, locale, t)
      : fields?.lastUpdatedAt && fields.occupancyKind === 'stale_live'
        ? formatUpdatedAgo(fields.lastUpdatedAt, now, locale, t)
        : null;

  const a11yOccupancySummary = fields
    ? [
        occupancyLabel,
        fields.showLiveMetrics && fields.availableSpaces != null
          ? t('map.municipal.detail.a11yAvailable', {
              available: String(fields.availableSpaces),
            })
          : fields.occupancyKind === 'stale_live'
            ? t('map.municipal.availabilityStaleLive')
            : fields.occupancyKind === 'static' || fields.occupancyKind === 'invalid'
              ? t(
                  fields.occupancyKind === 'invalid'
                    ? 'map.municipal.availabilityInvalid'
                    : 'map.municipal.availabilityStatic',
                )
              : '',
        fields.sourceLine ?? '',
      ]
        .filter((part) => part.length > 0)
        .join('. ')
    : '';

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader
        title={fields?.title ?? t('map.municipal.detail.title')}
      />

      {showLoading ? (
        <View
          style={styles.loading}
          accessibilityRole="progressbar"
          accessibilityLabel={t('common.loading')}
        >
          <Skeleton height={88} radius={16} />
          <Skeleton height={140} radius={16} />
          <Skeleton height={100} radius={16} />
        </View>
      ) : showNotFound ? (
        <EmptyState
          title={t('map.municipal.detail.notFound')}
          body={t('map.municipal.detail.notFoundBody')}
          ctaLabel={t('common.back')}
          onCtaPress={() => router.back()}
        />
      ) : showError ? (
        <EmptyState
          title={t('map.municipal.detail.error')}
          body={describeApiError(detailQuery.error, t).message || t('map.municipal.detail.errorBody')}
          ctaLabel={t('common.retry')}
          onCtaPress={() => void detailQuery.refetch()}
        />
      ) : fields ? (
        <ScrollView
          contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 32 }]}
          showsVerticalScrollIndicator={false}
        >
          <Card style={styles.card}>
            <AppText variant="titleLg" accessibilityRole="header">
              {fields.title}
            </AppText>
            <View style={styles.badgeRow}>
              <Badge
                label={occupancyLabel}
                icon={occupancyBadgeIcon(fields.occupancyKind)}
                fg={occupancyBadgeColors(fields.occupancyKind, colors).fg}
                bg={occupancyBadgeColors(fields.occupancyKind, colors).bg}
                size="sm"
              />
              {fields.facilityTypeKey ? (
                <Badge
                  label={t(
                    fields.facilityTypeKey === 'onStreet'
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
            {fields.sourceLine ? (
              <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                {fields.sourceLine}
              </AppText>
            ) : null}
            {fields.distanceMeters != null ? (
              <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                {formatDistance(fields.distanceMeters, locale)}
              </AppText>
            ) : null}
          </Card>

          <View accessibilityLabel={a11yOccupancySummary}>
            <Card style={styles.card}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('map.municipal.detail.occupancySection')}
            </AppText>

            {fields.showLiveMetrics ? (
              <View style={styles.metricsGrid}>
                <Metric
                  label={t('map.municipal.detail.available')}
                  value={String(fields.availableSpaces)}
                  emphasis
                  colors={colors}
                />
                {fields.occupiedSpaces != null ? (
                  <Metric
                    label={t('map.municipal.detail.occupied')}
                    value={String(fields.occupiedSpaces)}
                    colors={colors}
                  />
                ) : null}
                {fields.capacityTotal != null ? (
                  <Metric
                    label={t('map.municipal.detail.capacity')}
                    value={String(fields.capacityTotal)}
                    colors={colors}
                  />
                ) : null}
              </View>
            ) : fields.occupancyKind === 'stale_live' ? (
              <AppText variant="bodyMd" color={colors.onSurface}>
                {t('map.municipal.availabilityStaleLive')}
              </AppText>
            ) : fields.occupancyKind === 'invalid' ? (
              <AppText variant="bodyMd" color={colors.onSurface}>
                {t('map.municipal.availabilityInvalid')}
              </AppText>
            ) : (
              <AppText variant="bodyMd" color={colors.onSurface}>
                {t('map.municipal.availabilityStatic')}
              </AppText>
            )}

            {updatedLabel ? (
              <AppText variant="labelSm" color={colors.outline}>
                {updatedLabel}
              </AppText>
            ) : null}
            </Card>
          </View>

          {fields.showLocationSection ? (
            <Card style={styles.card}>
              <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
                {t('map.municipal.detail.locationSection')}
              </AppText>
              {fields.addressText ? (
                <View style={styles.addressRow}>
                  <MaterialCommunityIcons
                    name="map-marker-outline"
                    size={16}
                    color={colors.outline}
                    accessibilityElementsHidden
                  />
                  <AppText variant="bodyMd" color={colors.onSurface} style={styles.flex}>
                    {fields.addressText}
                  </AppText>
                </View>
              ) : null}
              {fields.distanceMeters != null ? (
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {formatDistance(fields.distanceMeters, locale)}
                </AppText>
              ) : null}
              {fields.canOpenInMaps ? (
                <Button
                  label={t('map.municipal.openInMaps')}
                  variant="tonal"
                  size="md"
                  icon="map-outline"
                  onPress={() => void openInMaps()}
                  accessibilityHint={t('map.municipal.openInMapsHint')}
                />
              ) : null}
            </Card>
          ) : null}

          {fields.showFacilityInfoSection ? (
            <Card style={styles.card}>
              <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
                {t('map.municipal.detail.infoSection')}
              </AppText>
              {fields.facilityTypeKey ? (
                <InfoRow
                  label={t('map.municipal.detail.typeLabel')}
                  value={t(
                    fields.facilityTypeKey === 'onStreet'
                      ? 'map.municipal.type.onStreet'
                      : 'map.municipal.type.offStreet',
                  )}
                  colors={colors}
                />
              ) : null}
              {fields.operatorName ? (
                <InfoRow
                  label={t('map.municipal.detail.operatorLabel')}
                  value={fields.operatorName}
                  colors={colors}
                />
              ) : null}
            </Card>
          ) : null}

          {fields.sourceLine ? (
            <Card style={styles.card}>
              <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
                {t('map.municipal.detail.sourceSection')}
              </AppText>
              <AppText variant="bodyMd" color={colors.onSurface}>
                {fields.sourceLine}
              </AppText>
            </Card>
          ) : null}
        </ScrollView>
      ) : null}
    </SafeAreaView>
  );
}

function formatUpdatedAgo(
  iso: string,
  now: number,
  locale: 'tr' | 'en',
  t: ReturnType<typeof useT>,
): string {
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) return '';
  const elapsed = Math.max(0, now - then);
  if (elapsed < 60_000) {
    return t('map.municipal.detail.updatedJustNow');
  }
  return t('map.municipal.detail.updatedAgo', {
    time: formatShortDuration(elapsed, locale),
  });
}

function occupancyStatusLabel(
  kind: ReturnType<typeof buildMunicipalFacilityDetailFields>['occupancyKind'],
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

function occupancyBadgeIcon(
  kind: ReturnType<typeof buildMunicipalFacilityDetailFields>['occupancyKind'],
): keyof typeof MaterialCommunityIcons.glyphMap {
  switch (kind) {
    case 'live':
    case 'aging':
      return 'broadcast';
    case 'stale_live':
      return 'timer-sand';
    case 'invalid':
      return 'alert-circle-outline';
    case 'static':
    default:
      return 'information-outline';
  }
}

function occupancyBadgeColors(
  kind: ReturnType<typeof buildMunicipalFacilityDetailFields>['occupancyKind'],
  colors: ReturnType<typeof useTheme>['colors'],
): { fg: string; bg: string } {
  switch (kind) {
    case 'live':
    case 'aging':
      return { fg: colors.secondary, bg: colors.secondaryContainer };
    case 'stale_live':
      return { fg: colors.tertiary, bg: colors.tertiaryContainer };
    case 'invalid':
      return { fg: colors.error, bg: colors.errorContainer };
    case 'static':
    default:
      return { fg: colors.onSurfaceVariant, bg: colors.surfaceContainer2 };
  }
}

function Metric({
  label,
  value,
  emphasis,
  colors,
}: {
  label: string;
  value: string;
  emphasis?: boolean;
  colors: ReturnType<typeof useTheme>['colors'];
}) {
  return (
    <View
      style={[styles.metric, { backgroundColor: colors.surfaceContainer1 }]}
      accessibilityLabel={`${label}: ${value}`}
    >
      <AppText variant="labelSm" color={colors.onSurfaceVariant}>
        {label}
      </AppText>
      <AppText
        variant={emphasis ? 'countdownLg' : 'titleLg'}
        tabular
        color={emphasis ? colors.primary : colors.onSurface}
      >
        {value}
      </AppText>
    </View>
  );
}

function InfoRow({
  label,
  value,
  colors,
}: {
  label: string;
  value: string;
  colors: ReturnType<typeof useTheme>['colors'];
}) {
  return (
    <View style={styles.infoRow} accessibilityLabel={`${label}: ${value}`}>
      <AppText variant="bodySm" color={colors.onSurfaceVariant}>
        {label}
      </AppText>
      <AppText variant="bodyMd" color={colors.onSurface} style={styles.flex}>
        {value}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  loading: { padding: 16, gap: 12 },
  scroll: { padding: 16, gap: 12 },
  card: { gap: 12 },
  badgeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  metricsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  metric: {
    minWidth: '30%',
    flexGrow: 1,
    borderRadius: 14,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 4,
  },
  addressRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
  infoRow: { gap: 2 },
  flex: { flexShrink: 1 },
});
