import { Pressable, StyleSheet, View } from 'react-native';
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
  /** Server-owned municipalHiddenCount — compact +M when > 0 (auth-gated). */
  municipalHiddenCount?: number;
  onMunicipalHiddenPress?: () => void;
  onRetry?: () => void;
}

/** Minimal Wave-1 public municipal summary (Glass overlay, mobile-v2 pattern). */
export function PublicExploreSummary({
  visible,
  loading,
  error,
  visibleCount,
  municipalHiddenCount = 0,
  onMunicipalHiddenPress,
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

  const totalLabel = t('publicExplore.summary.total', { count: visibleCount });
  const showHidden = municipalHiddenCount > 0;
  const hiddenLabel = showHidden
    ? t('publicExplore.teaser.municipalHidden', { count: municipalHiddenCount })
    : null;
  const a11yLabel = showHidden && hiddenLabel ? `${totalLabel} • ${hiddenLabel}` : totalLabel;

  return (
    <Glass radius={16} style={styles.host} contentStyle={styles.content}>
      <View
        style={styles.row}
        accessibilityLiveRegion="polite"
        accessibilityRole="summary"
        accessibilityLabel={a11yLabel}
      >
        <AppText variant="bodySm" color={colors.onSurface}>
          {totalLabel}
        </AppText>
        {showHidden && hiddenLabel ? (
          <>
            <AppText variant="bodySm" color={colors.onSurfaceVariant}>
              {' • '}
            </AppText>
            {onMunicipalHiddenPress ? (
              <Pressable
                onPress={onMunicipalHiddenPress}
                accessibilityRole="button"
                accessibilityLabel={hiddenLabel}
                hitSlop={8}
              >
                <AppText variant="bodySm" color={colors.primary}>
                  {hiddenLabel}
                </AppText>
              </Pressable>
            ) : (
              <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                {hiddenLabel}
              </AppText>
            )}
          </>
        ) : null}
      </View>
    </Glass>
  );
}

const styles = StyleSheet.create({
  host: { alignSelf: 'stretch' },
  content: { paddingHorizontal: 12, paddingVertical: 10, gap: 6 },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center' },
});
