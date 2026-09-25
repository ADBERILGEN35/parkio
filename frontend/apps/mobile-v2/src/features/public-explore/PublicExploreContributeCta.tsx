import { StyleSheet } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Glass } from '@/components/ui/Glass';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export interface PublicExploreContributeCtaProps {
  visible: boolean;
  onPress: () => void;
}

/**
 * Compact anonymous contribution discoverability — AuthGate only, no writes.
 */
export function PublicExploreContributeCta({ visible, onPress }: PublicExploreContributeCtaProps) {
  const t = useT();
  const theme = useTheme();

  if (!visible) return null;

  const ctaLabel = t('publicExplore.contribute.cta');
  const support = t('publicExplore.contribute.support');

  return (
    <Glass radius={16} style={styles.host} contentStyle={styles.content}>
      <Button
        label={ctaLabel}
        variant="tonal"
        size="sm"
        onPress={onPress}
        accessibilityHint={support}
      />
      <AppText
        variant="labelSm"
        color={theme.colors.onSurfaceVariant}
        accessibilityRole="text"
        importantForAccessibility="no"
      >
        {support}
      </AppText>
    </Glass>
  );
}

const styles = StyleSheet.create({
  host: { alignSelf: 'stretch' },
  content: {
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 6,
    alignItems: 'stretch',
  },
});
