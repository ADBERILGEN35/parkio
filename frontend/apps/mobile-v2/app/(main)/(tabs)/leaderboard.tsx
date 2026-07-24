import { useMemo } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { LeaderboardEntry } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Avatar } from '@/components/ui/Avatar';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { LevelProgressBar } from '@/components/ui/LevelProgressBar';
import { Skeleton } from '@/components/ui/Skeleton';
import {
  leaderboardQueryOptions,
  myLevelQueryOptions,
  myProgressQueryOptions,
} from '@/data/query-options/gamification';
import {
  REWARD_HINTS,
  anonymousDriverLabel,
  partitionLeaderboard,
  standingProgress,
  truncateDisplayName,
} from '@/features/leaderboard/leaderboardModel';
import { useShareSheetStore } from '@/features/share/shareSheetStore';
import { useT } from '@/i18n/LocaleProvider';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

const MEDAL_COLORS = ['#E7B00A', '#9AA3B2', '#B9722D'];

/** "Liderlik" — standing, dynamic top contributors, list, how-to-earn, share CTA. */
export default function LeaderboardScreen() {
  const theme = useTheme();
  const t = useT();
  const user = useAuthStore((s) => s.user);
  const openShare = useShareSheetStore((s) => s.open);
  const { colors } = theme;

  const leaderboard = useQuery({
    ...leaderboardQueryOptions(50),
    staleTime: 60_000,
  });
  const progress = useQuery({
    ...myProgressQueryOptions(),
    staleTime: 60_000,
  });
  const myLevel = useQuery({
    ...myLevelQueryOptions(),
    staleTime: 60_000,
  });

  const entries = useMemo(() => leaderboard.data ?? [], [leaderboard.data]);
  const { podium, rest, selfEntry } = useMemo(
    () => partitionLeaderboard(entries, user?.id),
    [entries, user?.id],
  );

  const standingPoints =
    selfEntry?.totalPoints ?? myLevel.data?.totalPoints ?? progress.data?.totalPoints ?? null;
  const standingLevel =
    selfEntry?.currentLevel ?? myLevel.data?.currentLevel ?? progress.data?.currentLevel ?? null;
  const standingRank = selfEntry?.rank ?? null;
  const progressInfo = standingProgress(myLevel.data);

  const isSelf = (entry: LeaderboardEntry) => entry.userId === user?.id;
  const nameOf = (entry: LeaderboardEntry) =>
    isSelf(entry)
      ? t('leaderboard.you')
      : anonymousDriverLabel(entry.userId, (id) => t('leaderboard.anonymous', { id }));

  const listHeader = (
    <View style={styles.headerBlock}>
      <StandingCard
        rank={standingRank}
        points={standingPoints}
        level={standingLevel}
        progressFraction={progressInfo.fraction}
        pointsToNext={progressInfo.pointsToNext}
        nextMin={progressInfo.nextMin}
        currentPoints={standingPoints}
      />
      <TopContributors
        entries={podium}
        selfId={user?.id}
        nameOf={nameOf}
      />
    </View>
  );

  const listFooter = (
    <View style={styles.footerBlock}>
      {!selfEntry && progress.data ? (
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
      ) : null}
      <HowToEarnCard />
      <ContributeCard onShare={() => openShare('leaderboard-cta')} />
    </View>
  );

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <View style={styles.header}>
        <AppText variant="headlineLg">{t('leaderboard.title')}</AppText>
        <Chip label={t('leaderboard.period')} size="sm" />
      </View>

      {leaderboard.isLoading ? (
        <View style={styles.loading}>
          <Skeleton height={120} radius={20} />
          <Skeleton height={140} radius={20} />
          <Skeleton height={56} radius={14} />
        </View>
      ) : leaderboard.isError ? (
        <View style={styles.stateWrap}>
          <EmptyState title={t('leaderboard.error')} />
          <Button label={t('leaderboard.retry')} variant="tonal" onPress={() => void leaderboard.refetch()} />
        </View>
      ) : entries.length === 0 ? (
        <View style={styles.stateWrap}>
          <EmptyState title={t('leaderboard.empty')} />
          <HowToEarnCard />
          <ContributeCard onShare={() => openShare('leaderboard-cta')} />
        </View>
      ) : (
        <FlatList
          data={rest}
          keyExtractor={(entry) => entry.userId}
          contentContainerStyle={styles.list}
          ListHeaderComponent={listHeader}
          renderItem={({ item }) => (
            <LeaderboardRow entry={item} name={nameOf(item)} self={isSelf(item)} />
          )}
          ListFooterComponent={listFooter}
        />
      )}
    </SafeAreaView>
  );
}

