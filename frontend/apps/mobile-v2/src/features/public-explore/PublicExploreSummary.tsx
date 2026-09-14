import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

/** Discovery origin semantics for truthful summary copy. */
export type PublicExploreSummaryOrigin = 'user-location' | 'area';

export interface PublicExploreSummaryProps {
  visible: boolean;
  loading: boolean;
  error: boolean;
  /** Renderable marker count — never municipalTotalInScope. */
  visibleCount: number;
  /**
   * `user-location` only when real GPS succeeded and no destination is selected.
   * Destination and default/fallback origins use `area`.
   */
  origin: PublicExploreSummaryOrigin;
  /**
   * Server-owned communitySpotCountInScope.
   * null = privacy threshold / absent → show nothing (never invent "0").
   */
  communitySpotCountInScope?: number | null;
  /** Server-owned municipalHiddenCount — compact +M when > 0 (auth-gated). */
  municipalHiddenCount?: number;
  onMunicipalHiddenPress?: () => void;
  onCommunityPress?: () => void;
  onRetry?: () => void;
}

/** Compact public municipal summary (Glass overlay, mobile-v2 pattern). */
export function PublicExploreSummary({
  visible,
  loading,
  error,
  visibleCount,
  origin,
  communitySpotCountInScope = null,
  municipalHiddenCount = 0,
  onMunicipalHiddenPress,
  onCommunityPress,
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

  const primaryKey =
    origin === 'user-location'
      ? 'publicExplore.summary.nearYou'
      : 'publicExplore.summary.inArea';
  const primaryLabel = t(primaryKey, { count: visibleCount });

  const showCommunity = communitySpotCountInScope != null;
  const communityLabel = showCommunity
    ? t('publicExplore.summary.communitySupport', { count: communitySpotCountInScope })
    : null;

  const showHidden = municipalHiddenCount > 0;
  const hiddenLabel = showHidden
    ? t('publicExplore.teaser.municipalHidden', { count: municipalHiddenCount })
    : null;

  const a11yParts = [primaryLabel];
  if (communityLabel) a11yParts.push(communityLabel);
  if (hiddenLabel) a11yParts.push(hiddenLabel);

  return (
    <Glass radius={16} style={styles.host} contentStyle={styles.content}>
      <View accessibilityLiveRegion="polite" accessibilityRole="summary" accessibilityLabel={a11yParts.join('. ')}>
        <AppText variant="bodySm" color={colors.onSurface}>
          {primaryLabel}
        </AppText>

        {showCommunity && communityLabel ? (
          onCommunityPress ? (
            <Pressable
              onPress={onCommunityPress}
              accessibilityRole="button"
              accessibilityLabel={communityLabel}
              hitSlop={6}
              style={styles.secondaryPress}
            >
              <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                {communityLabel}
              </AppText>
            </Pressable>
          ) : (
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.secondary}>
              {communityLabel}
            </AppText>
          )
        ) : null}

        {showHidden && hiddenLabel ? (
          onMunicipalHiddenPress ? (
            <Pressable
              onPress={onMunicipalHiddenPress}
              accessibilityRole="button"
              accessibilityLabel={hiddenLabel}
              hitSlop={8}
              style={styles.secondaryPress}
            >
              <AppText variant="bodySm" color={colors.primary}>
                {hiddenLabel}
              </AppText>
            </Pressable>
          ) : (
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.secondary}>
              {hiddenLabel}
            </AppText>
          )
        ) : null}
      </View>
    </Glass>
  );
}

const styles = StyleSheet.create({
  host: { alignSelf: 'stretch' },
  content: { paddingHorizontal: 12, paddingVertical: 10, gap: 4 },
  secondary: { marginTop: 2 },
  secondaryPress: { marginTop: 2, alignSelf: 'flex-start' },
});
