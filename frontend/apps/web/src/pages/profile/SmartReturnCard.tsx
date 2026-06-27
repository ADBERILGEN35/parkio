import { zodResolver } from '@hookform/resolvers/zod';
import type { SmartReturnSettings } from '@parkio/types';
import { Button, Icon, Input, LoadingState, StatusBadge } from '@parkio/ui';
import {
  SMART_RETURN_LEAD_MINUTES_MAX,
  SMART_RETURN_LEAD_MINUTES_MIN,
  smartReturnSettingsSchema,
  smartReturnTodaySchema,
  type SmartReturnSettingsFormValues,
  type SmartReturnTodayFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { showError, showSuccess } from '@/lib/toast';
import { type GeocodeResult } from '@/lib/geocoding';

export function SmartReturnCard() {
  const query = useQuery({ queryKey: ['me', 'smart-return'], queryFn: usersApi.getSmartReturn });

  return (
    <SettingsSectionCard
      title="Smart Return"
      icon="home_pin"
      description="Opt in to one parking check near your saved home area before you return."
    >
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <SmartReturnPanel settings={query.data} />
      )}
    </SettingsSectionCard>
  );
}

function SmartReturnPanel({ settings }: { settings: SmartReturnSettings }) {
  return (
    <div className="flex flex-col gap-lg">
      <PrivacyNote />
      <SmartReturnSettingsForm settings={settings} />
      <TodayPlan settings={settings} />
    </div>
  );
}

function PrivacyNote() {
  return (
    <div className="rounded-lg border border-outline-variant/40 bg-surface-container-low p-md">
      <p className="m-0 flex items-center gap-xs text-label-md font-semibold text-on-surface">
        <Icon name="shield" className="text-[16px] leading-none text-primary" />
        Private by design
      </p>
      <p className="m-0 mt-xs text-body-sm text-on-surface-variant">
        Parkio stores your saved home area only after you opt in. It is used for Smart Return checks, never shown to
        other users, never sent in analytics events, and V1 does not track your live location.
      </p>
    </div>
  );
}

function SmartReturnSettingsForm({ settings }: { settings: SmartReturnSettings }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateSmartReturnSettings,
    onSuccess: (next) => {
      queryClient.setQueryData(['me', 'smart-return'], next);
      showSuccess('Smart Return settings saved.');
    },
    onError: () => showError('Could not save Smart Return settings.'),
  });

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<SmartReturnSettingsFormValues>({
    resolver: zodResolver(smartReturnSettingsSchema),
    defaultValues: {
      enabled: settings.enabled,
      homeLatitude: settings.homeLatitude,
      homeLongitude: settings.homeLongitude,
      homeLabel: settings.homeLabel ?? '',
      defaultReturnTime: settings.defaultReturnTime ?? '18:30',
      reminderLeadMinutes: settings.reminderLeadMinutes,
    },
  });

  const enabled = watch('enabled');
  const homeLabel = watch('homeLabel');
  const homeLatitude = watch('homeLatitude');
  const homeLongitude = watch('homeLongitude');

  const onSelectPlace = (place: GeocodeResult) => {
    setValue('homeLatitude', place.lat, { shouldDirty: true, shouldValidate: true });
    setValue('homeLongitude', place.lng, { shouldDirty: true, shouldValidate: true });
    setValue('homeLabel', place.secondary || place.primary, { shouldDirty: true, shouldValidate: true });
  };

  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      enabled: values.enabled,
      homeLatitude: values.homeLatitude,
      homeLongitude: values.homeLongitude,
      homeLabel: values.homeLabel || null,
      defaultReturnTime: values.defaultReturnTime,
      reminderLeadMinutes: values.reminderLeadMinutes,
    });
  });

  return (
    <div>
      <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
        <label className="flex min-h-11 items-center justify-between gap-md rounded-lg border border-outline-variant/40 bg-surface p-md text-body-md text-on-surface">
          <span className="flex items-center gap-sm">
            <Icon name="notifications_active" className="text-[18px] leading-none text-primary" />
            Enable Smart Return
          </span>
          <input
            type="checkbox"
            aria-label="Enable Smart Return"
            className="h-5 w-5 rounded border-outline-variant text-primary focus:ring-primary"
            {...register('enabled')}
          />
        </label>

        <PlaceSearch label="Saved home area" placeholder="Search for your street or neighborhood" onSelect={onSelectPlace} />
        <div className="grid grid-cols-1 gap-sm sm:grid-cols-2">
          <Input
            label="Home latitude"
            type="number"
            step="0.000001"
            inputMode="decimal"
            error={errors.homeLatitude?.message}
            {...register('homeLatitude')}
          />
          <Input
            label="Home longitude"
            type="number"
            step="0.000001"
            inputMode="decimal"
            error={errors.homeLongitude?.message}
            {...register('homeLongitude')}
          />
        </div>
        <Input label="Home label" maxLength={160} error={errors.homeLabel?.message} {...register('homeLabel')} />
        {homeLatitude !== null && homeLongitude !== null ? (
          <p className="m-0 text-label-sm text-on-surface-variant">
            Saved area: {homeLabel || 'Unnamed area'}
          </p>
        ) : null}

        <div className="grid grid-cols-1 gap-sm sm:grid-cols-2">
          <Input
            label="Default return time"
            type="time"
            error={errors.defaultReturnTime?.message}
            {...register('defaultReturnTime')}
          />
          <Input
            label="Lead time in minutes"
            type="number"
            min={SMART_RETURN_LEAD_MINUTES_MIN}
            max={SMART_RETURN_LEAD_MINUTES_MAX}
            inputMode="numeric"
            error={errors.reminderLeadMinutes?.message}
            {...register('reminderLeadMinutes')}
          />
        </div>

        {errors.root ? <p className="m-0 text-label-sm text-error">{errors.root.message}</p> : null}
        {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}
        <Button type="button" onClick={onSubmit} disabled={mutation.isPending} className="min-h-11 self-start">
          {mutation.isPending ? 'Saving...' : enabled ? 'Save Smart Return' : 'Save disabled'}
        </Button>
      </fieldset>
    </div>
  );
}

