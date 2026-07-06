import { zodResolver } from '@hookform/resolvers/zod';
import {
  MODERATION_ACTIONS,
  MODERATION_STATUSES,
  hasPrivilegedRole,
  type ModerationAppeal,
  type ModerationCase,
  type ModerationStatus,
} from '@parkio/types';
import {
  resolveAppealSchema,
  resolveCaseSchema,
  type ResolveAppealFormValues,
  type ResolveCaseFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Pressable, StyleSheet, View } from 'react-native';
import { Badge, Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormSelect } from '@/components/forms/FormSelect';
import { FormTextField } from '@/components/forms/FormTextField';
import { useToast } from '@/providers/ToastProvider';
import { moderationApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { formatRelativeAgo, humanizeEnum } from '@/utils/format';
import { toUserMessage } from '@/utils/errors';
import { useTheme } from '@/theme';

export default function ModerationScreen() {
  const roles = useAuthStore((s) => s.roles);
  if (!hasPrivilegedRole(roles)) {
    return (
      <>
        <Stack.Screen options={{ headerShown: true, title: 'Access denied' }} />
        <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
          <StateView
            icon="lock-closed-outline"
            title="Access denied"
            description="This area requires a moderator or admin role."
          />
        </Screen>
      </>
    );
  }

  return <ModerationContent />;
}

function ModerationContent() {
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<ModerationStatus | ''>('');

  const casesQuery = useQuery({
    queryKey: ['moderation', 'cases', statusFilter || 'all'],
    queryFn: () => moderationApi.getModerationCases(statusFilter || undefined),
  });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Moderation' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <Card>
          <AppText variant="heading">Cases</AppText>
          <View style={styles.filters}>
            <Pressable onPress={() => setStatusFilter('')} style={styles.filterChip}>
              <Badge label="All" tone={statusFilter === '' ? 'primary' : 'neutral'} />
            </Pressable>
            {MODERATION_STATUSES.map((status) => (
              <Pressable key={status} onPress={() => setStatusFilter(status)} style={styles.filterChip}>
                <Badge
                  label={humanizeEnum(status)}
                  tone={statusFilter === status ? 'primary' : 'neutral'}
                />
              </Pressable>
            ))}
          </View>
          {casesQuery.isPending ? (
            <SkeletonCard />
          ) : casesQuery.isError ? (
            <StateView
              icon="alert-circle-outline"
              title="Couldn’t load cases"
              actionLabel="Retry"
              onAction={() => void casesQuery.refetch()}
            />
          ) : casesQuery.data.length === 0 ? (
            <AppText variant="body" tone="muted">
              The moderation queue is clear.
            </AppText>
          ) : (
            <View style={styles.list}>
              {casesQuery.data.map((item) => (
                <CaseListItem
                  key={item.id}
                  moderationCase={item}
                  selected={item.id === selectedCaseId}
                  onSelect={() => setSelectedCaseId(item.id)}
                />
              ))}
            </View>
          )}
        </Card>

        {selectedCaseId ? <CaseDetail caseId={selectedCaseId} /> : null}
        <AppealsCard />
      </Screen>
    </>
  );
}

function CaseListItem({
  moderationCase,
  selected,
  onSelect,
}: {
  moderationCase: ModerationCase;
  selected: boolean;
  onSelect: () => void;
}) {
  const theme = useTheme();
  return (
    <Pressable
      onPress={onSelect}
      style={[
        styles.caseItem,
        {
          borderColor: selected ? theme.colors.primary : theme.colors.border,
          borderRadius: theme.radius.lg,
        },
      ]}
    >
      <AppText variant="subtitle">{humanizeEnum(moderationCase.reason)}</AppText>
      <View style={styles.meta}>
        <Badge label={humanizeEnum(moderationCase.status)} tone="neutral" />
        <Badge label={humanizeEnum(moderationCase.severity)} tone="warning" />
      </View>
      <AppText variant="caption" tone="muted">
        {humanizeEnum(moderationCase.targetType)} · {moderationCase.reportCount} reports ·{' '}
        {formatRelativeAgo(moderationCase.openedAt)}
      </AppText>
    </Pressable>
  );
}

