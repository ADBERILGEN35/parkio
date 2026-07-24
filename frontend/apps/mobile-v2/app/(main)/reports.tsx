import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { AppNotification } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { useNowTick } from '@/components/spots/FreshnessRing';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import { Sheet } from '@/components/ui/Sheet';
import { Skeleton } from '@/components/ui/Skeleton';
import { TextArea } from '@/components/ui/TextArea';
import { TextField } from '@/components/ui/TextField';
import { reportsKeys } from '@/data/keys';
import { myNotificationsQueryOptions } from '@/data/query-options/notifications';
import { myReportsQueryOptions } from '@/data/query-options/reports';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { formatRelative } from '@/lib/time';
import { moderationApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

type Tab = 'sent' | 'penalties';

/** Case-id hints arrive inside WARNING/SYSTEM notification metadata. */
function caseIdOf(notification: AppNotification): string | null {
  const metadata = notification.metadata ?? {};
  return metadata.caseId ?? metadata.case_id ?? null;
}

/** "Bildirimlerim & itirazlar" (brief §12.7): sent reports + penalties/appeals. */
export default function ReportsScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const toast = useToast();
  const queryClient = useQueryClient();
  const insets = useSafeAreaInsets();
  const [tab, setTab] = useState<Tab>('sent');
  const [appealOpen, setAppealOpen] = useState(false);
  const [appealCaseId, setAppealCaseId] = useState('');
  const [appealNote, setAppealNote] = useState('');
  const now = useNowTick(60_000);
  const { colors } = theme;

  const reports = useQuery(myReportsQueryOptions());
  const notifications = useQuery(myNotificationsQueryOptions());

  const penaltyNotifications = useMemo(
    () =>
      (notifications.data ?? []).filter(
        (item) => item.type === 'WARNING' || item.type === 'SYSTEM',
      ),
    [notifications.data],
  );

  const appealMutation = useMutation({
    mutationFn: () =>
      moderationApi.createAppeal({
        caseId: appealCaseId.trim(),
        ...(appealNote.trim() ? { note: appealNote.trim() } : {}),
      }),
    onSuccess: () => {
      toast.show(t('reports.appeal.submitted'), 'success');
      setAppealOpen(false);
      setAppealCaseId('');
      setAppealNote('');
      void queryClient.invalidateQueries({ queryKey: reportsKeys.all });
    },
    onError: (error) => {
      toast.show(describeApiError(error, t).message, 'error');
    },
  });

  const openAppeal = (caseId: string | null) => {
    setAppealCaseId(caseId ?? '');
    setAppealOpen(true);
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('reports.title')} />
      <View style={styles.tabs}>
        <SegmentedControl
          options={[
            { value: 'sent', label: t('reports.tab.sent') },
            { value: 'penalties', label: t('reports.tab.penalties') },
          ]}
          value={tab}
          onChange={setTab}
        />
      </View>

      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 32 }]}
        showsVerticalScrollIndicator={false}
      >
        {tab === 'sent' ? (
          reports.isLoading ? (
            <View style={styles.loading}>
              <Skeleton height={84} radius={16} />
              <Skeleton height={84} radius={16} />
            </View>
          ) : (reports.data ?? []).length === 0 ? (
            <EmptyState title={t('reports.sent.empty')} />
          ) : (
            (reports.data ?? []).map((report) => (
              <Card key={report.id} padding={14} style={styles.card}>
                <View style={styles.cardHeader}>
                  <Chip
                    icon="flag-outline"
                    label={t(`report.${report.reason}`)}
                    size="sm"
                  />
                  <AppText variant="labelSm" color={colors.outline}>
                    {formatRelative(report.createdAt, now, locale)}
                  </AppText>
                </View>
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {t(`reports.target.${report.targetType}`)}
                  {report.description ? ` · ${report.description}` : ''}
                </AppText>
                <View style={styles.cardFooter}>
                  <Chip
                    icon={report.caseId ? 'briefcase-outline' : 'check'}
                    label={report.caseId ? t('reports.sent.caseOpened') : t('reports.sent.noCase')}
                    size="sm"
                  />
                  {report.caseId ? (
                    <AppText variant="labelSm" color={colors.outline} numberOfLines={1} style={styles.mono}>
                      {report.caseId}
                    </AppText>
                  ) : null}
                </View>
              </Card>
            ))
          )
        ) : notifications.isLoading ? (
          <View style={styles.loading}>
            <Skeleton height={84} radius={16} />
          </View>
        ) : penaltyNotifications.length === 0 ? (
          <EmptyState title={t('reports.penalties.empty')} />
        ) : (
          <>
            {penaltyNotifications.map((item) => {
              const caseId = caseIdOf(item);
              const warning = item.type === 'WARNING';
              return (
                <Card key={item.id} padding={14} style={styles.card}>
                  <View style={styles.cardHeader}>
                    <MaterialCommunityIcons
                      name={warning ? 'alert-outline' : 'information-outline'}
                      size={18}
                      color={warning ? colors.error : colors.primary}
                    />
                    <AppText variant="bodyMd" style={styles.cardTitle} numberOfLines={2}>
                      {item.title}
                    </AppText>
                    <AppText variant="labelSm" color={colors.outline}>
                      {formatRelative(item.createdAt, now, locale)}
                    </AppText>
                  </View>
                  <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                    {item.body}
                  </AppText>
                  {warning && (
                    <Button
                      label={t('reports.appeal.cta')}
                      variant="tonal"
                      size="sm"
                      block={false}
                      onPress={() => openAppeal(caseId)}
                    />
                  )}
                </Card>
              );
            })}
            <Button
              label={t('reports.appeal.title')}
              variant="ghost"
              size="md"
              onPress={() => openAppeal(null)}
            />
          </>
        )}
      </ScrollView>

      {/* Appeal sheet — case id prefilled from the notification when present. */}
      <Sheet visible={appealOpen} onClose={() => setAppealOpen(false)} title={t('reports.appeal.title')}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('reports.appeal.body')}
        </AppText>
        <TextField
          label={t('reports.appeal.caseId')}
          placeholder={t('reports.appeal.caseIdPlaceholder')}
          autoCapitalize="none"
          autoCorrect={false}
          value={appealCaseId}
          onChangeText={setAppealCaseId}
        />
        <TextArea
          label={t('spot.report.descriptionLabel')}
          placeholder={t('reports.appeal.placeholder')}
          value={appealNote}
          onChangeText={setAppealNote}
          maxLength={2000}
          minHeight={88}
        />
        <Button
          label={t('reports.appeal.submit')}
          loading={appealMutation.isPending}
          disabled={appealCaseId.trim().length === 0}
          onPress={() => appealMutation.mutate()}
        />
      </Sheet>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  tabs: { paddingHorizontal: 20, paddingBottom: 10 },
  scroll: { padding: 20, paddingTop: 4, gap: 12 },
  loading: { gap: 12 },
  card: { gap: 8 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  cardTitle: { flex: 1, fontFamily: 'Inter_600SemiBold' },
  cardFooter: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  mono: { flex: 1 },
});
