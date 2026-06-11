import { zodResolver } from '@hookform/resolvers/zod';
import { VEHICLE_TYPES, type VehicleProfile } from '@parkio/types';
import { Button, Card, Input, LoadingState, colors, radius, spacing } from '@parkio/ui';
import { vehicleUpsertSchema, type VehicleUpsertFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';

export function VehicleCard() {
  const query = useQuery({ queryKey: ['me', 'vehicle'], queryFn: usersApi.getMyVehicle });

  return (
    <Card title="Vehicle">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : (
        <VehicleForm vehicle={query.data} />
      )}
    </Card>
  );
}

function VehicleForm({ vehicle }: { vehicle: VehicleProfile }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.upsertMyVehicle,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'vehicle'] }),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<VehicleUpsertFormValues>({
    resolver: zodResolver(vehicleUpsertSchema),
    defaultValues: {
      vehicleType: vehicle.vehicleType ?? '',
      plate: vehicle.plate ?? '',
    },
  });

  const hasVehicle = vehicle.vehicleType !== null || vehicle.plate !== null;

  // PUT replaces the whole vehicle profile; empty fields clear the stored value.
  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      vehicleType: values.vehicleType || null,
      plate: values.plate || null,
    });
  });

  return (
    <form onSubmit={onSubmit}>
      <fieldset
        disabled={mutation.isPending}
        style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}
      >
        {!hasVehicle ? (
          <p style={{ margin: 0, color: colors.textMuted, fontSize: '0.875rem' }}>
            No vehicle configured yet — pick a vehicle type below to add one.
          </p>
        ) : null}
        <label style={{ display: 'flex', flexDirection: 'column', gap: spacing.xs }}>
          <span style={{ fontSize: '0.875rem', fontWeight: 500, color: colors.text }}>Vehicle type</span>
          <select
            style={{
              padding: spacing.sm,
              borderRadius: radius.md,
              border: `1px solid ${errors.vehicleType ? colors.error : colors.border}`,
              fontSize: '1rem',
              backgroundColor: colors.surface,
            }}
            {...register('vehicleType')}
          >
            <option value="">— none —</option>
            {VEHICLE_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
          {errors.vehicleType ? (
            <span style={{ fontSize: '0.75rem', color: colors.error }}>{errors.vehicleType.message}</span>
          ) : null}
        </label>
        <Input label="Plate (private, never shown publicly)" error={errors.plate?.message} {...register('plate')} />
        {mutation.isError ? <ApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? <p style={{ margin: 0, fontSize: '0.875rem' }}>Saved.</p> : null}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Saving…' : 'Save vehicle'}
        </Button>
      </fieldset>
    </form>
  );
}
