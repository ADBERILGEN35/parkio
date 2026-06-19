import { zodResolver } from '@hookform/resolvers/zod';
import type { UserPreference } from '@parkio/types';
import { Button, Icon, Input, LoadingState } from '@parkio/ui';
import {
  PREFERRED_RADIUS_MAX_METERS,
  PREFERRED_RADIUS_MIN_METERS,
  preferencesUpdateSchema,
  type PreferencesUpdateFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { showError, showSuccess } from '@/lib/toast';

export function PreferencesCard() {
  const query = useQuery({ queryKey: ['me', 'preferences'], queryFn: usersApi.getMyPreferences });

  return (
    <SettingsSectionCard
      title="Preferences"
      icon="notifications"
      description="Tune nearby search radius and account notification preferences."
    >
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <PreferencesForm preferences={query.data} />
      )}
    </SettingsSectionCard>
  );
}

function PreferencesForm({ preferences }: { preferences: UserPreference }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyPreferences,
    onSuccess: (preferences) => {
      queryClient.setQueryData(['me', 'preferences'], preferences);
      showSuccess('Preferences saved.');
    },
    onError: () => showError('Could not save preferences.'),
  });

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<PreferencesUpdateFormValues>({
    resolver: zodResolver(preferencesUpdateSchema),
    defaultValues: {
      preferredRadiusMeters: preferences.preferredRadiusMeters,
      notificationsEnabled: preferences.notificationsEnabled,
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  const radius = Number(watch('preferredRadiusMeters'));
  const sliderValue = Number.isFinite(radius)
    ? Math.min(PREFERRED_RADIUS_MAX_METERS, Math.max(PREFERRED_RADIUS_MIN_METERS, radius))
    : PREFERRED_RADIUS_MIN_METERS;
  const radiusLabel = Number.isFinite(radius)
    ? radius >= 1000
      ? `${(radius / 1000).toFixed(radius % 1000 === 0 ? 0 : 1)} km`
      : `${radius} m`
    : '—';

  return (
    <form onSubmit={onSubmit}>
      <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
        <div className="flex flex-col gap-xs">
          <div className="flex items-baseline justify-between gap-sm">
            <span className="text-label-md font-semibold text-on-surface">
              Preferred search radius
            </span>
            <span className="text-label-md font-semibold text-primary">{radiusLabel}</span>
          </div>
          <input
            type="range"
            min={PREFERRED_RADIUS_MIN_METERS}
            max={PREFERRED_RADIUS_MAX_METERS}
            step={100}
            value={sliderValue}
            onChange={(event) =>
              setValue('preferredRadiusMeters', Number(event.target.value), {
                shouldValidate: true,
                shouldDirty: true,
              })
            }
            className="w-full accent-primary"
            aria-label="Preferred search radius slider"
          />
          <Input
            label="Radius in meters"
            type="number"
            inputMode="numeric"
            min={PREFERRED_RADIUS_MIN_METERS}
            max={PREFERRED_RADIUS_MAX_METERS}
            error={errors.preferredRadiusMeters?.message}
            {...register('preferredRadiusMeters')}
          />
          <p className="m-0 text-label-sm text-on-surface-variant">
            How far around you nearby search looks, between {PREFERRED_RADIUS_MIN_METERS} m and{' '}
            {(PREFERRED_RADIUS_MAX_METERS / 1000).toFixed(0)} km.
          </p>
        </div>
        <label className="flex items-center gap-sm text-body-md text-on-surface">
          <input
            type="checkbox"
            className="h-4 w-4 rounded border-outline-variant text-primary focus:ring-primary"
            {...register('notificationsEnabled')}
          />
          Notifications enabled
        </label>
        {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-label-sm text-secondary">
            <Icon name="check_circle" className="text-[14px] leading-none" />
            Saved.
          </p>
        ) : null}
        <Button type="submit" disabled={mutation.isPending} className="self-start">
          {mutation.isPending ? 'Saving…' : 'Save preferences'}
        </Button>
      </fieldset>
    </form>
  );
}