function TodayPlan({ settings }: { settings: SmartReturnSettings }) {
  const queryClient = useQueryClient();
  const todayMutation = useMutation({
    mutationFn: usersApi.smartReturnLeftByCar,
    onSuccess: (next) => {
      queryClient.setQueryData(['me', 'smart-return'], next);
      showSuccess('Return reminder scheduled.');
    },
    onError: () => showError('Could not schedule Smart Return.'),
  });
  const notByCar = useMutation({
    mutationFn: usersApi.smartReturnNotByCar,
    onSuccess: (next) => {
      queryClient.setQueryData(['me', 'smart-return'], next);
      showSuccess('Smart Return skipped for today.');
    },
    onError: () => showError('Could not update today’s plan.'),
  });
  const cancel = useMutation({
    mutationFn: usersApi.cancelSmartReturnToday,
    onSuccess: (next) => {
      queryClient.setQueryData(['me', 'smart-return'], next);
      showSuccess('Today’s reminder cancelled.');
    },
    onError: () => showError('Could not cancel today’s reminder.'),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SmartReturnTodayFormValues>({
    resolver: zodResolver(smartReturnTodaySchema),
    defaultValues: { returnTime: settings.defaultReturnTime ?? '18:30' },
  });

  const onSubmit = handleSubmit((values) => {
    const expectedReturnAt = todayAt(values.returnTime);
    if (!expectedReturnAt || expectedReturnAt.getTime() <= Date.now()) {
      showError('Choose a return time later today.');
      return;
    }
    todayMutation.mutate({ expectedReturnAt: expectedReturnAt.toISOString() });
  });

  const disabled = !settings.enabled || settings.homeLatitude === null || settings.homeLongitude === null;

  return (
    <section className="flex flex-col gap-md rounded-lg border border-outline-variant/40 p-md">
      <div className="flex flex-wrap items-center justify-between gap-sm">
        <div>
          <h3 className="m-0 text-title-md text-on-surface">Today</h3>
          <p className="m-0 mt-xs text-body-sm text-on-surface-variant">Are you driving today?</p>
        </div>
        <StatusBadge status={settings.todayStatus.replaceAll('_', ' ')} />
      </div>

      <form onSubmit={onSubmit} className="flex flex-col gap-sm sm:flex-row sm:items-end">
        <Input
          label="Expected return time"
          type="time"
          disabled={disabled || todayMutation.isPending}
          error={errors.returnTime?.message}
          {...register('returnTime')}
        />
        <Button type="submit" disabled={disabled || todayMutation.isPending} className="min-h-11">
          <Icon name="directions_car" className="text-[16px] leading-none" />
          Driving today
        </Button>
      </form>

      <div className="flex flex-wrap gap-sm">
        <Button variant="secondary" disabled={disabled || notByCar.isPending} onClick={() => notByCar.mutate()}>
          Not by car
        </Button>
        <Button
          variant="outline"
          disabled={settings.todayStatus !== 'LEFT_BY_CAR' || cancel.isPending}
          onClick={() => cancel.mutate()}
        >
          Cancel today
        </Button>
      </div>

      {settings.todayExpectedReturnAt ? (
        <p className="m-0 text-label-sm text-on-surface-variant">
          Current return plan: {new Date(settings.todayExpectedReturnAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
        </p>
      ) : null}
      {disabled ? (
        <p className="m-0 text-label-sm text-on-surface-variant">
          Enable Smart Return and save a home area before answering today’s prompt.
        </p>
      ) : null}
    </section>
  );
}

function todayAt(time: string): Date | null {
  const [hh, mm] = time.split(':').map(Number);
  if (!Number.isInteger(hh) || !Number.isInteger(mm)) return null;
  const date = new Date();
  date.setHours(hh, mm, 0, 0);
  return date;
}
