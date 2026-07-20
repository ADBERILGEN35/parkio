import { Image, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import Constants from 'expo-constants';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { ListRow } from '@/components/ui/ListRow';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { appConfig } from '@/config/env';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export default function AboutScreen() {
  const theme = useTheme();
  const t = useT();
  const insets = useSafeAreaInsets();
  const { colors } = theme;
  const version = Constants.expoConfig?.version ?? '0.1.0';

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('profile.about.title')} />
      <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 24 }]}>
        <View style={styles.hero}>
          <Image source={require('../../../assets/images/icon.png')} style={styles.logo} />
          <AppText variant="headlineMd">Parkio</AppText>
          <Chip label={t('profile.about.stage')} size="sm" />
        </View>

        <Card style={styles.card}>
          <ListRow
            icon="tag-outline"
            label={t('profile.about.version')}
            trailing={
              <AppText variant="bodyMd" tabular color={colors.onSurfaceVariant}>
                {version} · {appConfig.appEnv}
              </AppText>
            }
            showChevron={false}
          />
        </Card>

        <Card tone={1} shadow={false} style={styles.privacyCard}>
          <MaterialCommunityIcons name="shield-check-outline" size={20} color={colors.primary} />
          <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.privacyText}>
            {t('profile.about.privacyNote')}
          </AppText>
        </Card>

        <AppText variant="labelSm" align="center" color={colors.outline}>
          © 2026 Parkio · OpenStreetMap contributors
        </AppText>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, paddingTop: 8, gap: 14 },
  hero: { alignItems: 'center', gap: 8, paddingVertical: 12 },
  logo: { width: 72, height: 72, borderRadius: 18 },
  card: {},
  privacyCard: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  privacyText: { flex: 1 },
});
