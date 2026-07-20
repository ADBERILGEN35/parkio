import { useMemo } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { LeaderboardEntry } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Avatar } from '@/components/ui/Avatar';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useT } from '@/i18n/LocaleProvider';
import { gamificationApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

const MEDAL_COLORS = ['#E7B00A', '#9AA3B2', '#B9722D'];

/** Privacy-safe display handle — the API exposes only platform user ids. */
function anonymousName(userId: string, template: string): string {
  const shortId = userId.replace(/-/g, '').slice(0, 4).toUpperCase();
  return template.replace('{id}', shortId);
}

/** "En çok katkı verenler" — podium top-3 + rows + pinned self (brief §12.6). */
export default function LeaderboardScreen() {
  const theme = useTheme();
  const t = useT();
  const user = useAuthStore((s) => s.user);
  const { colors } = theme;

  const leaderboard = useQuery({
    queryKey: ['leaderboard'],
    queryFn: () => gamificationApi.getLeaderboard(50),
    staleTime: 60_000,
  });
  const progress = useQuery({
    queryKey: ['my-progress'],
    queryFn: () => gamificationApi.getMyProgress(),
    staleTime: 60_000,
  });

  const entries = useMemo(() => leaderboard.data ?? [], [leaderboard.data]);
  const podium = entries.slice(0, 3);
  const rest = entries.slice(3);
  const selfEntry = useMemo(
    () => entries.find((entry) => entry.userId === user?.id) ?? null,
    [entries, user],
  );

  const isSelf = (entry: LeaderboardEntry) => entry.userId === user?.id;
  const nameOf = (entry: LeaderboardEntry) =>
    isSelf(entry) ? t('leaderboard.you') : anonymousName(entry.userId, t('leaderboard.anonymous', { id: '{id}' }));

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <View style={styles.header}>
        <AppText variant="headlineLg">{t('leaderboard.title')}</AppText>
        <Chip label={t('leaderboard.period')} size="sm" />
      </View>

      {leaderboard.isLoading ? (
        <View style={styles.loading}>
          <Skeleton height={140} radius={20} />
          <Skeleton height={56} radius={14} />
          <Skeleton height={56} radius={14} />
        </View>
      ) : entries.length === 0 ? (
        <EmptyState title={t('leaderboard.empty')} />
      ) : (
        <FlatList
          data={rest}
          keyExtractor={(entry) => entry.userId}
          contentContainerStyle={styles.list}
          ListHeaderComponent={
            <View style={styles.podium}>
              {[1, 0, 2].map((podiumIndex) => {
                const entry = podium[podiumIndex];
                if (!entry) {
                  return <View key={podiumIndex} style={styles.podiumSlot} />;
                }
                const isFirst = podiumIndex === 0;
                return (
                  <Card
                    key={entry.userId}
                    tone={isFirst ? 3 : 2}
                    padding={12}
                    shadow={false}
                    style={[styles.podiumSlot, isFirst && styles.podiumFirst]}
                  >
                    <MaterialCommunityIcons name="medal" size={20} color={MEDAL_COLORS[podiumIndex]} />
                    <Avatar name={nameOf(entry)} size={isFirst ? 48 : 40} />
                    <AppText variant="bodySm" numberOfLines={1} style={styles.podiumName}>
                      {nameOf(entry)}
                    </AppText>
                    <AppText variant="titleMd" tabular>
                      {entry.totalPoints}
                    </AppText>
                    <AppText variant="labelSm" color={colors.onSurfaceVariant}>
                      {t('common.levelShort')} {entry.currentLevel}
                    </AppText>
                  </Card>
                );
              })}
            </View>
          }
          renderItem={({ item }) => <LeaderboardRow entry={item} name={nameOf(item)} self={isSelf(item)} />}
          ListFooterComponent={
            !selfEntry && progress.data ? (
              <View style={styles.selfFooter}>
                <LeaderboardRow
                  entry={{
                    rank: 0,
                    userId: progress.data.userId,
                    totalPoints: progress.data.totalPoints,
                    currentLevel: progress.data.currentLevel,
                  }}
                  name={t('leaderboard.you')}
                  self
                  unranked
                />
              </View>
            ) : null
          }
        />
      )}
    </SafeAreaView>
  );
}

function LeaderboardRow({
  entry,
  name,
  self,
  unranked,
}: {
  entry: LeaderboardEntry;
  name: string;
  self?: boolean;
  unranked?: boolean;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  return (
    <View
      style={[
        styles.row,
        { backgroundColor: self ? colors.primaryFixed : colors.surface },
        theme.mode === 'light' && !self ? styles.rowShadow : null,
      ]}
    >
      <AppText variant="titleMd" tabular color={colors.onSurfaceVariant} style={styles.rank}>
        {unranked ? '—' : entry.rank}
      </AppText>
      <Avatar name={name} size={34} />
      <AppText variant="bodyLg" numberOfLines={1} style={styles.name}>
        {name}
      </AppText>
      <Chip label={`${t('common.levelShort')} ${entry.currentLevel}`} size="sm" />
      <AppText variant="titleMd" tabular>
        {entry.totalPoints}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingTop: 14,
    paddingBottom: 10,
  },
  loading: { padding: 20, gap: 12 },
  list: { paddingHorizontal: 20, paddingBottom: 28, gap: 8 },
  podium: { flexDirection: 'row', alignItems: 'flex-end', gap: 8, paddingBottom: 12 },
  podiumSlot: { flex: 1, alignItems: 'center', gap: 4 },
  podiumFirst: { paddingVertical: 20 },
  podiumName: { maxWidth: '100%' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 14,
  },
  rowShadow: {
    shadowColor: '#000000',
    shadowOpacity: 0.04,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 3 },
    elevation: 1,
  },
  rank: { width: 28, textAlign: 'center' },
  name: { flex: 1 },
  selfFooter: { paddingTop: 8 },
});
