import { useMemo, useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import type { ParkingStatus, Spot } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { Skeleton } from '@/components/ui/Skeleton';
import { SpotCard } from '@/components/spots/SpotCard';
import { useShareSessionStore } from '@/features/share/shareSessionStore';
import { useSpotPhoto } from '@/features/spots/useSpotPhoto';
import { useT } from '@/i18n/LocaleProvider';
import { parkingApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';

type Filter = 'all' | 'live' | 'pending' | 'closed';

const FILTER_STATUSES: Record<Exclude<Filter, 'all'>, ParkingStatus[]> = {
  live: ['ACTIVE', 'VERIFIED', 'SUSPICIOUS'],
  pending: ['PENDING_VALIDATION', 'PENDING_REVIEW'],
  closed: ['FILLED', 'EXPIRED', 'REJECTED'],
};

/** "Park yerlerim" — the owner's spots with status filters (brief §12.6). */
export default function MySpotsScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const [filter, setFilter] = useState<Filter>('all');
  const { colors } = theme;

  const mySpots = useQuery({
    queryKey: ['my-spots'],
    queryFn: () => parkingApi.getMySpots(),
    refetchInterval: 30_000,
  });

  const spots = useMemo(() => {
    const all = [...(mySpots.data ?? [])].sort(
      (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt),
    );
    if (filter === 'all') {
      return all;
    }
    return all.filter((spot) => FILTER_STATUSES[filter].includes(spot.status));
  }, [mySpots.data, filter]);

  const filters: { key: Filter; label: string }[] = [
    { key: 'all', label: t('mySpots.filter.all') },
    { key: 'live', label: t('mySpots.filter.live') },
    { key: 'pending', label: t('mySpots.filter.pending') },
    { key: 'closed', label: t('mySpots.filter.closed') },
  ];

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <AppText variant="headlineLg" style={styles.title}>
        {t('mySpots.title')}
      </AppText>
      <View style={styles.filters}>
        {filters.map((item) => (
          <Chip
            key={item.key}
            label={item.label}
            selected={filter === item.key}
            onPress={() => setFilter(item.key)}
          />
        ))}
      </View>

      {mySpots.isLoading ? (
        <View style={styles.loading}>
          <Skeleton height={230} radius={16} />
          <Skeleton height={230} radius={16} />
        </View>
      ) : spots.length === 0 ? (
        <EmptyState
          title={t('mySpots.empty.title')}
          ctaLabel={t('mySpots.empty.cta')}
          onCtaPress={() => {
            useShareSessionStore.getState().begin('my-spots', '/(main)/(tabs)/my-spots');
            router.push({ pathname: '/(main)/share', params: { source: 'camera' } });
          }}
        />
      ) : (
        <FlatList
          data={spots}
          keyExtractor={(spot) => spot.id}
          contentContainerStyle={styles.list}
          refreshing={mySpots.isRefetching}
          onRefresh={() => void mySpots.refetch()}
          renderItem={({ item }) => <MySpotRow spot={item} />}
        />
      )}
    </SafeAreaView>
  );
}

function MySpotRow({ spot }: { spot: Spot }) {
  const t = useT();
  const router = useRouter();
  const photo = useSpotPhoto(spot.id);

  return (
    <SpotCard
      spot={spot}
      photoUri={photo.data ?? null}
      onPress={() => router.push({ pathname: '/(main)/spots/[id]', params: { id: spot.id } })}
      footer={
        <View style={styles.ownerFooter}>
          <Chip
            icon="check-circle-outline"
            label={t('spot.verifications', { count: spot.verificationCount })}
            size="sm"
          />
          <Chip icon="chart-arc" label={t('mySpots.confidence', { score: spot.confidenceScore })} size="sm" />
          {spot.status === 'REJECTED' && (
            <Button
              label={t('spot.appeal')}
              variant="ghost"
              size="sm"
              block={false}
              onPress={() => router.push('/(main)/reports')}
            />
          )}
        </View>
      }
    />
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  title: { paddingHorizontal: 20, paddingTop: 14, paddingBottom: 8 },
  filters: { flexDirection: 'row', gap: 8, paddingHorizontal: 20, paddingBottom: 10 },
  loading: { padding: 20, gap: 14 },
  list: { padding: 20, paddingTop: 6, gap: 14, paddingBottom: 28 },
  ownerFooter: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 6, marginTop: 2 },
});
