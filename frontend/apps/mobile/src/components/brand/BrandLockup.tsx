import { Image, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { useLocale } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme';

const MARK = require('../../../assets/images/icon.png');

/**
 * Compact brand lockup for auth/startup surfaces: official mark + wordmark.
 * Mark uses empty alt (decorative); "Parkio" text carries the accessible name.
 */
export function BrandLockup({
  showTagline = false,
}: {
  showTagline?: boolean;
}) {
  const theme = useTheme();
  const { t } = useLocale();

  return (
    <View style={styles.root} accessibilityRole="header">
      <View style={styles.row}>
        <Image
          source={MARK}
          style={styles.mark}
          resizeMode="contain"
          accessibilityIgnoresInvertColors
          accessible={false}
        />
        <AppText variant="display" style={{ color: theme.colors.text }}>
          Parkio
        </AppText>
      </View>
      {showTagline ? (
        <AppText variant="body" tone="muted" style={styles.tagline}>
          {t('Concierge for the curb.')}
        </AppText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: 8 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  mark: { width: 40, height: 40 },
  tagline: { marginTop: 4 },
});