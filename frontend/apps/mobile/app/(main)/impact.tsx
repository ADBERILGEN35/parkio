import { Stack, useRouter } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { Pressable, StyleSheet, View } from 'react-native';
import type { LevelStanding, PointTransactionEntry } from '@parkio/types';
import { Badge, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { gamificationApi } from '@/services/api';
import { formatRelativeAgo, humanizeEnum } from '@/utils/format';
import { useTheme } from '@/theme';

export default function ImpactScreen() {
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Your Impact' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <AppText variant="body" tone="muted">
          Track your contributions and unlock more ways to help the community.
        </AppText>
        <LevelHero />
        <RecentActivity />
        <Benefits />
        <LevelsRoadmap />
      </Screen>
    </>
  );
}

function LevelHero() {
  const query = useQuery({ queryKey: ['level'], queryFn: gamificationApi.getMyLevel });
  if (query.isPending) return <SkeletonCard />;
  if (query.isError) {
    return (
      <StateView
        icon="alert-circle-outline"
        title="Couldn’t load progress"
        actionLabel="Retry"
        onAction={() => void query.refetch()}
      />
    );
  }
  return <LevelHeroContent level={query.data} />;
}

function LevelHeroContent({ level }: { level: LevelStanding }) {
  const theme = useTheme();
  const atMax = level.nextLevelMinPoints === null || level.pointsToNextLevel === null;
  const span = atMax ? 0 : (level.nextLevelMinPoints as number) - level.currentLevelMinPoints;
  const earnedInBand = level.totalPoints - level.currentLevelMinPoints;
  const pct = atMax || span <= 0 ? 100 : Math.min(100, Math.max(0, (earnedInBand / span) * 100));

  return (
    <Card>
      <View style={styles.heroRow}>
        <View style={[styles.levelDisc, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.full }]}>
          <AppText variant="title" tone="primary">
            {level.currentLevel}
          </AppText>
        </View>
        <View style={styles.heroText}>
          <AppText variant="label" tone="muted">
            Current level
          </AppText>
          <AppText variant="heading">Level {level.currentLevel}</AppText>
          <AppText variant="body" tone="muted">
            {level.totalPoints} total points
          </AppText>
        </View>
      </View>
      <View style={[styles.track, { backgroundColor: theme.colors.surfaceMuted, borderRadius: theme.radius.full }]}>
        <View
          style={[
            styles.fill,
            { width: `${pct}%`, backgroundColor: theme.colors.primary, borderRadius: theme.radius.full },
          ]}
        />
      </View>
      <AppText variant="caption" tone="muted">
        {atMax
          ? 'You are at the highest level.'
          : `${level.totalPoints} / ${level.nextLevelMinPoints} points toward the next level.`}
      </AppText>
    </Card>
  );
}

function RecentActivity() {
  const router = useRouter();
  const query = useQuery({ queryKey: ['points'], queryFn: gamificationApi.getMyPoints });

  return (
    <Card>
      <AppText variant="heading">Recent activity</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load activity.
        </AppText>
      ) : query.data.recentTransactions.length === 0 ? (
        <AppText variant="body" tone="muted">
          Share or verify spots to start earning points.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.recentTransactions.map((entry, index) => (
            <TransactionItem
              key={`${entry.createdAt}-${index}`}
              entry={entry}
              onSpotPress={
                entry.relatedSpotId
                  ? () => router.push(`/(main)/spots/${entry.relatedSpotId}`)
                  : undefined
              }
            />
          ))}
        </View>
      )}
    </Card>
  );
}

function TransactionItem({
  entry,
  onSpotPress,
}: {
  entry: PointTransactionEntry;
  onSpotPress?: () => void;
}) {
  const earned = entry.direction === 'EARNED';
  return (
    <View style={styles.txRow}>
      <View style={styles.txBody}>
        <AppText variant="subtitle">{humanizeEnum(entry.sourceType)}</AppText>
        <AppText variant="caption" tone="muted">
          {formatRelativeAgo(entry.createdAt)}
        </AppText>
        {onSpotPress ? (
          <Pressable onPress={onSpotPress} accessibilityRole="link">
            <AppText variant="caption" tone="primary">
              View spot
            </AppText>
          </Pressable>
        ) : null}
      </View>
      <Badge label={`${earned ? '+' : '−'}${entry.points}`} tone={earned ? 'success' : 'danger'} />
    </View>
  );
}

function Benefits() {
  const query = useQuery({ queryKey: ['access-policy'], queryFn: gamificationApi.getMyAccessPolicy });
  return (
    <Card>
      <AppText variant="heading">Your current benefits</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load benefits.
        </AppText>
      ) : (
        <View style={styles.list}>
          <AppText variant="body">Search radius: {query.data.searchRadiusMeters} m</AppText>
          <AppText variant="body">Results per search: {query.data.resultLimit}</AppText>
          <AppText variant="body">Daily views: {query.data.dailyViewLimit}</AppText>
          <AppText variant="body">
            Verified-spot priority: {query.data.verifiedSpotPriority ? 'Yes' : 'No'}
          </AppText>
          <AppText variant="body">
            Notification priority: {query.data.notificationPriority ? 'Yes' : 'No'}
          </AppText>
        </View>
      )}
    </Card>
  );
}

function LevelsRoadmap() {
  const query = useQuery({ queryKey: ['levels'], queryFn: gamificationApi.getLevels });
  return (
    <Card>
      <AppText variant="heading">Level roadmap</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load levels.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.map((rule) => (
            <AppText key={rule.level} variant="body" tone="muted">
              Level {rule.level}: {rule.minPoints} pts
            </AppText>
          ))}
        </View>
      )}
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  heroRow: { flexDirection: 'row', alignItems: 'center', gap: 16 },
  levelDisc: { width: 56, height: 56, alignItems: 'center', justifyContent: 'center' },
  heroText: { flex: 1, gap: 2 },
  track: { height: 8, marginTop: 12, overflow: 'hidden' },
  fill: { height: '100%' },
  list: { gap: 10, marginTop: 8 },
  txRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  txBody: { flex: 1, gap: 2 },
});