import { zodResolver } from '@hookform/resolvers/zod';
import { VEHICLE_TYPES, type VehicleType, type VehicleProfile } from '@parkio/types';
import { Button, Icon, Input, LoadingState, SoftBadge, cn } from '@parkio/ui';
import { vehicleUpsertSchema, type VehicleUpsertFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, type UseFormRegisterReturn } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { enumLabel } from '@/lib/format';
import { showError, showSuccess } from '@/lib/toast';

const VEHICLE_ICONS: Record<VehicleType, string> = {
  MOTORCYCLE: 'two_wheeler',
  SMALL_CAR: 'directions_car',
  SEDAN: 'directions_car',
  SUV: 'directions_car',
  VAN: 'airport_shuttle',
  TRUCK: 'local_shipping',
};

export function VehicleCard() {
  const { t } = useTranslation('settings');
  const query = useQuery({ queryKey: ['me', 'vehicle'], queryFn: usersApi.getMyVehicle });

  return (
    <SettingsSectionCard
      title={t('vehicle.title')}
      icon="directions_car"
      description={t('vehicle.description')}
    >
      {query.isPending ? (
        <LoadingState label={t('common:actions.loading')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <VehicleForm vehicle={query.data} />
      )}
    </SettingsSectionCard>
  );
}

function VehicleForm({ vehicle }: { vehicle: VehicleProfile }) {
  const { t } = useTranslation(['settings', 'common']);
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.upsertMyVehicle,
    onSuccess: (vehicle) => {
      queryClient.setQueryData(['me', 'vehicle'], vehicle);
      showSuccess(t('vehicle.savedToast'));
    },
    onError: () => showError(t('vehicle.saveError')),
  });

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<VehicleUpsertFormValues>({
    resolver: zodResolver(vehicleUpsertSchema),
    defaultValues: {
      vehicleType: vehicle.vehicleType ?? '',
      plate: vehicle.plate ?? '',
    },
  });

  const hasVehicle = vehicle.vehicleType !== null || vehicle.plate !== null;
  const selectedType = watch('vehicleType');

  // PUT replaces the whole vehicle profile; empty fields clear the stored value.
  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      vehicleType: values.vehicleType || null,
      plate: values.plate || null,
    });
  });

  const vehicleTypeLabel = (type: string) => enumLabel(type, t);

  return (
    <form onSubmit={onSubmit}>
      <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
        {!hasVehicle ? (
          <div className="flex items-start gap-sm rounded-xl bg-surface-container-low p-md">
            <Icon name="directions_car" className="text-[20px] leading-none text-primary" />
            <p className="m-0 text-body-md text-on-surface-variant">{t('vehicle.empty')}</p>
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-sm">
            <span className="text-label-sm font-medium text-on-surface-variant">
              {t('vehicle.current')}
            </span>
            <SoftBadge tone="primary" icon="directions_car">
              {vehicle.vehicleType ? vehicleTypeLabel(vehicle.vehicleType) : t('vehicle.noType')}
            </SoftBadge>
            {vehicle.plate ? (
              <span className="rounded bg-surface-container-low px-sm py-xs font-mono text-label-sm text-on-surface">
                {vehicle.plate}
              </span>
            ) : null}
          </div>
        )}

        <div className="flex flex-col gap-xs">
          <span className="text-label-md font-semibold text-on-surface">{t('vehicle.type')}</span>
          <div className="grid grid-cols-2 gap-sm sm:grid-cols-3">
            {VEHICLE_TYPES.map((type) => (
              <VehicleOption
                key={type}
                value={type}
                icon={VEHICLE_ICONS[type]}
                label={vehicleTypeLabel(type)}
                selected={selectedType === type}
                registration={register('vehicleType')}
              />
            ))}
            <VehicleOption
              value=""
              icon="block"
              label={t('vehicle.none')}
              selected={!selectedType}
              registration={register('vehicleType')}
            />
          </div>
          {errors.vehicleType ? (
            <span className="text-label-sm text-error">{errors.vehicleType.message}</span>
          ) : null}
        </div>

        <Input
          label={t('vehicle.platePrivate')}
          error={errors.plate?.message}
          {...register('plate')}
        />

        {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-label-sm text-secondary">
            <Icon name="check_circle" className="text-[14px] leading-none" />
            {t('vehicle.savedInline')}
          </p>
        ) : null}
        <Button type="submit" disabled={mutation.isPending} className="self-start">
          {mutation.isPending ? t('vehicle.saving') : t('vehicle.save')}
        </Button>
      </fieldset>
    </form>
  );
}

function VehicleOption({
  value,
  icon,
  label,
  selected,
  registration,
}: {
  value: string;
  icon: string;
  label: string;
  selected: boolean;
  registration: UseFormRegisterReturn;
}) {
  return (
    <label className="cursor-pointer">
      <input type="radio" value={value} className="peer sr-only" {...registration} />
      <span
        className={cn(
          'flex h-full items-center gap-sm rounded-xl border bg-surface p-md text-body-md font-semibold transition-colors duration-std',
          'hover:border-primary/40 peer-focus-visible:ring-2 peer-focus-visible:ring-primary',
          selected
            ? 'border-primary bg-primary-fixed text-on-primary-fixed'
            : 'border-outline-variant text-on-surface-variant',
        )}
      >
        <Icon name={icon} className="text-[18px] leading-none" />
        {label}
      </span>
    </label>
  );
}
