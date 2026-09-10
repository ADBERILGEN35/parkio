import { StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { PressableScale } from '@/components/ui/PressableScale';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import type { MunicipalFilterEmptyReason, MunicipalFilterSummaryCounts } from './municipalFilterPipeline';

export interface MunicipalSummaryBannerProps {
  visible: boolean;
  summary: MunicipalFilterSummaryCounts;
  emptyReason: MunicipalFilterEmptyReason | null;
  resultLimitReached: boolean;
  resultLimit: number;
  loading?: boolean;
  onResetFilters?: () => void;
  showReset?: boolean;
}

/**
 * Compact municipal result summary for the map overlay.
 * Scope: current nearby query after client filters (bounded by radius + limit).
 */
export function MunicipalSummaryBanner({
  visible,
  summary,
  emptyReason,
  resultLimitReached,
  resultLimit,
  loading,
  onResetFilters,
  showReset,
}: MunicipalSummaryBannerProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;

  if (!visible) return null;

  if (loading && summary.total === 0 && emptyReason == null) {
    return (
      <Glass radius={16} style={styles.host} contentStyle={styles.content}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant} accessibilityLiveRegion="polite">
          {t('map.municipal.summary.loading')}
        </AppText>
      </Glass>
    );
  }

  if (emptyReason === 'filtered') {
    return (
      <Glass radius={16} style={styles.host} contentStyle={styles.content}>
        <AppText
          variant="bodySm"
          color={colors.onSurface}
          accessibilityLiveRegion="polite"
          accessibilityLabel={t('map.municipal.summary.filteredEmptyA11y')}
        >
          {t('map.municipal.summary.filteredEmpty')}
        </AppText>
        {showReset && onResetFilters ? (
          <PressableScale
            onPress={onResetFilters}
            accessibilityRole="button"
            accessibilityLabel={t('map.municipal.filters.reset')}
          >
            <AppText variant="labelSm" color={colors.primary}>
              {t('map.municipal.filters.reset')}
            </AppText>
          </PressableScale>
        ) : null}
      </Glass>
    );
  }

  if (emptyReason === 'none_nearby' || summary.total === 0) {
    return (
      <Glass radius={16} style={styles.host} contentStyle={styles.content}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant} accessibilityLiveRegion="polite">
          {t('map.municipal.summary.noneNearby')}
        </AppText>
      </Glass>
    );
  }

  const totalLabel = resultLimitReached
    ? t('map.municipal.summary.totalCapped', { count: resultLimit })
    : t('map.municipal.summary.total', { count: summary.total });

  const lines: string[] = [totalLabel];
  if (summary.live > 0) {
    lines.push(t('map.municipal.summary.live', { count: summary.live }));
  }
  if (summary.staticOnly > 0) {
    lines.push(t('map.municipal.summary.static', { count: summary.staticOnly }));
  }
  if (summary.staleLive > 0) {
    lines.push(t('map.municipal.summary.staleLive', { count: summary.staleLive }));
  }

  const a11y = lines.join('. ');

  return (
    <Glass radius={16} style={styles.host} contentStyle={styles.content}>
      <View
        accessible
        accessibilityRole="summary"
        accessibilityLabel={a11y}
        accessibilityLiveRegion="polite"
        style={styles.lines}
      >
        {lines.map((line) => (
          <AppText key={line} variant="bodySm" color={colors.onSurface} numberOfLines={2}>
            {line}
          </AppText>
        ))}
      </View>
    </Glass>
  );
}

const styles = StyleSheet.create({
  host: { alignSelf: 'stretch' },
  content: { paddingHorizontal: 12, paddingVertical: 10, gap: 6 },
  lines: { gap: 2 },
});
