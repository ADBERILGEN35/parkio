import { useQuery } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { analyticsApi } from '@/services/api';
import { humanizeEnum } from '@/utils/format';

export default function AnalyticsScreen() {
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Analytics' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <OverviewCard />
        <ParkingCard />
        <MetricsCard />
        <DailyCard />
      </Screen>
    </>
  );
}

function OverviewCard() {
  const query = useQuery({
    queryKey: ['analytics', 'overview'],
    queryFn: analyticsApi.getAnalyticsOverview,
  });

  return (
    <Card>
      <AppText variant="heading">Overview — lifetime totals</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <StateView
          icon="alert-circle-outline"
          title="Couldn’t load overview"
          actionLabel="Retry"
          onAction={() => void query.refetch()}
        />
      ) : (
        <View style={styles.grid}>
          <Stat label="Spots created" value={query.data.totalParkingCreated} />
          <Stat label="Verifications" value={query.data.totalParkingVerified} />
          <Stat label="Claims" value={query.data.totalParkingClaimed} />
          <Stat label="Rejections" value={query.data.totalParkingRejected} />
          <Stat label="Points earned" value={query.data.totalPointsEarned} />
          <Stat label="Level-ups" value={query.data.totalLevelUps} />
          <Stat label="Notifications" value={query.data.totalNotificationsCreated} />
        </View>
      )}
    </Card>
  );
}

function ParkingCard() {
  const query = useQuery({
    queryKey: ['analytics', 'parking'],
    queryFn: analyticsApi.getParkingAnalytics,
  });

  return (
    <Card>
      <AppText variant="heading">Parking funnel</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load parking analytics.
        </AppText>
      ) : query.data.length === 0 ? (
        <AppText variant="body" tone="muted">
          No parking analytics yet.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.map((row) => (
            <AppText key={row.metricType} variant="body" tone="muted">
              {humanizeEnum(row.metricType)}: {row.eventCount} events · sum {row.sumValue}
            </AppText>
          ))}
        </View>
      )}
    </Card>
  );
}

function MetricsCard() {
  const query = useQuery({
    queryKey: ['analytics', 'metrics'],
    queryFn: analyticsApi.getAnalyticsMetrics,
  });

  return (
    <Card>
      <AppText variant="heading">All metrics</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load metrics.
        </AppText>
      ) : query.data.length === 0 ? (
        <AppText variant="body" tone="muted">
          No metrics yet.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.map((row) => (
            <AppText key={row.metricType} variant="body" tone="muted">
              {humanizeEnum(row.metricType)}: count {row.totalCount} · value {row.totalValue}
            </AppText>
          ))}
        </View>
      )}
    </Card>
  );
}

function DailyCard() {
  const query = useQuery({
    queryKey: ['analytics', 'daily'],
    queryFn: analyticsApi.getDailyAnalytics,
  });

  return (
    <Card>
      <AppText variant="heading">Daily snapshots</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load daily snapshots.
        </AppText>
      ) : query.data.length === 0 ? (
        <AppText variant="body" tone="muted">
          No daily snapshots yet.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.map((row) => (
            <AppText key={`${row.date}-${row.metricType}`} variant="body" tone="muted">
              {row.date} · {humanizeEnum(row.metricType)}: {row.eventCount} events · sum {row.sumValue}
            </AppText>
          ))}
        </View>
      )}
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <View style={styles.stat}>
      <AppText variant="title">{value}</AppText>
      <AppText variant="caption" tone="muted">
        {label}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12, marginTop: 12 },
  stat: { width: '45%', gap: 2 },
  list: { gap: 8, marginTop: 8 },
});