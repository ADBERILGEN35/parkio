import { ScrollView, StyleSheet, View } from 'react-native';
import { Redirect } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { hasAdminRole } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { useT } from '@/i18n/LocaleProvider';
import { analyticsApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

/** Compact platform summary for admins: KPI tiles + the parking funnel. */
export default function ModerationAnalyticsScreen() {
  const theme = useTheme();
  const t = useT();
  const user = useAuthStore((s) => s.user);
  const insets = useSafeAreaInsets();
  const { colors } = theme;

  const overview = useQuery({
    queryKey: ['analytics-overview'],
    queryFn: () => analyticsApi.getAnalyticsOverview(),
    enabled: hasAdminRole(user?.roles ?? []),
  });

  if (!hasAdminRole(user?.roles ?? [])) {
    return <Redirect href="/(main)/(tabs)/map" />;
  }

  const data = overview.data;
  const funnel = data
    ? [
        { label: t('mod.analytics.created'), value: data.totalParkingCreated },
        { label: t('mod.analytics.verified'), value: data.totalParkingVerified },
        { label: t('mod.analytics.claimed'), value: data.totalParkingClaimed },
        { label: t('mod.analytics.rejected'), value: data.totalParkingRejected },
      ]
    : [];
  const funnelMax = Math.max(1, ...funnel.map((row) => row.value));

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('mod.analytics.title')} />
      {overview.isLoading || !data ? (
        <View style={styles.loading}>
          <Skeleton height={90} radius={16} />
          <Skeleton height={180} radius={16} />
        </View>
      ) : (
        <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 32 }]}>
          <View style={styles.tiles}>
            <Kpi label={t('mod.analytics.created')} value={data.totalParkingCreated} />
            <Kpi label={t('mod.analytics.verified')} value={data.totalParkingVerified} />
            <Kpi label={t('mod.analytics.claimed')} value={data.totalParkingClaimed} />
            <Kpi label={t('mod.analytics.rejected')} value={data.totalParkingRejected} />
          </View>

          <Card style={styles.funnelCard}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('mod.analytics.funnel')}
            </AppText>
            {funnel.map((row) => (
              <View key={row.label} style={styles.funnelRow}>
                <AppText variant="bodySm" style={styles.funnelLabel} numberOfLines={1}>
                  {row.label}
                </AppText>
                <View style={[styles.funnelTrack, { backgroundColor: colors.surfaceContainer2 }]}>
                  <View
                    style={[
                      styles.funnelBar,
                      {
                        backgroundColor: colors.primary,
                        width: `${Math.max(4, (row.value / funnelMax) * 100)}%`,
                      },
                    ]}
                  />
                </View>
                <AppText variant="bodySm" tabular style={styles.funnelValue}>
                  {row.value}
                </AppText>
              </View>
            ))}
          </Card>
        </ScrollView>
      )}
    </SafeAreaView>
  );
}

function Kpi({ label, value }: { label: string; value: number }) {
  const theme = useTheme();
  return (
    <Card tone={1} shadow={false} padding={14} style={styles.kpi}>
      <AppText variant="headlineMd" tabular>
        {value}
      </AppText>
      <AppText variant="labelSm" color={theme.colors.onSurfaceVariant} numberOfLines={1}>
        {label}
      </AppText>
    </Card>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  loading: { padding: 20, gap: 12 },
  scroll: { padding: 20, paddingTop: 4, gap: 12 },
  tiles: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  kpi: { flexBasis: '47%', flexGrow: 1, gap: 2 },
  funnelCard: { gap: 12 },
  funnelRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  funnelLabel: { width: 88 },
  funnelTrack: { flex: 1, height: 14, borderRadius: 7, overflow: 'hidden' },
  funnelBar: { height: 14, borderRadius: 7 },
  funnelValue: { width: 44, textAlign: 'right' },
});
