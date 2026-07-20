import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { PointTransactionEntry } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { LevelProgressBar } from '@/components/ui/LevelProgressBar';
import { RadiusDiagram } from '@/components/ui/RadiusDiagram';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { useNowTick } from '@/components/spots/FreshnessRing';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { formatRelative } from '@/lib/time';
import { gamificationApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';

function formatRadius(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(1).replace(/\.0$/, '')} km` : `${meters} m`;
}

/** "Katkıların" (pen `tJsqx`): level hero, radius diagram, benefits, ledger, roadmap. */
export default function ImpactScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const insets = useSafeAreaInsets();
  const now = useNowTick(60_000);
  const { colors } = theme;

  const level = useQuery({ queryKey: ['my-level'], queryFn: () => gamificationApi.getMyLevel() });
  const points = useQuery({ queryKey: ['my-points'], queryFn: () => gamificationApi.getMyPoints() });
  const policy = useQuery({ queryKey: ['access-policy'], queryFn: () => gamificationApi.getMyAccessPolicy() });
  const levels = useQuery({ queryKey: ['levels'], queryFn: () => gamificationApi.getLevels(), staleTime: 10 * 60_000 });

  const currentLevel = level.data?.currentLevel ?? policy.data?.currentLevel ?? 1;
  const nextRule = levels.data?.find((rule) => rule.level === currentLevel + 1) ?? null;
  const progressFraction = (() => {
    const standing = level.data;
    if (!standing || standing.nextLevelMinPoints === null) {
      return 1;
    }
    const span = standing.nextLevelMinPoints - standing.currentLevelMinPoints;
    return span > 0 ? (standing.totalPoints - standing.currentLevelMinPoints) / span : 1;
  })();

  const loading = level.isLoading || policy.isLoading;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('impact.title')} />
      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 32 }]}
        showsVerticalScrollIndicator={false}
      >
        {loading ? (
          <View style={styles.loading}>
            <Skeleton height={64} />
            <Skeleton height={200} radius={16} />
            <Skeleton height={90} radius={16} />
          </View>
        ) : (
          <>
            {/* Level hero */}
            <View style={styles.hero}>
              <View style={styles.heroRow}>
                <AppText variant="headlineMd">
                  {t('common.level')} {currentLevel}
                </AppText>
                <AppText variant="bodySm" tabular color={colors.onSurfaceVariant}>
                  {level.data?.nextLevelMinPoints !== null && level.data
                    ? t('impact.progressLabel', {
                        points: level.data.totalPoints,
                        nextMin: level.data.nextLevelMinPoints,
                      })
                    : t('impact.maxLevel')}
                </AppText>
              </View>
              <LevelProgressBar fraction={progressFraction} />
            </View>

            {/* Level = sight (brief §5.7) */}
            {policy.data && (
              <View style={styles.section}>
                <RadiusDiagram
                  currentLabel={formatRadius(policy.data.searchRadiusMeters)}
                  nextLabel={
                    nextRule
                      ? `${t('common.level')} ${nextRule.level} · ${formatRadius(nextRule.searchRadiusMeters)}`
                      : null
                  }
                />
                <AppText variant="bodySm" color={colors.onSurfaceVariant} align="center">
                  {nextRule
                    ? t('impact.radiusCaption', {
                        current: formatRadius(policy.data.searchRadiusMeters),
                        next: nextRule.level,
                        nextRadius: formatRadius(nextRule.searchRadiusMeters),
                      })
                    : t('impact.radiusCaptionMax', {
                        current: formatRadius(policy.data.searchRadiusMeters),
                      })}
                </AppText>
              </View>
            )}

            {/* Current benefits */}
            {policy.data && (
              <View style={styles.statsRow}>
                <StatTile
                  icon="radar"
                  value={formatRadius(policy.data.searchRadiusMeters)}
                  label={t('impact.stat.radius')}
                />
                <StatTile
                  icon="format-list-numbered"
                  value={String(policy.data.resultLimit)}
                  label={t('impact.stat.results')}
                />
                <StatTile
                  icon="eye-outline"
                  value={String(policy.data.dailyViewLimit)}
                  label={t('impact.stat.dailyViews')}
                />
              </View>
            )}
            {(policy.data?.verifiedSpotPriority || policy.data?.notificationPriority) && (
              <View style={styles.perks}>
                {policy.data.verifiedSpotPriority && (
                  <Chip icon="check-decagram-outline" label={t('impact.perk.verifiedPriority')} size="sm" />
                )}
                {policy.data.notificationPriority && (
                  <Chip icon="bell-ring-outline" label={t('impact.perk.notificationPriority')} size="sm" />
                )}
              </View>
            )}

            {/* Recent point ledger */}
            <Card style={styles.ledger}>
              <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
                {t('impact.recent.title')}
              </AppText>
              {points.data && points.data.recentTransactions.length > 0 ? (
                points.data.recentTransactions.slice(0, 12).map((entry, index) => (
                  <LedgerRow key={`${entry.createdAt}-${index}`} entry={entry} locale={locale} now={now} />
                ))
              ) : (
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {t('impact.recent.empty')}
                </AppText>
              )}
            </Card>

            {/* Level roadmap */}
            {levels.data && (
              <View style={styles.roadmap}>
                <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
                  {t('impact.roadmap.title')}
                </AppText>
                {levels.data
                  .slice()
                  .sort((a, b) => a.level - b.level)
                  .map((rule) => {
                    const isCurrent = rule.level === currentLevel;
                    return (
                      <Card
                        key={rule.level}
                        tone={isCurrent ? 3 : 1}
                        padding={14}
                        shadow={false}
                        style={styles.roadmapCard}
                      >
                        <View
                          style={[
                            styles.levelBubble,
                            {
                              backgroundColor: isCurrent ? colors.primary : colors.surface,
                            },
                          ]}
                        >
                          <AppText
                            variant="titleMd"
                            tabular
                            color={isCurrent ? colors.onPrimary : colors.onSurfaceVariant}
                          >
                            {rule.level}
                          </AppText>
                        </View>
                        <View style={styles.roadmapLabels}>
                          <AppText variant="bodyMd">
                            {t('impact.roadmap.perLevel', {
                              radius: formatRadius(rule.searchRadiusMeters),
                              results: rule.resultLimit,
                              views: rule.dailyViewLimit,
                            })}
                          </AppText>
                          <AppText variant="labelSm" tabular color={colors.onSurfaceVariant}>
                            ≥ {rule.minPoints} {t('common.points')}
                          </AppText>
                        </View>
                        {(rule.verifiedSpotPriority || rule.notificationPriority) && (
                          <MaterialCommunityIcons name="star-outline" size={16} color={colors.tertiary} />
                        )}
                      </Card>
                    );
                  })}
              </View>
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function StatTile({
  icon,
  value,
  label,
}: {
  icon: React.ComponentProps<typeof MaterialCommunityIcons>['name'];
  value: string;
  label: string;
}) {
  const theme = useTheme();
  return (
    <Card tone={1} padding={12} shadow={false} style={styles.statTile}>
      <MaterialCommunityIcons name={icon} size={17} color={theme.colors.primary} />
      <AppText variant="titleMd" tabular>
        {value}
      </AppText>
      <AppText variant="labelSm" color={theme.colors.onSurfaceVariant}>
        {label}
      </AppText>
    </Card>
  );
}

function LedgerRow({
  entry,
  locale,
  now,
}: {
  entry: PointTransactionEntry;
  locale: 'tr' | 'en';
  now: number;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const earned = entry.direction === 'EARNED';
  return (
    <View style={styles.ledgerRow}>
      <View style={styles.ledgerLabels}>
        <AppText variant="bodyMd" numberOfLines={1}>
          {t(`impact.source.${entry.sourceType}`)}
        </AppText>
        <AppText variant="labelSm" color={colors.onSurfaceVariant}>
          {formatRelative(entry.createdAt, now, locale)}
        </AppText>
      </View>
      <AppText variant="titleMd" tabular color={earned ? colors.secondary : colors.error}>
        {earned ? '+' : '−'}
        {Math.abs(entry.points)}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, paddingTop: 6, gap: 16 },
  loading: { gap: 14 },
  hero: { gap: 8 },
  heroRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between' },
  section: { gap: 8 },
  statsRow: { flexDirection: 'row', gap: 8 },
  statTile: { flex: 1, alignItems: 'center', gap: 3 },
  perks: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  ledger: { gap: 10 },
  ledgerRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  ledgerLabels: { flex: 1, gap: 1 },
  roadmap: { gap: 8 },
  roadmapCard: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  levelBubble: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  roadmapLabels: { flex: 1, gap: 1 },
});