function StandingCard({
  rank,
  points,
  level,
  progressFraction,
  pointsToNext,
  nextMin,
  currentPoints,
}: {
  rank: number | null;
  points: number | null;
  level: number | null;
  progressFraction: number | null;
  pointsToNext: number | null;
  nextMin: number | null;
  currentPoints: number | null;
}) {
  const theme = useTheme();
  const t = useT();
  if (points == null && level == null && rank == null) {
    return null;
  }
  return (
    <Card tone={3} padding={16} shadow={false} style={styles.standing}>
      <AppText variant="labelSm" color={theme.colors.onSurfaceVariant}>
        {t('leaderboard.yourStanding')}
      </AppText>
      <View style={styles.standingMetrics}>
        <Metric
          label={t('leaderboard.rankLabel')}
          value={rank != null && rank > 0 ? `#${rank}` : '—'}
        />
        <Metric
          label={t('leaderboard.pointsLabel')}
          value={points != null ? `${points} ${t('leaderboard.pointsSuffix')}` : '—'}
        />
        <Metric
          label={t('leaderboard.levelLabel')}
          value={level != null ? String(level) : '—'}
        />
      </View>
      {progressFraction != null && nextMin != null && currentPoints != null ? (
        <View style={styles.progressBlock}>
          <AppText variant="labelSm" color={theme.colors.onSurfaceVariant}>
            {t('leaderboard.progressLabel', { current: currentPoints, next: nextMin })}
          </AppText>
          <LevelProgressBar fraction={progressFraction} />
          {pointsToNext != null ? (
            <AppText variant="bodySm" color={theme.colors.onSurfaceVariant}>
              {t('leaderboard.nextLevel', { points: pointsToNext })}
            </AppText>
          ) : null}
        </View>
      ) : null}
    </Card>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.metric}>
      <AppText variant="titleMd" tabular numberOfLines={1}>
        {value}
      </AppText>
      <AppText variant="labelSm" color="#8A93A6" numberOfLines={1}>
        {label}
      </AppText>
    </View>
  );
}

