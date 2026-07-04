import { Stack, useRouter } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { Pressable, StyleSheet, View } from 'react-native';
import type { Spot } from '@parkio/types';
import { Badge, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { parkingApi } from '@/services/api';
import { humanizeEnum } from '@/utils/format';
import { useTheme } from '@/theme';

export default function MySpotsScreen() {
  const router = useRouter();
  const query = useQuery({ queryKey: ['parking', 'my-spots'], queryFn: parkingApi.getMySpots });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'My spots' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        {query.isPending ? (
          <View style={styles.list}>
            <SkeletonCard />
            <SkeletonCard />
          </View>
        ) : query.isError ? (
          <StateView
            icon="alert-circle-outline"
            title="Couldn’t load your spots"
            actionLabel="Retry"
            onAction={() => void query.refetch()}
          />
        ) : query.data.length === 0 ? (
          <StateView
            icon="car-outline"
            title="No spots yet"
            description="Spots you share will appear here, with their live status and verification activity."
            actionLabel="Share your first spot"
            onAction={() => router.push('/(main)/upload')}
          />
        ) : (
          <View style={styles.list}>
            {query.data.map((spot) => (
              <MySpotItem key={spot.id} spot={spot} onPress={() => router.push(`/(main)/spots/${spot.id}`)} />
            ))}
          </View>
        )}
      </Screen>
    </>
  );
}

function MySpotItem({ spot, onPress }: { spot: Spot; onPress: () => void }) {
  const theme = useTheme();
  return (
    <Card padded={false}>
      <Pressable
        accessibilityRole="button"
        onPress={onPress}
        style={({ pressed }) => [
          styles.item,
          { backgroundColor: pressed ? theme.colors.surfaceMuted : 'transparent', borderRadius: theme.radius.xl },
        ]}
      >
        <View style={styles.itemBody}>
          <AppText variant="subtitle">{spot.addressText ?? 'Parking spot'}</AppText>
          <AppText variant="caption" tone="muted">
            {spot.description ?? humanizeEnum(spot.status)}
          </AppText>
          <View style={styles.meta}>
            <Badge label={humanizeEnum(spot.status)} tone="neutral" />
            {spot.verificationCount != null ? (
              <AppText variant="caption" tone="muted">
                {spot.verificationCount} verifications
              </AppText>
            ) : null}
          </View>
        </View>
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  list: { gap: 12 },
  item: { padding: 14 },
  itemBody: { gap: 6 },
  meta: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginTop: 4 },
});