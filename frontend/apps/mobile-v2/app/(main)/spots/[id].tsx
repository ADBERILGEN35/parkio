import { useMemo } from 'react';
import { Image, ScrollView, StyleSheet, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { DETAIL_ZOOM } from '@parkio/geo';
import type { PublicSpot, Spot } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import { CountdownText } from '@/components/spots/CountdownText';
import { FreshnessRing, useNowTick } from '@/components/spots/FreshnessRing';
import { isLiveStatus, statusVisual } from '@/components/spots/statusVisuals';
import { spotChips, spotTitle } from '@/components/spots/spotChips';
import { DecisionCard } from '@/features/moderation/decision';
import { MapSurface } from '@/features/map/MapSurface';
import { SpotActions } from '@/features/spots/SpotActions';
import { useSpotPhoto } from '@/features/spots/useSpotPhoto';
import { mySpotsQueryOptions, spotDetailQueryOptions } from '@/data/query-options/parking';
import { useT } from '@/i18n/LocaleProvider';
import {
  getRejectionPresentation,
  isLegacySystemMigrationRejection,
} from '@/lib/getRejectionPresentation';
import { formatClock, formatCountdown, remainingFraction, remainingMs } from '@/lib/time';
import { radius as radiusTokens } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

/** Full-screen spot detail (pen `Z7mqs4`, brief §12.4) with status variants. */
export default function SpotDetailScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const params = useLocalSearchParams<{ id: string }>();
  const spotId = typeof params.id === 'string' ? params.id : '';
  const now = useNowTick(1000);
  const { colors } = theme;

  const spotQuery = useQuery({
    ...spotDetailQueryOptions(spotId),
    refetchInterval: 30_000,
    retry: false,
  });

  // Ownership: the public payload is privacy-sanitized, so check membership in
  // the caller's own spots; owners get the richer counters + owner status copy.
  const mySpots = useQuery({
    ...mySpotsQueryOptions(),
    staleTime: 30_000,
  });
  const ownSpot: Spot | null = useMemo(
    () => mySpots.data?.find((candidate) => candidate.id === spotId) ?? null,
    [mySpots.data, spotId],
  );

  // Owner view falls back to /my-spots data when the public endpoint hides
  // non-discoverable states (pending/rejected are 404 for the public view).
  const spot: PublicSpot | null = spotQuery.data ?? ownSpot;
  const photo = useSpotPhoto(spot ? spotId : null);

  if (spotQuery.isLoading && !spot) {
    return (
      <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
        <View style={styles.loading}>
          <Skeleton height={300} radius={0} />
          <View style={styles.loadingBody}>
            <Skeleton height={22} width="70%" />
            <Skeleton height={16} width="45%" />
            <Skeleton height={64} />
          </View>
        </View>
      </SafeAreaView>
    );
  }

  if (!spot) {
    return (
      <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
        <EmptyState title={t('spot.notFound')} ctaLabel={t('common.back')} onCtaPress={() => router.back()} />
      </SafeAreaView>
    );
  }

  const live = isLiveStatus(spot.status);
  const fraction =
    live && spot.expiresAt ? remainingFraction(spot.createdAt, spot.expiresAt, now) : 0;
  const remaining = live && spot.expiresAt ? remainingMs(spot.expiresAt, now) : 0;
  const visual = statusVisual(spot.status, theme);
  const chips = spotChips(spot, t);
  const isOwner = ownSpot !== null;
  const dimmed = spot.status === 'FILLED' || spot.status === 'EXPIRED';
  const showActions = !isOwner && live;
  const rejection = ownSpot?.rejection ?? spot.rejection ?? null;
  const structuredRejection = Boolean(isOwner && spot.status === 'REJECTED' && rejection);
  const migrationRejection = isLegacySystemMigrationRejection(rejection);
  const rejectionPresentation =
    rejection && spot.status === 'REJECTED'
      ? getRejectionPresentation({
          status: spot.status,
          code: rejection.code,
          source: rejection.source,
          serverMessage: rejection.message,
          moderatorNote: rejection.moderatorNote,
          t,
        })
      : null;
  const statusLabel =
    rejectionPresentation?.displayStatus ?? t(`status.${spot.status}`);

  const ownerStatusCard = (() => {
    if (!isOwner) return null;
    if (spot.status === 'PENDING_VALIDATION') {
      return {
        icon: 'timer-sand-empty' as const,
        title: t('spot.pendingOwner.title'),
        body: t('spot.pendingOwner.body'),
        tone: colors.onSurfaceVariant,
        indeterminate: true,
      };
    }
    if (spot.status === 'PENDING_REVIEW') {
      return {
        icon: 'timer-sand' as const,
        title: t('spot.pendingReview.title'),
        body: t('spot.pendingReview.body'),
        tone: colors.tertiary,
        indeterminate: true,
      };
    }
    // Structured rejection metadata is rendered by DecisionCard instead.
    if (spot.status === 'REJECTED' && !rejection) {
      return {
        icon: 'close-circle-outline' as const,
        title: t('spot.rejected.title'),
        body: `${t('spot.rejected.body')} ${t('spot.rejected.penalty')}`,
        tone: colors.error,
        indeterminate: false,
      };
    }
    if (spot.status === 'REVIEW_FAILED') {
      // Deliberately not phrased as a rejection: the review never completed, which is
      // the platform's failure, so the owner is invited to resubmit rather than warned.
      return {
        icon: 'alert-circle-outline' as const,
        title: t('spot.reviewFailed.title'),
        body: `${t('spot.reviewFailed.body')} ${t('spot.reviewFailed.retry')}`,
        tone: colors.error,
        indeterminate: false,
      };
    }
    return null;
  })();

  return (
    <View style={[styles.safe, { backgroundColor: colors.background }]}>
      <ScrollView
        contentContainerStyle={[
          styles.scroll,
          { paddingBottom: insets.bottom + (showActions ? 132 : 40) },
        ]}
        showsVerticalScrollIndicator={false}
      >
        {/* Photo hero */}
        <View style={[styles.hero, { backgroundColor: colors.surfaceContainer3 }]}>
          {photo.data ? (
            <Image
              source={{ uri: photo.data }}
              style={[styles.heroImage, dimmed && styles.dimmedImage]}
              resizeMode="cover"
            />
          ) : (
            <View style={styles.heroFallback}>
              <MaterialCommunityIcons name="image-outline" size={40} color={colors.outline} />
            </View>
          )}
          <View style={[styles.heroTop, { top: insets.top + 8 }]}>
            <Glass radius={999}>
              <IconButton
                icon="arrow-left"
                size={40}
                variant="glassless"
                accessibilityLabel={t('common.back')}
                onPress={() => (router.canGoBack() ? router.back() : router.replace('/(main)/(tabs)/map'))}
              />
            </Glass>
            <Glass radius={999} contentStyle={styles.statusBadgeInner}>
              <MaterialCommunityIcons
                name={migrationRejection ? 'cog-outline' : visual.icon}
                size={14}
                color={migrationRejection ? colors.onSurfaceVariant : visual.fg}
              />
              <AppText
                variant="bodySm"
                color={migrationRejection ? colors.onSurfaceVariant : visual.fg}
              >
                {statusLabel}
              </AppText>
            </Glass>
          </View>
          {live && (
            <Glass radius={999} style={styles.heroRing} contentStyle={styles.heroRingInner}>
              <FreshnessRing fraction={fraction} size={48} strokeWidth={3}>
                <MaterialCommunityIcons name="clock-outline" size={15} color={colors.onSurfaceVariant} />
              </FreshnessRing>
              <CountdownText remainingMs={remaining} fraction={fraction} variant="titleMd" />
            </Glass>
          )}
        </View>

        <View style={styles.body}>
          {structuredRejection && (
            <View style={styles.ownerCard}>
              <DecisionCard
                rejection={rejection}
                status={spot.status}
                confidenceScore={ownSpot?.confidenceScore}
              />
              <Button
                label={t('spot.appeal')}
                variant="tonal"
                size="md"
                onPress={() => router.push('/(main)/reports')}
              />
            </View>
          )}

          {ownerStatusCard && (
            <Card tone={spot.status === 'REJECTED' ? 0 : 1} style={styles.ownerCard}>
              <View style={styles.ownerCardRow}>
                {ownerStatusCard.indeterminate ? (
                  <FreshnessRing fraction={null} size={40} strokeWidth={2.5}>
                    <MaterialCommunityIcons name={ownerStatusCard.icon} size={16} color={ownerStatusCard.tone} />
                  </FreshnessRing>
                ) : (
                  <MaterialCommunityIcons name={ownerStatusCard.icon} size={26} color={ownerStatusCard.tone} />
                )}
                <View style={styles.ownerCardLabels}>
                  <AppText variant="titleMd">{ownerStatusCard.title}</AppText>
                  <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                    {ownerStatusCard.body}
                  </AppText>
                </View>
              </View>
              {spot.status === 'REJECTED' && (
                <Button
                  label={t('spot.appeal')}
                  variant="tonal"
                  size="md"
                  onPress={() => router.push('/(main)/reports')}
                />
              )}
            </Card>
          )}

          {(spot.status === 'SUSPICIOUS' || dimmed) && (
            <Badge
              label={
                spot.status === 'SUSPICIOUS'
                  ? t('spot.suspicious.body')
                  : spot.status === 'FILLED'
                    ? t('spot.filled.body')
                    : t('spot.expired.body')
              }
              icon={visual.icon}
              fg={visual.fg}
              bg={visual.bg}
            />
          )}

          <AppText variant="headlineMd">{spotTitle(spot, t)}</AppText>

          {live && (
            <View style={styles.countdownBlock}>
              <AppText variant="countdownLg" tabular>
                {t('spot.remaining', { time: formatCountdown(remaining) })}
              </AppText>
              <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                {t('spot.countdownSub')}
              </AppText>
            </View>
          )}

          <View style={styles.chips}>
            {chips.map((chip) => (
              <Chip key={chip.key} icon={chip.icon} label={chip.label} />
            ))}
          </View>
          {spot.legalStatus === 'UNCERTAIN' && (
            <AppText variant="labelSm" color={colors.onSurfaceVariant}>
              {t('legal.advisory')}
            </AppText>
          )}

          {spot.description ? <AppText variant="bodyLg">{spot.description}</AppText> : null}

          {/* Community signal — honest timeline from real lifecycle facts. */}
          <Card tone={1} style={styles.signalCard}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('spot.communitySignal')}
            </AppText>
            <TimelineRow
              icon="camera-outline"
              title={t('spot.timeline.shared')}
              time={formatClock(spot.createdAt)}
              color={colors.primary}
              first
            />
            {spot.status === 'VERIFIED' && (
              <TimelineRow
                icon="check-decagram-outline"
                title={t('spot.timeline.verified')}
                time={formatClock(spot.updatedAt)}
                color={colors.secondary}
              />
            )}
            {isOwner && ownSpot && (
              <View style={styles.ownerCounters}>
                <Chip
                  icon="check-circle-outline"
                  label={t('spot.verifications', { count: ownSpot.verificationCount })}
                  size="sm"
                />
                <Chip
                  icon="chart-arc"
                  label={t('mySpots.confidence', { score: ownSpot.confidenceScore })}
                  size="sm"
                />
                {ownSpot.filledReportCount > 0 && (
                  <Chip
                    icon="car-off"
                    label={t('mySpots.filledReports', { count: ownSpot.filledReportCount })}
                    size="sm"
                  />
                )}
              </View>
            )}
          </Card>

          {/* Location mini-map */}
          <View style={styles.miniMapWrap}>
            <MapSurface
              initialCenter={{ lat: spot.latitude, lng: spot.longitude }}
              initialZoom={DETAIL_ZOOM}
              interactiveSpots={false}
              onReady={undefined}
              style={styles.miniMap}
            />
            <View pointerEvents="none" style={styles.miniMapPin}>
              <MaterialCommunityIcons name="map-marker" size={34} color={colors.primary} />
            </View>
          </View>
          {spot.addressText ? (
            <AppText variant="bodySm" color={colors.onSurfaceVariant}>
              {spot.addressText}
            </AppText>
          ) : null}
        </View>
      </ScrollView>

      {/* Sticky glass action bar */}
      {showActions && (
        <View style={[styles.actionBarWrap, { paddingBottom: insets.bottom + 10 }]}>
          <Glass radius={radiusTokens.sheet} contentStyle={styles.actionBarInner}>
            <SpotActions spotId={spot.id} variant="bar" />
          </Glass>
        </View>
      )}
    </View>
  );
}

