import { createIdempotencyKey } from '@parkio/api-client';
import type { PublicSpot, Spot } from '@parkio/types';
import {
  MODERATION_REASONS,
  VERIFICATION_RESULTS,
} from '@parkio/types';
import {
  reportSpotFormSchema,
  verifySpotSchema,
  type ReportSpotFormValues,
  type VerifySpotFormValues,
} from '@parkio/validation';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Alert, Image, Linking, StyleSheet, View } from 'react-native';
import { Badge, Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormSelect } from '@/components/forms/FormSelect';
import { FormTextField } from '@/components/forms/FormTextField';
import { invalidateGamificationQueries } from '@/lib/gamificationCache';
import { useToast } from '@/providers/ToastProvider';
import { moderationApi, parkingApi } from '@/services/api';
import { humanizeEnum } from '@/utils/format';
import { toUserMessage } from '@/utils/errors';

const TERMINAL_STATUSES = ['FILLED', 'EXPIRED', 'REJECTED'];

export default function SpotDetailScreen() {
  const params = useLocalSearchParams<{ id: string }>();
  const spotId = typeof params.id === 'string' ? params.id : '';

  const spotQuery = useQuery({
    queryKey: ['parking', 'spot', spotId],
    queryFn: () => parkingApi.getSpot(spotId),
    enabled: Boolean(spotId),
  });

  const photoQuery = useQuery({
    queryKey: ['parking', 'spot', spotId, 'media-access-url'],
    queryFn: () => parkingApi.getSpotMediaAccessUrl(spotId),
    enabled: Boolean(spotId),
  });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Spot detail' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        {!spotId ? (
          <StateView icon="alert-circle-outline" title="Missing spot id" />
        ) : spotQuery.isPending ? (
          <SkeletonCard />
        ) : spotQuery.isError ? (
          <StateView
            icon="alert-circle-outline"
            title="Couldn’t load this spot"
            actionLabel="Retry"
            onAction={() => void spotQuery.refetch()}
          />
        ) : (
          <>
            <Card>
              {photoQuery.data?.accessUrl ? (
                <Image
                  source={{ uri: photoQuery.data.accessUrl }}
                  style={styles.photo}
                  accessibilityLabel="Spot photo"
                />
              ) : null}
              <AppText variant="heading">{spotQuery.data.addressText ?? 'Parking spot'}</AppText>
              <View style={styles.meta}>
                <Badge label={humanizeEnum(spotQuery.data.status)} tone="neutral" />
                <Badge label={humanizeEnum(spotQuery.data.legalStatus)} tone="neutral" />
              </View>
              {spotQuery.data.description ? (
                <AppText variant="body" tone="muted">
                  {spotQuery.data.description}
                </AppText>
              ) : null}
              <Button
                label="Open directions"
                variant="secondary"
                onPress={() => {
                  const { latitude, longitude } = spotQuery.data;
                  void Linking.openURL(
                    `https://www.google.com/maps/dir/?api=1&destination=${latitude},${longitude}`,
                  );
                }}
              />
            </Card>
            <SpotActions spot={spotQuery.data} />
          </>
        )}
      </Screen>
    </>
  );
}

