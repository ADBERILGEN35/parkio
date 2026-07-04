import { zodResolver } from '@hookform/resolvers/zod';
import { VEHICLE_TYPES, type VehicleProfile } from '@parkio/types';
import { vehicleUpsertSchema, type VehicleUpsertFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { useForm } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import { Badge, Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormSelect } from '@/components/forms/FormSelect';
import { FormTextField } from '@/components/forms/FormTextField';
import { useToast } from '@/providers/ToastProvider';
import { usersApi } from '@/services/api';
import { humanizeEnum } from '@/utils/format';
import { toUserMessage } from '@/utils/errors';

export default function VehicleScreen() {
  const query = useQuery({ queryKey: ['me', 'vehicle'], queryFn: usersApi.getMyVehicle });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Vehicle' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        {query.isPending ? (
          <SkeletonCard />
        ) : query.isError ? (
          <StateView
            icon="alert-circle-outline"
            title="Couldn’t load vehicle"
            actionLabel="Retry"
            onAction={() => void query.refetch()}
          />
        ) : (
          <VehicleForm vehicle={query.data} />
        )}
      </Screen>
    </>
  );
}

function VehicleForm({ vehicle }: { vehicle: VehicleProfile }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.upsertMyVehicle,
    onSuccess: (updated) => {
      queryClient.setQueryData(['me', 'vehicle'], updated);
      toast.showSuccess('Vehicle saved.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const { control, handleSubmit } = useForm<VehicleUpsertFormValues>({
    resolver: zodResolver(vehicleUpsertSchema),
    defaultValues: {
      vehicleType: vehicle.vehicleType ?? '',
      plate: vehicle.plate ?? '',
    },
  });

  const hasVehicle = vehicle.vehicleType !== null || vehicle.plate !== null;

  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      vehicleType: values.vehicleType || null,
      plate: values.plate || null,
    });
  });

  return (
    <Card>
      <AppText variant="body" tone="muted">
        Set the vehicle profile used to judge spot fit. Your plate is private and never shown
        publicly.
      </AppText>
      {hasVehicle ? (
        <View style={styles.current}>
          <AppText variant="caption" tone="muted">
            Current
          </AppText>
          {vehicle.vehicleType ? <Badge label={humanizeEnum(vehicle.vehicleType)} tone="primary" /> : null}
          {vehicle.plate ? <AppText variant="body">{vehicle.plate}</AppText> : null}
        </View>
      ) : (
        <AppText variant="body" tone="muted">
          No vehicle configured yet — pick a type below (and optionally add a plate).
        </AppText>
      )}
      <View style={styles.form}>
        <FormSelect
          control={control}
          name="vehicleType"
          label="Vehicle type"
          options={VEHICLE_TYPES.map((type) => ({ value: type, label: humanizeEnum(type) }))}
        />
        <FormTextField
          control={control}
          name="plate"
          label="Plate (optional)"
          autoCapitalize="characters"
        />
        <Button label="Save vehicle" onPress={onSubmit} loading={mutation.isPending} />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  current: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8, marginTop: 8 },
  form: { gap: 12, marginTop: 12 },
});