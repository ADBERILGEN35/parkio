import { Stack } from 'expo-router';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import type { LeaderboardEntry, PublicProfile } from '@parkio/types';
import { Badge, Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { gamificationApi, usersApi } from '@/services/api';
import { humanizeEnum } from '@/utils/format';
import { useTheme } from '@/theme';

const LIMIT_STEPS = [10, 20, 50, 100] as const;

export default function LeaderboardScreen() {
  const [limitStep, setLimitStep] = useState(0);
  const limit = LIMIT_STEPS[limitStep]!;
  const canShowMore = limitStep < LIMIT_STEPS.length - 1;

  const query = useQuery({
    queryKey: ['leaderboard', limit],
    queryFn: () => gamificationApi.getLeaderboard(limit),
  });
  const myProgress = useQuery({ queryKey: ['progress'], queryFn: gamificationApi.getMyProgress });
  const myUserId = myProgress.data?.userId ?? null;
  const entries = query.data ?? [];

  const profileQueries = useQueries({
    queries: entries.map((entry) => ({
      queryKey: ['public-profile', entry.userId],
      queryFn: () => usersApi.getPublicProfile(entry.userId),
      staleTime: 5 * 60 * 1000,
      retry: false,
    })),
  });
  const profileByUserId = new Map<string, PublicProfile | null>();
  entries.forEach((entry, index) => {
    profileByUserId.set(entry.userId, profileQueries[index]?.data ?? null);
  });

  const myEntry = myUserId ? entries.find((e) => e.userId === myUserId) ?? null : null;

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Leaderboard' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <AppText variant="body" tone="muted">
          Recognizing the community members who help map the curb.
        </AppText>

        {query.isPending ? (
          <View style={styles.list}>
            <SkeletonCard />
            <SkeletonCard />
          </View>
        ) : query.isError ? (
          <StateView
            icon="alert-circle-outline"
            title="Couldn’t load leaderboard"
            actionLabel="Retry"
            onAction={() => void query.refetch()}
          />
        ) : entries.length === 0 ? (
          <StateView
            icon="trophy-outline"
            title="No ranked contributors yet"
            description="Share and verify spots to earn points and climb the ranks."
          />
        ) : (
          <View style={styles.list}>
            {myEntry ? (
              <Card>
                <AppText variant="label" tone="muted">
                  Your standing
                </AppText>
                <AppText variant="title">#{myEntry.rank}</AppText>
                <AppText variant="body" tone="muted">
                  {myEntry.totalPoints} points · Level {myEntry.currentLevel}
                </AppText>
              </Card>
            ) : null}

            {entries.map((entry) => (
              <LeaderboardRow
                key={entry.userId}
                entry={entry}
                profile={profileByUserId.get(entry.userId) ?? null}
                isMe={entry.userId === myUserId}
              />
            ))}

            {canShowMore ? (
              <Button
                label={query.isFetching ? 'Loading…' : 'Show more'}
                variant="secondary"
                onPress={() => setLimitStep((s) => Math.min(s + 1, LIMIT_STEPS.length - 1))}
                disabled={query.isFetching}
              />
            ) : null}
            <AppText variant="caption" tone="muted">
              Showing top {limit}. Leaderboard is based on lifetime points.
            </AppText>
          </View>
        )}
      </Screen>
    </>
  );
}

function LeaderboardRow({
  entry,
  profile,
  isMe,
}: {
  entry: LeaderboardEntry;
  profile: PublicProfile | null;
  isMe: boolean;
}) {
  const theme = useTheme();
  const name = profile?.displayName?.trim() || `Driver ${entry.userId.slice(0, 8)}`;
  return (
    <Card
      style={
        isMe
          ? { borderWidth: 1, borderColor: theme.colors.primary }
          : undefined
      }
    >
      <View style={styles.row}>
        <AppText variant="title">#{entry.rank}</AppText>
        <View style={styles.rowBody}>
          <AppText variant="subtitle">
            {name}
            {isMe ? ' (you)' : ''}
          </AppText>
          <AppText variant="caption" tone="muted">
            {entry.totalPoints} pts · Level {entry.currentLevel}
          </AppText>
        </View>
        {profile?.trustBand ? <Badge label={humanizeEnum(profile.trustBand)} tone="neutral" /> : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  list: { gap: 12 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  rowBody: { flex: 1, gap: 2 },
});