function SpotActions({ spot }: { spot: PublicSpot }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [claimed, setClaimed] = useState(false);

  const applySpotUpdate = (updated: PublicSpot) => {
    queryClient.setQueryData(['parking', 'spot', updated.id], updated);
    queryClient.setQueriesData<PublicSpot[]>({ queryKey: ['parking', 'nearby'] }, (current) =>
      current?.map((item) => (item.id === updated.id ? updated : item)),
    );
    queryClient.setQueryData<Spot[]>(['parking', 'my-spots'], (current) =>
      current?.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
    );
  };

  const verifyForm = useForm<VerifySpotFormValues>({
    resolver: zodResolver(verifySpotSchema),
  });

  const verifyMutation = useMutation({
    mutationFn: (values: VerifySpotFormValues) =>
      parkingApi.verifySpot(spot.id, { result: values.result }, createIdempotencyKey()),
    onSuccess: async (updated) => {
      applySpotUpdate(updated);
      verifyForm.reset();
      await queryClient.invalidateQueries({ queryKey: ['parking', 'my-spots'] });
      await invalidateGamificationQueries(queryClient);
      toast.showSuccess('Verification submitted.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const claimMutation = useMutation({
    mutationFn: () => parkingApi.claimSpot(spot.id, createIdempotencyKey()),
    onSuccess: async (updated) => {
      applySpotUpdate(updated);
      setClaimed(true);
      await queryClient.invalidateQueries({ queryKey: ['parking', 'my-spots'] });
      await invalidateGamificationQueries(queryClient);
      toast.showSuccess('Spot claimed as filled.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const reportForm = useForm<ReportSpotFormValues>({
    resolver: zodResolver(reportSpotFormSchema),
    defaultValues: { description: '' },
  });

  const reportMutation = useMutation({
    mutationFn: (values: ReportSpotFormValues) =>
      moderationApi.createReport({
        targetType: 'PARKING_SPOT',
        targetId: spot.id,
        reason: values.reason,
        description: values.description === '' ? null : values.description,
      }),
    onSuccess: async () => {
      reportForm.reset({ description: '' });
      await queryClient.invalidateQueries({ queryKey: ['reports'] });
      toast.showSuccess('Report submitted.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const pending = verifyMutation.isPending || claimMutation.isPending || reportMutation.isPending;
  const terminal = TERMINAL_STATUSES.includes(spot.status) || claimed;
  const disabled = pending || terminal;

  const confirmClaim = () => {
    Alert.alert(
      'Claim this spot as filled?',
      'This marks the spot filled for everyone and cannot be undone.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Claim', style: 'destructive', onPress: () => claimMutation.mutate() },
      ],
    );
  };

  return (
    <Card>
      <AppText variant="heading">Actions</AppText>
      <AppText variant="body" tone="muted">
        Help the community keep this spot accurate.
      </AppText>

      {terminal ? (
        <AppText variant="body" tone="muted">
          This spot is {spot.status.toLowerCase()} — it can no longer be verified or claimed.
        </AppText>
      ) : null}

      <View style={styles.section}>
        <AppText variant="subtitle">Verify availability</AppText>
        <FormSelect
          control={verifyForm.control}
          name="result"
          label="What did you observe?"
          options={VERIFICATION_RESULTS.map((result) => ({
            value: result,
            label: humanizeEnum(result),
          }))}
        />
        <Button
          label="Submit verification"
          onPress={verifyForm.handleSubmit((values) => verifyMutation.mutate(values))}
          loading={verifyMutation.isPending}
          disabled={disabled}
        />
      </View>

      <View style={styles.section}>
        <AppText variant="subtitle">Claim as filled</AppText>
        <Button
          label="I took this spot"
          variant="secondary"
          onPress={confirmClaim}
          loading={claimMutation.isPending}
          disabled={disabled}
        />
      </View>

      <View style={styles.section}>
        <AppText variant="subtitle">Report a problem</AppText>
        <FormSelect
          control={reportForm.control}
          name="reason"
          label="Reason"
          options={MODERATION_REASONS.map((reason) => ({
            value: reason,
            label: humanizeEnum(reason),
          }))}
        />
        <FormTextField
          control={reportForm.control}
          name="description"
          label="Details (optional)"
          multiline
        />
        <Button
          label="Submit report"
          variant="ghost"
          onPress={reportForm.handleSubmit((values) => reportMutation.mutate(values))}
          loading={reportMutation.isPending}
          disabled={pending}
        />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  photo: { width: '100%', height: 200, borderRadius: 12, marginBottom: 12 },
  meta: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 8 },
  section: { gap: 12, marginTop: 16 },
});