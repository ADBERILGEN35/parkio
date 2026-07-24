import { useState } from 'react';
import { Image, ScrollView, StyleSheet, View } from 'react-native';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  MODERATION_ACTIONS,
  hasAdminRole,
  hasPrivilegedRole,
  type ModerationAction,
} from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { OptionSheet } from '@/components/ui/OptionSheet';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { TextArea } from '@/components/ui/TextArea';
import { spotTitle } from '@/components/spots/spotChips';
import { useNowTick } from '@/components/spots/FreshnessRing';
import { moderationKeys } from '@/data/keys';
import { spotDetailQueryOptions } from '@/data/query-options/parking';
import { useSpotPhoto } from '@/features/spots/useSpotPhoto';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { formatRelative } from '@/lib/time';
import { moderationApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

const ADMIN_ACTIONS: ModerationAction[] = [
  'REDUCE_TRUST',
  'DEDUCT_POINTS',
  'SUSPEND_USER',
  'RESTORE_USER',
];

const ACTION_ICONS: Record<ModerationAction, React.ComponentProps<typeof MaterialCommunityIcons>['name']> = {
  APPROVE: 'check-circle-outline',
  REJECT: 'close-circle-outline',
  MARK_FILLED: 'car-off',
  MARK_RISKY: 'alert-octagon-outline',
  REDUCE_TRUST: 'shield-off-outline',
  DEDUCT_POINTS: 'minus-circle-outline',
  SUSPEND_USER: 'account-lock-outline',
  RESTORE_USER: 'account-check-outline',
};

/** Case detail: evidence, snapshot, role-gated resolution (brief §12.10). */
export default function ModerationCaseScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const toast = useToast();
  const queryClient = useQueryClient();
  const params = useLocalSearchParams<{ id: string }>();
  const caseId = typeof params.id === 'string' ? params.id : '';
  const user = useAuthStore((s) => s.user);
  const insets = useSafeAreaInsets();
  const [actionSheet, setActionSheet] = useState(false);
  const [pendingAction, setPendingAction] = useState<ModerationAction | null>(null);
  const [note, setNote] = useState('');
  const now = useNowTick(60_000);
  const { colors } = theme;

  const staff = hasPrivilegedRole(user?.roles ?? []);
  const admin = hasAdminRole(user?.roles ?? []);

  const caseQuery = useQuery({
    queryKey: moderationKeys.caseDetail(caseId),
    queryFn: () => moderationApi.getModerationCase(caseId),
    enabled: staff && caseId.length > 0,
  });
  const moderationCase = caseQuery.data ?? null;

  const isSpotTarget = moderationCase?.targetType === 'PARKING_SPOT';
  const spotQuery = useQuery({
    ...spotDetailQueryOptions(moderationCase?.targetId ?? ''),
    enabled: Boolean(isSpotTarget && moderationCase?.targetId),
    retry: false,
  });
  const photo = useSpotPhoto(isSpotTarget && spotQuery.data ? moderationCase!.targetId : null);

  const assign = useMutation({
    mutationFn: () => moderationApi.assignModerationCase(caseId),
    onSuccess: (updated) => {
      queryClient.setQueryData(moderationKeys.caseDetail(caseId), updated);
      void queryClient.invalidateQueries({ queryKey: [...moderationKeys.all, 'cases'] });
    },
    onError: (error) => toast.show(describeApiError(error, t).message, 'error'),
  });

  const resolve = useMutation({
    mutationFn: (action: ModerationAction) =>
      moderationApi.resolveModerationCase(caseId, {
        action,
        ...(note.trim() ? { note: note.trim() } : {}),
      }),
    onSuccess: (updated) => {
      toast.show(t('mod.case.resolved'), 'success');
      queryClient.setQueryData(moderationKeys.caseDetail(caseId), updated);
      void queryClient.invalidateQueries({ queryKey: [...moderationKeys.all, 'cases'] });
      setPendingAction(null);
    },
    onError: (error) => {
      setPendingAction(null);
      toast.show(describeApiError(error, t).message, 'error');
    },
  });

  if (!staff) {
    return <Redirect href="/(main)/(tabs)/map" />;
  }

  const terminal =
    moderationCase?.status === 'RESOLVED' || moderationCase?.status === 'REJECTED';
  const availableActions = MODERATION_ACTIONS.filter((action) =>
    admin ? true : !ADMIN_ACTIONS.includes(action),
  );

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('mod.case.title')} />
      {caseQuery.isLoading || !moderationCase ? (
        <View style={styles.loading}>
          <Skeleton height={100} radius={16} />
          <Skeleton height={180} radius={16} />
        </View>
      ) : (
        <ScrollView
          contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 32 }]}
          showsVerticalScrollIndicator={false}
        >
          {/* Case facts */}
          <Card style={styles.card}>
            <View style={styles.headerRow}>
              <AppText variant="titleMd" style={styles.reason}>
                {t(`report.${moderationCase.reason}`)}
              </AppText>
              <Chip label={t(`mod.filter.${moderationCase.status}`)} size="sm" />
            </View>
            <View style={styles.metaRow}>
              <Chip label={t(`reports.target.${moderationCase.targetType}`)} size="sm" />
              <Chip label={t(`mod.severity.${moderationCase.severity}`)} size="sm" />
              <Chip icon="flag-outline" label={t('mod.case.reports', { count: moderationCase.reportCount })} size="sm" />
            </View>
            <AppText variant="labelSm" color={colors.outline}>
              {formatRelative(moderationCase.openedAt, now, locale)} ·{' '}
              {moderationCase.assignedModeratorId ? t('mod.case.assigned') : t('mod.case.unassigned')}
            </AppText>
            {!terminal && !moderationCase.assignedModeratorId && (
              <Button
                label={t('mod.case.assign')}
                variant="tonal"
                size="md"
                onPress={() => assign.mutate()}
                loading={assign.isPending}
              />
            )}
          </Card>

          {/* Evidence */}
          {isSpotTarget && (
            <Card style={styles.card}>
              <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
                {t('mod.case.evidence')}
              </AppText>
              {spotQuery.data ? (
                <>
                  <View style={[styles.photoWrap, { backgroundColor: colors.surfaceContainer2 }]}>
                    {photo.data ? (
                      <Image source={{ uri: photo.data }} style={styles.photo} resizeMode="cover" />
                    ) : (
                      <MaterialCommunityIcons name="image-outline" size={28} color={colors.outline} />
                    )}
                  </View>
                  <AppText variant="bodyMd">{spotTitle(spotQuery.data, t)}</AppText>
                  <View style={styles.metaRow}>
                    <Chip label={t(`status.${spotQuery.data.status}`)} size="sm" />
                    <Chip label={t(`legal.${spotQuery.data.legalStatus}`)} size="sm" />
                  </View>
                  {spotQuery.data.description ? (
                    <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                      {spotQuery.data.description}
                    </AppText>
                  ) : null}
                </>
              ) : (
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {t('spot.notFound')}
                </AppText>
              )}
              <AppText variant="labelSm" color={colors.outline} numberOfLines={1}>
                {moderationCase.targetId}
              </AppText>
            </Card>
          )}

          {/* Resolution */}
          <Card style={styles.card}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('mod.case.resolution')}
            </AppText>
            {terminal ? (
              <>
                <Chip
                  icon={moderationCase.resolutionAction ? ACTION_ICONS[moderationCase.resolutionAction] : 'check'}
                  label={
                    moderationCase.resolutionAction
                      ? t(`mod.action.${moderationCase.resolutionAction}`)
                      : t(`mod.filter.${moderationCase.status}`)
                  }
                />
                {moderationCase.resolutionNote ? (
                  <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                    {moderationCase.resolutionNote}
                  </AppText>
                ) : null}
              </>
            ) : (
              <>
                <TextArea
                  label={t('mod.case.noteLabel')}
                  placeholder={t('mod.case.notePlaceholder')}
                  value={note}
                  onChangeText={setNote}
                  maxLength={2000}
                  minHeight={72}
                />
                <Button label={t('mod.case.resolution')} onPress={() => setActionSheet(true)} />
              </>
            )}
          </Card>
        </ScrollView>
      )}

      <OptionSheet
        visible={actionSheet}
        onClose={() => setActionSheet(false)}
        title={t('mod.case.resolution')}
        options={availableActions.map((action) => ({
          value: action,
          label: t(`mod.action.${action}`),
          icon: ACTION_ICONS[action],
          tone:
            action === 'SUSPEND_USER' || action === 'REJECT' || action === 'MARK_RISKY'
              ? ('danger' as const)
              : ('default' as const),
        }))}
        onSelect={(action) => {
          setActionSheet(false);
          setPendingAction(action);
        }}
      />
      <ConfirmModal
        visible={pendingAction !== null}
        title={pendingAction ? t(`mod.action.${pendingAction}`) : ''}
        body={t('mod.case.notePlaceholder')}
        confirmLabel={t('common.ok')}
        cancelLabel={t('common.cancel')}
        confirmVariant={pendingAction === 'SUSPEND_USER' ? 'destructive' : 'primary'}
        loading={resolve.isPending}
        onConfirm={() => pendingAction && resolve.mutate(pendingAction)}
        onCancel={() => setPendingAction(null)}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  loading: { padding: 20, gap: 12 },
  scroll: { padding: 20, paddingTop: 4, gap: 12 },
  card: { gap: 10 },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  reason: { flex: 1 },
  metaRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  photoWrap: {
    height: 160,
    borderRadius: 14,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  photo: { width: '100%', height: '100%' },
});
