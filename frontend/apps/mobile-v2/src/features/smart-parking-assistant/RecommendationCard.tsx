import { Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ParkingCandidate, RecommendationReasonCode } from '@parkio/types';
import { municipalParkTarget } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { useParkHereAtTarget } from '@/features/parking/useParkHereAtTarget';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { formatDistance } from '@/lib/time';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';
import {
  MAX_VISIBLE_REASONS,
  RECOMMENDATION_REASON_I18N,
} from './recommendationPresentation';

export type RecommendationCardProps = {
  candidate: ParkingCandidate;
  rankIndex: number;
  selected: boolean;
  onSelect: (candidate: ParkingCandidate) => void;
  parkHereEnabled?: boolean;
};

function freshnessKey(freshness: string | null | undefined): TranslationKey | null {
  if (!freshness) return null;
  if (freshness === 'LIVE' || freshness === 'AGING') return 'map.municipal.occupancy.live';
  if (freshness === 'STALE_LIVE') return 'map.municipal.occupancy.staleLive';
  if (freshness === 'STATIC') return 'map.municipal.occupancy.static';
  return null;
}

export function RecommendationCard({
  candidate,
  rankIndex,
  selected,
  onSelect,
  parkHereEnabled = false,
}: RecommendationCardProps) {
  const t = useT();
  const toast = useToast();
  const { locale } = useLocale();
  const { colors } = useTheme();
  const parkHere = useParkHereAtTarget();
  const isMunicipal = candidate.channel === 'MUNICIPAL_FACILITY';
  const availability = candidate.availability;
  const reasons = (candidate.reasons ?? [])
    .map((r) => r.code)
    .filter((code): code is RecommendationReasonCode => Boolean(code))
    .slice(0, MAX_VISIBLE_REASONS);

  const channelLabel = isMunicipal
    ? t('assistant.channelMunicipal')
    : t('assistant.channelCommunity');
  const rank =
    rankIndex === 0
      ? t('assistant.rankRecommended')
      : t('assistant.rankOption', { n: rankIndex + 1 });
  const freshKey = freshnessKey(availability?.freshness ?? null);
  const fresh = freshKey ? t(freshKey) : null;
  const spaces =
    availability?.availableSpaces != null && availability?.capacityTotal != null
      ? t('map.municipal.spacesAvailable', {
          available: availability.availableSpaces,
          capacity: availability.capacityTotal,
        })
      : availability?.availableSpaces != null
        ? t('map.municipal.availableOnly', { available: availability.availableSpaces })
        : null;

  const reasonText = reasons.map((code) => t(RECOMMENDATION_REASON_I18N[code])).join(', ');
  const a11y = [
    rank,
    candidate.title,
    channelLabel,
    formatDistance(candidate.distanceMeters, locale),
    fresh,
    spaces,
    reasonText,
  ]
    .filter(Boolean)
    .join('. ');

  const showParkHere = parkHereEnabled && isMunicipal && Boolean(candidate.refId?.trim());

  const onParkHere = async () => {
    const outcome = await parkHere.start({
      latitude: candidate.latitude,
      longitude: candidate.longitude,
      target: municipalParkTarget(candidate.refId, candidate.title),
    });
    if (outcome.status === 'busy') return;
    if (outcome.status === 'success') {
      toast.show(t('parkedCar.parkHere.success'), 'success');
      parkHere.reset();
      return;
    }
    if (outcome.status === 'conflict') {
      toast.show(t('parkedCar.parkHere.alreadyActive'), 'neutral');
      parkHere.reset();
      return;
    }
    toast.show(t('parkedCar.parkHere.failed'), 'error');
    parkHere.reset();
  };

  return (
    <View
      style={[
        styles.card,
        {
          backgroundColor: selected ? colors.primaryContainer : colors.surfaceContainer1,
          borderColor: selected ? colors.primary : colors.outlineVariant,
        },
      ]}
      testID={`recommendation-card-${candidate.id}`}
    >
      <Pressable
        onPress={() => onSelect(candidate)}
        accessibilityRole="button"
        accessibilityState={{ selected }}
        accessibilityLabel={a11y}
        style={({ pressed }) => [{ opacity: pressed ? 0.92 : 1 }]}
      >
        <View style={styles.topRow}>
          <Chip label={rank} />
          <AppText variant="labelSm" color={colors.onSurfaceVariant}>
            {channelLabel}
          </AppText>
        </View>
        <AppText variant="titleMd" numberOfLines={2}>
          {candidate.title}
        </AppText>
        <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={2}>
          {[
            formatDistance(candidate.distanceMeters, locale),
            fresh,
            spaces,
            candidate.sourceLabel,
          ]
            .filter(Boolean)
            .join(' · ')}
        </AppText>
        {reasons.length > 0 ? (
          <View style={styles.reasons}>
            <MaterialCommunityIcons name="lightbulb-outline" size={14} color={colors.primary} />
            <AppText variant="bodySm" color={colors.onSurface} style={styles.reasonText}>
              {reasonText}
            </AppText>
          </View>
        ) : null}
      </Pressable>
      {showParkHere ? (
        <Button
          label={
            parkHere.busy ? t('parkedCar.parkHere.saving') : t('parkedCar.parkHere.cta')
          }
          size="sm"
          variant="tonal"
          loading={parkHere.busy}
          disabled={parkHere.busy}
          onPress={() => void onParkHere()}
          accessibilityHint={t('parkedCar.parkHere.a11y')}
          testID={`park-here-recommendation-${candidate.id}`}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
    borderRadius: 16,
    padding: 12,
    gap: 8,
  },
  topRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  reasons: { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
  reasonText: { flex: 1 },
});
