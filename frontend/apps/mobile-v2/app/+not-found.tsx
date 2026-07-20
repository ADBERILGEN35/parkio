import { Link } from 'expo-router';
import { StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export default function NotFound() {
  const theme = useTheme();
  const t = useT();
  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.colors.background }]}>
      <AppText variant="headlineMd">404</AppText>
      <AppText variant="bodyMd" color={theme.colors.onSurfaceVariant}>
        {t('spot.notFound')}
      </AppText>
      <Link href="/" style={styles.link}>
        <AppText variant="titleMd" color={theme.colors.primary}>
          {t('tabs.map')}
        </AppText>
      </Link>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 8 },
  link: { marginTop: 16, padding: 8 },
});
