import { useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { Redirect, useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  hasAdminRole,
  hasPrivilegedRole,
  type ModerationAppeal,
  type ModerationCase,
  type ModerationSeverity,
  type ModerationStatus,
} from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { useNowTick } from '@/components/spots/FreshnessRing';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { PressableScale } from '@/components/ui/PressableScale';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import { Skeleton } from '@/components/ui/Skeleton';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { formatRelative } from '@/lib/time';
import { moderationApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

const SEVERITY_COLOR: Record<ModerationSeverity, string> = {
  LOW: '#727687',
  MEDIUM: '#A06500',
  HIGH: '#BA1A1A',
  CRITICAL: '#7F1D1D',
};

type Section = 'cases' | 'appeals';

/** Staff moderation queue (brief §12.10) — MODERATOR/ADMIN only. */
export default function ModerationQueueScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const insets = useSafeAreaInsets();
  const [section, setSection] = useState<Section>('cases');
  const [status, setStatus] = useState<ModerationStatus>('OPEN');
  const now = useNowTick(60_000);
  const { colors } = theme;

  const staff = hasPrivilegedRole(user?.roles ?? []);
  const admin = hasAdminRole(user?.roles ?? []);

  const cases = useQuery({
    queryKey: ['mod-cases', status],
    queryFn: () => moderationApi.getModerationCases(status),
    enabled: staff,
    refetchInterval: 60_000,
  });
  const appeals = useQuery({
    queryKey: ['mod-appeals'],
    queryFn: () => moderationApi.getModerationAppeals(),
    enabled: staff && section === 'appeals',
  });

  const resolveAppeal = useMutation({
    mutationFn: (input: { appealId: string; accepted: boolean }) =>
      moderationApi.resolveModerationAppeal(input.appealId, { accepted: input.accepted }),
    onSuccess: () => {
      toast.show(t('mod.appeals.resolved'), 'success');
      void queryClient.invalidateQueries({ queryKey: ['mod-appeals'] });
    },
    onError: (error) => toast.show(describeApiError(error, t).message, 'error'),
  });

  if (!staff) {
    return <Redirect href="/(main)/(tabs)/map" />;
  }

  const statusFilters: ModerationStatus[] = ['OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED'];
  const openAppeals = (appeals.data ?? []).filter((appeal) => appeal.status === 'OPEN');

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('mod.title')} />
      <View style={styles.sectionTabs}>
        <SegmentedControl
          options={[
            { value: 'cases', label: t('mod.case.title') },
            { value: 'appeals', label: t('mod.appeals.title') },
          ]}
          value={section}
          onChange={setSection}
        />
      </View>

      {section === 'cases' ? (
        <>
          <View style={styles.filters}>
            {statusFilters.map((candidate) => (
              <Chip
                key={candidate}
                label={t(`mod.filter.${candidate}`)}
                size="sm"
                selected={status === candidate}
                onPress={() => setStatus(candidate)}
              />
            ))}
          </View>
          {cases.isLoading ? (
            <View style={styles.loading}>
              <Skeleton height={84} radius={16} />
              <Skeleton height={84} radius={16} />
            </View>
          ) : (cases.data ?? []).length === 0 ? (
            <EmptyState title={t('mod.empty')} />
          ) : (
            <FlatList
              data={cases.data}
              keyExtractor={(item) => item.id}
              contentContainerStyle={[styles.list, { paddingBottom: insets.bottom + 28 }]}
              refreshing={cases.isRefetching}
              onRefresh={() => void cases.refetch()}
              renderItem={({ item }) => (
                <CaseRow
                  moderationCase={item}
                  locale={locale}
                  now={now}
                  onPress={() =>
                    router.push({ pathname: '/(main)/moderation/[id]', params: { id: item.id } })
                  }
                />
              )}
            />
          )}
        </>
      ) : appeals.isLoading ? (
        <View style={styles.loading}>
          <Skeleton height={84} radius={16} />
        </View>
      ) : openAppeals.length === 0 ? (
        <EmptyState title={t('mod.appeals.empty')} />
      ) : (
        <FlatList
          data={openAppeals}
          keyExtractor={(item) => item.id}
          contentContainerStyle={[styles.list, { paddingBottom: insets.bottom + 28 }]}
          renderItem={({ item }) => (
            <AppealRow
              appeal={item}
              locale={locale}
              now={now}
              admin={admin}
              busy={resolveAppeal.isPending}
              onResolve={(accepted) => resolveAppeal.mutate({ appealId: item.id, accepted })}
            />
          )}
        />
      )}
    </SafeAreaView>
  );
}

