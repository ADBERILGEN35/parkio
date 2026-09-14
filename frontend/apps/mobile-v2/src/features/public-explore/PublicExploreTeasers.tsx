import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export interface PublicExploreTeasersProps {
  visible: boolean;
  /** Server-owned municipalHiddenCount — show only when > 0. */
  municipalHiddenCount: number;
  /**
   * Server-owned communitySpotCountInScope.
   * null = privacy threshold / absent → show nothing (never invent "0").
   */
  communitySpotCountInScope: number | null;
  onMunicipalHiddenPress: () => void;
  onCommunityPress: () => void;
}

/**
 * Compact membership teasers for anonymous Explore.
 * Interactive Glass chips — no private fetches, no community pins.
 */
export function PublicExploreTeasers({
  visible,
  municipalHiddenCount,
  communitySpotCountInScope,
  onMunicipalHiddenPress,
  onCommunityPress,
}: PublicExploreTeasersProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;

  if (!visible) return null;

  const showHidden = municipalHiddenCount > 0;
  const showCommunity = communitySpotCountInScope != null;

  if (!showHidden && !showCommunity) return null;

  const hiddenLabel = showHidden
    ? t('publicExplore.teaser.municipalHidden', { count: municipalHiddenCount })
    : null;
  const communityLabel = showCommunity
    ? t('publicExplore.teaser.community', { count: communitySpotCountInScope })
    : null;

  return (
    <View style={styles.stack} accessibilityRole="summary">
      {showHidden && hiddenLabel ? (
        <Glass radius={16} style={styles.host} contentStyle={styles.content}>
          <Pressable
            onPress={onMunicipalHiddenPress}
            accessibilityRole="button"
            accessibilityLabel={hiddenLabel}
            hitSlop={8}
            style={styles.pressable}
          >
            <AppText variant="bodySm" color={colors.primary}>
              {hiddenLabel}
            </AppText>
          </Pressable>
        </Glass>
      ) : null}

      {showCommunity && communityLabel ? (
        <Glass radius={16} style={styles.host} contentStyle={styles.content}>
          <Pressable
            onPress={onCommunityPress}
            accessibilityRole="button"
            accessibilityLabel={communityLabel}
            hitSlop={8}
            style={styles.pressable}
          >
            <AppText variant="bodySm" color={colors.onSurface}>
              {communityLabel}
            </AppText>
          </Pressable>
        </Glass>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { alignSelf: 'stretch', gap: 8 },
  host: { alignSelf: 'stretch' },
  content: { paddingHorizontal: 12, paddingVertical: 10 },
  pressable: { alignSelf: 'stretch' },
});
