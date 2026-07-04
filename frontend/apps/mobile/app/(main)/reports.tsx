import { zodResolver } from '@hookform/resolvers/zod';
import type { ModerationAppeal, ModerationReport } from '@parkio/types';
import { createAppealSchema, type CreateAppealFormValues } from '@parkio/validation';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Pressable, StyleSheet, View } from 'react-native';
import { Badge, Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useToast } from '@/providers/ToastProvider';
import { moderationApi } from '@/services/api';
import { formatRelativeAgo, humanizeEnum } from '@/utils/format';
import { toUserMessage } from '@/utils/errors';

export default function ReportsScreen() {
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'My reports' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <MyReportsCard />
        <AppealCard />
      </Screen>
    </>
  );
}

function MyReportsCard() {
  const router = useRouter();
  const query = useQuery({ queryKey: ['reports'], queryFn: moderationApi.getMyReports });

  return (
    <Card>
      <AppText variant="heading">Reports you submitted</AppText>
      {query.isPending ? (
        <SkeletonCard />
      ) : query.isError ? (
        <StateView
          icon="alert-circle-outline"
          title="Couldn’t load reports"
          actionLabel="Retry"
          onAction={() => void query.refetch()}
        />
      ) : query.data.length === 0 ? (
        <AppText variant="body" tone="muted">
          You have not reported anything. You can report a spot from its detail page.
        </AppText>
      ) : (
        <View style={styles.list}>
          {query.data.map((report) => (
            <ReportItem
              key={report.id}
              report={report}
              onSpotPress={
                report.targetType === 'PARKING_SPOT'
                  ? () => router.push(`/(main)/spots/${report.targetId}`)
                  : undefined
              }
            />
          ))}
        </View>
      )}
    </Card>
  );
}

function ReportItem({
  report,
  onSpotPress,
}: {
  report: ModerationReport;
  onSpotPress?: () => void;
}) {
  return (
    <View style={styles.report}>
      <View style={styles.meta}>
        <AppText variant="subtitle">{humanizeEnum(report.reason)}</AppText>
        <Badge label={humanizeEnum(report.targetType)} tone="neutral" />
      </View>
      {onSpotPress ? (
        <Pressable onPress={onSpotPress} accessibilityRole="link">
          <AppText variant="caption" tone="primary">
            View spot
          </AppText>
        </Pressable>
      ) : (
        <AppText variant="caption" tone="muted">
          Target: {report.targetId}
        </AppText>
      )}
      {report.description ? <AppText variant="body">{report.description}</AppText> : null}
      <View style={styles.meta}>
        <Badge label={report.caseId ? 'Case opened' : 'Recorded'} tone={report.caseId ? 'primary' : 'neutral'} />
        <AppText variant="caption" tone="muted">
          {formatRelativeAgo(report.createdAt)}
        </AppText>
      </View>
    </View>
  );
}

function AppealCard() {
  const toast = useToast();
  const [createdAppeal, setCreatedAppeal] = useState<ModerationAppeal | null>(null);
  const { control, handleSubmit, reset } = useForm<CreateAppealFormValues>({
    resolver: zodResolver(createAppealSchema),
    defaultValues: { caseId: '', note: '' },
  });

  const appealMutation = useMutation({
    mutationFn: (values: CreateAppealFormValues) =>
      moderationApi.createAppeal({
        caseId: values.caseId,
        note: values.note === '' ? null : values.note,
      }),
    onSuccess: (appeal) => {
      setCreatedAppeal(appeal);
      reset();
      toast.showSuccess('Appeal submitted.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  return (
    <Card>
      <AppText variant="heading">Appeal a moderation decision</AppText>
      <AppText variant="body" tone="muted">
        Appeal a resolved moderation case that targets your own account. Enter the case id from the
        warning you received.
      </AppText>
      <View style={styles.form}>
        <FormTextField control={control} name="caseId" label="Case id" autoCapitalize="none" />
        <FormTextField control={control} name="note" label="Note (optional)" multiline />
        <Button
          label="Submit appeal"
          onPress={handleSubmit((values) => appealMutation.mutate(values))}
          loading={appealMutation.isPending}
        />
        {createdAppeal ? (
          <Badge label={humanizeEnum(createdAppeal.status)} tone="primary" />
        ) : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  list: { gap: 12, marginTop: 8 },
  report: { gap: 6 },
  meta: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 },
  form: { gap: 12, marginTop: 12 },
});