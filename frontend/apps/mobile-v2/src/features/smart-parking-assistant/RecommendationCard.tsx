import { Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ParkingCandidate, RecommendationReasonCode } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Chip } from '@/components/ui/Chip';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { formatDistance } from '@/lib/time';
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
}: RecommendationCardProps) {
  const t = useT();
  const { locale } = useLocale();
  const { colors } = useTheme();
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

  return (
    <Pressable
      onPress={() => onSelect(candidate)}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      accessibilityLabel={a11y}
      testID={`recommendation-card-${candidate.id}`}
      style={({ pressed }) => [
        styles.card,
        {
          backgroundColor: selected ? colors.primaryContainer : colors.surfaceContainer1,
          borderColor: selected ? colors.primary : colors.outlineVariant,
          opacity: pressed ? 0.92 : 1,
        },
      ]}
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
  );
}

const styles = StyleSheet.create({
  card: {
    gap: 6,
    padding: 12,
    borderRadius: 14,
    borderWidth: StyleSheet.hairlineWidth,
    minHeight: 88,
  },
  topRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  reasons: { flexDirection: 'row', alignItems: 'flex-start', gap: 6, marginTop: 2 },
  reasonText: { flex: 1 },
});