function CaseDetail({ caseId }: { caseId: string }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ['moderation', 'case', caseId],
    queryFn: () => moderationApi.getModerationCase(caseId),
  });

  const invalidate = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['moderation', 'cases'] }),
      queryClient.invalidateQueries({ queryKey: ['moderation', 'case', caseId] }),
    ]);

  const assignMutation = useMutation({
    mutationFn: () => moderationApi.assignModerationCase(caseId),
    onSuccess: async () => {
      toast.showSuccess('Case assigned to you.');
      await invalidate();
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const resolveForm = useForm<ResolveCaseFormValues>({
    resolver: zodResolver(resolveCaseSchema),
    defaultValues: { note: '' },
  });

  const resolveMutation = useMutation({
    mutationFn: (values: ResolveCaseFormValues) =>
      moderationApi.resolveModerationCase(caseId, {
        action: values.action,
        note: values.note === '' ? null : values.note,
      }),
    onSuccess: async () => {
      resolveForm.reset({ note: '' });
      await invalidate();
      toast.showSuccess('Case resolved.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  if (query.isPending) return <SkeletonCard />;
  if (query.isError || !query.data) {
    return (
      <StateView
        icon="alert-circle-outline"
        title="Couldn’t load case"
        actionLabel="Retry"
        onAction={() => void query.refetch()}
      />
    );
  }

  const moderationCase = query.data;
  const terminal = moderationCase.status === 'RESOLVED' || moderationCase.status === 'REJECTED';

  return (
    <Card>
      <AppText variant="heading">Case detail</AppText>
      <AppText variant="subtitle">{humanizeEnum(moderationCase.reason)}</AppText>
      <AppText variant="body" tone="muted">
        Target {moderationCase.targetId}
      </AppText>
      <View style={styles.meta}>
        <Badge label={humanizeEnum(moderationCase.status)} tone="neutral" />
        <Badge label={humanizeEnum(moderationCase.severity)} tone="warning" />
      </View>

      {!terminal ? (
        <View style={styles.form}>
          <Button
            label="Assign to me"
            variant="secondary"
            onPress={() => assignMutation.mutate()}
            loading={assignMutation.isPending}
          />
          <FormSelect
            control={resolveForm.control}
            name="action"
            label="Resolution action"
            options={MODERATION_ACTIONS.map((action) => ({
              value: action,
              label: humanizeEnum(action),
            }))}
          />
          <FormTextField control={resolveForm.control} name="note" label="Note (optional)" multiline />
          <Button
            label="Resolve case"
            onPress={resolveForm.handleSubmit((values) => resolveMutation.mutate(values))}
            loading={resolveMutation.isPending}
          />
        </View>
      ) : (
        <AppText variant="body" tone="muted">
          This case is already closed.
        </AppText>
      )}
    </Card>
  );
}

function AppealsCard() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ['moderation', 'appeals'], queryFn: moderationApi.getModerationAppeals });
  const [selectedAppealId, setSelectedAppealId] = useState<string | null>(null);

  const resolveForm = useForm<ResolveAppealFormValues>({
    resolver: zodResolver(resolveAppealSchema),
    defaultValues: { note: '' },
  });

  const resolveMutation = useMutation({
    mutationFn: (values: ResolveAppealFormValues) =>
      moderationApi.resolveModerationAppeal(selectedAppealId!, {
        accepted: values.accepted,
        note: values.note === '' ? null : values.note,
      }),
    onSuccess: async () => {
      resolveForm.reset({ note: '' });
      setSelectedAppealId(null);
      await queryClient.invalidateQueries({ queryKey: ['moderation', 'appeals'] });
      toast.showSuccess('Appeal resolved.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  return (
    <Card>
      <AppText variant="heading">Appeals</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <AppText variant="body" tone="danger">
          Couldn’t load appeals.
        </AppText>
      ) : query.data.length === 0 ? (
        <AppText variant="body" tone="muted">
          No appeals.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.map((appeal: ModerationAppeal) => (
            <Pressable key={appeal.id} onPress={() => setSelectedAppealId(appeal.id)} style={styles.caseItem}>
              <AppText variant="subtitle">Case {appeal.caseId.slice(0, 8)}…</AppText>
              <Badge label={humanizeEnum(appeal.status)} tone="neutral" />
              <AppText variant="caption" tone="muted">
                {formatRelativeAgo(appeal.createdAt)}
              </AppText>
            </Pressable>
          ))}
        </View>
      )}

      {selectedAppealId ? (
        <View style={styles.form}>
          <AppText variant="subtitle">Resolve appeal</AppText>
          <FormSelect
            control={resolveForm.control}
            name="accepted"
            label="Decision"
            options={[
              { value: 'true', label: 'Accept' },
              { value: 'false', label: 'Reject' },
            ]}
          />
          <FormTextField control={resolveForm.control} name="note" label="Note (optional)" multiline />
          <Button
            label="Submit decision"
            onPress={resolveForm.handleSubmit((values) => resolveMutation.mutate(values))}
            loading={resolveMutation.isPending}
          />
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  filters: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 8 },
  filterChip: { minHeight: 44, justifyContent: 'center' },
  list: { gap: 10, marginTop: 8 },
  caseItem: { borderWidth: 1, padding: 12, gap: 6 },
  meta: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  form: { gap: 12, marginTop: 12 },
});
