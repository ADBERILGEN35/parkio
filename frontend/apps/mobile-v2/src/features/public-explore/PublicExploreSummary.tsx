import { StyleSheet } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export interface PublicExploreSummaryProps {
  visible: boolean;
  loading: boolean;
  error: boolean;
  /** Renderable marker count — never municipalTotalInScope. */
  visibleCount: number;
  onRetry?: () => void;
}

/** Minimal Wave-1 public municipal summary (Glass overlay, mobile-v2 pattern). */
export function PublicExploreSummary({
  visible,
  loading,
  error,
  visibleCount,
  onRetry,
}: PublicExploreSummaryProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;

  if (!visible) return null;

  if (loading && visibleCount === 0 && !error) {
    return (
      <Glass radius={16} style={styles.host} contentStyle={styles.content}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant} accessibilityLiveRegion="polite">
          {t('publicExplore.summary.loading')}
        </AppText>
      </Glass>
    );
  }

  if (error) {
    return (
      <Glass radius={16} style={styles.host} contentStyle={styles.content}>
        <AppText variant="bodySm" color={colors.onSurface} accessibilityLiveRegion="polite">
          {t('publicExplore.summary.error')}
        </AppText>
        {onRetry ? (
          <AppText
            variant="labelSm"
            color={colors.primary}
            onPress={onRetry}
            accessibilityRole="button"
            accessibilityLabel={t('common.retry')}
          >
            {t('common.retry')}
          </AppText>
        ) : null}
      </Glass>
    );
  }

  if (visibleCount === 0) {
    return (
      <Glass radius={16} style={styles.host} contentStyle={styles.content}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant} accessibilityLiveRegion="polite">
          {t('publicExplore.summary.empty')}
        </AppText>
      </Glass>
    );
  }

  const label = t('publicExplore.summary.total', { count: visibleCount });
  return (
    <Glass radius={16} style={styles.host} contentStyle={styles.content}>
      <AppText
        variant="bodySm"
        color={colors.onSurface}
        accessibilityLiveRegion="polite"
        accessibilityRole="summary"
        accessibilityLabel={label}
      >
        {label}
      </AppText>
    </Glass>
  );
}

const styles = StyleSheet.create({
  host: { alignSelf: 'stretch' },
  content: { paddingHorizontal: 12, paddingVertical: 10, gap: 6 },
});