function TopContributors({
  entries,
  selfId,
  nameOf,
}: {
  entries: LeaderboardEntry[];
  selfId?: string;
  nameOf: (entry: LeaderboardEntry) => string;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;

  if (entries.length === 0) {
    return null;
  }

  if (entries.length === 1) {
    const only = entries[0];
    const isSelf = only.userId === selfId;
    return (
      <Card tone={2} padding={16} shadow={false} style={styles.solo}>
        <MaterialCommunityIcons name="medal" size={28} color={MEDAL_COLORS[0]} />
        <AppText variant="titleMd">
          {isSelf ? t('leaderboard.solo.selfTitle') : t('leaderboard.solo.otherTitle')}
        </AppText>
        <AppText variant="bodySm" color={colors.onSurfaceVariant} align="center">
          {isSelf ? t('leaderboard.solo.selfBody') : t('leaderboard.solo.otherBody')}
        </AppText>
        <Avatar name={nameOf(only)} size={48} />
        <AppText variant="bodyLg" numberOfLines={1}>
          {truncateDisplayName(nameOf(only))}
        </AppText>
        <AppText variant="titleMd" tabular>
          {only.totalPoints} {t('leaderboard.pointsSuffix')}
        </AppText>
        {isSelf ? (
          <AppText variant="bodySm" color={colors.onSurfaceVariant} align="center">
            {t('leaderboard.solo.selfCta')}
          </AppText>
        ) : null}
      </Card>
    );
  }

  if (entries.length === 2) {
    return (
      <View style={styles.twoGrid}>
        {entries.map((entry, index) => (
          <Card key={entry.userId} tone={index === 0 ? 3 : 2} padding={12} shadow={false} style={styles.twoCard}>
            <MaterialCommunityIcons name="medal" size={18} color={MEDAL_COLORS[index]} />
            <Avatar name={nameOf(entry)} size={40} />
            <AppText variant="bodySm" numberOfLines={1}>
              {truncateDisplayName(nameOf(entry))}
            </AppText>
            <AppText variant="titleMd" tabular>
              {entry.totalPoints}
            </AppText>
            <AppText variant="labelSm" color={colors.onSurfaceVariant}>
              {t('common.levelShort')} {entry.currentLevel}
            </AppText>
          </Card>
        ))}
      </View>
    );
  }

  return (
    <View style={styles.podium}>
      {[1, 0, 2].map((podiumIndex) => {
        const entry = entries[podiumIndex];
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
            <Avatar name={nameOf(entry)} size={isFirst ? 52 : 40} />
            <AppText variant="bodySm" numberOfLines={1} style={styles.podiumName}>
              {truncateDisplayName(nameOf(entry))}
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
  );
}

function HowToEarnCard() {
  const theme = useTheme();
  const t = useT();
  return (
    <Card tone={2} padding={16} shadow={false} style={styles.howCard}>
      <AppText variant="titleMd">{t('leaderboard.howTitle')}</AppText>
      {REWARD_HINTS.map((hint) => (
        <View key={hint.key} style={styles.howRow}>
          <AppText variant="titleMd" tabular color={theme.colors.primary} style={styles.howPoints}>
            {`+${hint.points}`}
          </AppText>
          <AppText variant="bodySm" style={styles.howLabel}>
            {t(hint.key)}
          </AppText>
        </View>
      ))}
    </Card>
  );
}

function ContributeCard({ onShare }: { onShare: () => void }) {
  const theme = useTheme();
  const t = useT();
  return (
    <Card tone={3} padding={16} shadow={false} style={styles.contribute}>
      <AppText variant="titleMd">{t('leaderboard.contributeTitle')}</AppText>
      <AppText variant="bodySm" color={theme.colors.onSurfaceVariant}>
        {t('leaderboard.contributeBody')}
      </AppText>
      <Button label={t('leaderboard.contributeCta')} onPress={onShare} />
    </Card>
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
        {
          backgroundColor: self ? colors.primaryFixed : colors.surface,
          borderColor: self ? colors.primary : 'transparent',
          borderWidth: self ? 1 : 0,
        },
        theme.mode === 'light' && !self ? styles.rowShadow : null,
      ]}
    >
      <AppText variant="titleMd" tabular color={colors.onSurfaceVariant} style={styles.rank}>
        {unranked ? '—' : entry.rank}
      </AppText>
      <Avatar name={name} size={34} />
      <AppText variant="bodyLg" numberOfLines={1} style={styles.name}>
        {truncateDisplayName(name)}
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
  stateWrap: { padding: 20, gap: 16, flex: 1 },
  list: { paddingHorizontal: 20, paddingBottom: 28, gap: 8 },
  headerBlock: { gap: 12, paddingBottom: 8 },
  footerBlock: { gap: 12, paddingTop: 12 },
  standing: { gap: 12 },
  standingMetrics: { flexDirection: 'row', gap: 8 },
  metric: { flex: 1, gap: 2 },
  progressBlock: { gap: 6 },
  solo: { alignItems: 'center', gap: 8 },
  twoGrid: { flexDirection: 'row', gap: 8 },
  twoCard: { flex: 1, alignItems: 'center', gap: 4 },
  podium: { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  podiumSlot: { flex: 1, alignItems: 'center', gap: 4 },
  podiumFirst: { paddingVertical: 18 },
  podiumName: { maxWidth: '100%' },
  howCard: { gap: 10 },
  howRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  howPoints: { width: 40 },
  howLabel: { flex: 1 },
  contribute: { gap: 10 },
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
});