function TimelineRow({
  icon,
  title,
  time,
  color,
  first,
}: {
  icon: React.ComponentProps<typeof MaterialCommunityIcons>['name'];
  title: string;
  time: string;
  color: string;
  first?: boolean;
}) {
  const theme = useTheme();
  return (
    <View style={styles.timelineRow}>
      <View style={styles.timelineRail}>
        {!first && <View style={[styles.timelineLine, { backgroundColor: theme.colors.outlineVariant }]} />}
        <View style={[styles.timelineNode, { borderColor: color, backgroundColor: theme.colors.surface }]}>
          <MaterialCommunityIcons name={icon} size={13} color={color} />
        </View>
      </View>
      <AppText variant="bodyMd" style={styles.timelineTitle}>
        {title}
      </AppText>
      <AppText variant="bodySm" tabular color={theme.colors.onSurfaceVariant}>
        {time}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  loading: { flex: 1 },
  loadingBody: { padding: 20, gap: 14 },
  scroll: {},
  hero: { height: 300 },
  heroImage: { width: '100%', height: '100%' },
  dimmedImage: { opacity: 0.55 },
  heroFallback: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  heroTop: {
    position: 'absolute',
    left: 12,
    right: 12,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  statusBadgeInner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  heroRing: { position: 'absolute', right: 12, bottom: 12 },
  heroRingInner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingLeft: 6,
    paddingRight: 14,
    paddingVertical: 6,
  },
  body: { padding: 20, gap: 14 },
  ownerCard: { gap: 12 },
  ownerCardRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  ownerCardLabels: { flex: 1, gap: 2 },
  countdownBlock: { gap: 2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  signalCard: { gap: 10 },
  ownerCounters: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, paddingTop: 4 },
  timelineRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  timelineRail: { width: 24, alignItems: 'center' },
  timelineLine: { position: 'absolute', top: -18, bottom: 18, width: 2 },
  timelineNode: {
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  timelineTitle: { flex: 1 },
  miniMapWrap: { height: 160, borderRadius: 16, overflow: 'hidden' },
  miniMap: { flex: 1 },
  miniMapPin: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionBarWrap: { position: 'absolute', left: 12, right: 12, bottom: 0 },
  actionBarInner: { paddingHorizontal: 14, paddingVertical: 12 },
});
