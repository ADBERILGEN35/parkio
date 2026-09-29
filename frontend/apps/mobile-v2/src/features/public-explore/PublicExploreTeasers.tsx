import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export interface PublicExploreTeasersProps {
  visible: boolean;
  /**
   * Server-owned communitySpotCountInScope.
   * null = privacy threshold / absent → show nothing (never invent "0").
   */
  communitySpotCountInScope: number | null;
  onCommunityPress: () => void;
}

/**
 * Compact membership teasers for anonymous Explore.
 * Municipal +M lives on PublicExploreSummary (pressable suffix).
 * Interactive Glass chips — no private fetches, no community pins.
 */
export function PublicExploreTeasers({
  visible,
  communitySpotCountInScope,
  onCommunityPress,
}: PublicExploreTeasersProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;

  if (!visible) return null;

  const showCommunity = communitySpotCountInScope != null;
  if (!showCommunity) return null;

  const communityLabel = t('publicExplore.teaser.community', { count: communitySpotCountInScope });

  return (
    <View style={styles.stack} accessibilityRole="summary">
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
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { alignSelf: 'stretch', gap: 8 },
  host: { alignSelf: 'stretch' },
  content: { paddingHorizontal: 12, paddingVertical: 10 },
  pressable: { alignSelf: 'stretch' },
});