function CaseRow({
  moderationCase,
  locale,
  now,
  onPress,
}: {
  moderationCase: ModerationCase;
  locale: 'tr' | 'en';
  now: number;
  onPress: () => void;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  return (
    <PressableScale scaleTo={0.98} onPress={onPress} accessibilityRole="button" accessibilityLabel={t(`report.${moderationCase.reason}`)}>
      <Card padding={14} style={styles.caseCard}>
        <View style={styles.caseHeader}>
          <View style={[styles.severityDot, { backgroundColor: SEVERITY_COLOR[moderationCase.severity] }]} />
          <AppText variant="bodyMd" style={styles.caseReason} numberOfLines={1}>
            {t(`report.${moderationCase.reason}`)}
          </AppText>
          <AppText variant="labelSm" color={colors.outline}>
            {formatRelative(moderationCase.openedAt, now, locale)}
          </AppText>
        </View>
        <View style={styles.caseMeta}>
          <Chip label={t(`reports.target.${moderationCase.targetType}`)} size="sm" />
          <Chip label={t(`mod.severity.${moderationCase.severity}`)} size="sm" />
          <Chip icon="flag-outline" label={t('mod.case.reports', { count: moderationCase.reportCount })} size="sm" />
          {moderationCase.assignedModeratorId ? (
            <MaterialCommunityIcons name="account-check-outline" size={16} color={colors.secondary} />
          ) : null}
        </View>
      </Card>
    </PressableScale>
  );
}

function AppealRow({
  appeal,
  locale,
  now,
  admin,
  busy,
  onResolve,
}: {
  appeal: ModerationAppeal;
  locale: 'tr' | 'en';
  now: number;
  admin: boolean;
  busy: boolean;
  onResolve: (accepted: boolean) => void;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  return (
    <Card padding={14} style={styles.caseCard}>
      <View style={styles.caseHeader}>
        <MaterialCommunityIcons name="gavel" size={17} color={colors.tertiary} />
        <AppText variant="labelSm" color={colors.outline} style={styles.caseReason} numberOfLines={1}>
          {appeal.caseId}
        </AppText>
        <AppText variant="labelSm" color={colors.outline}>
          {formatRelative(appeal.createdAt, now, locale)}
        </AppText>
      </View>
      {appeal.note ? (
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {appeal.note}
        </AppText>
      ) : null}
      {admin ? (
        <View style={styles.appealActions}>
          <Button
            label={t('mod.appeals.accept')}
            size="sm"
            block={false}
            disabled={busy}
            onPress={() => onResolve(true)}
          />
          <Button
            label={t('mod.appeals.reject')}
            variant="ghost"
            size="sm"
            block={false}
            disabled={busy}
            onPress={() => onResolve(false)}
          />
        </View>
      ) : (
        <AppText variant="labelSm" color={colors.outline}>
          {t('mod.action.adminOnly')}
        </AppText>
      )}
    </Card>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  sectionTabs: { paddingHorizontal: 20, paddingBottom: 8 },
  filters: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, paddingHorizontal: 20, paddingBottom: 8 },
  loading: { padding: 20, gap: 12 },
  list: { padding: 20, paddingTop: 4, gap: 10 },
  caseCard: { gap: 8 },
  caseHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  severityDot: { width: 10, height: 10, borderRadius: 5 },
  caseReason: { flex: 1, fontFamily: 'Inter_600SemiBold' },
  caseMeta: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 6 },
  appealActions: { flexDirection: 'row', gap: 8 },
});
