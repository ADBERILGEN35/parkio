import { zodResolver } from '@hookform/resolvers/zod';
import type { ParkioLocale, UserPreference } from '@parkio/types';
import { Button, Icon, Input, LoadingState } from '@parkio/ui';
import {
  PREFERRED_RADIUS_MAX_METERS,
  PREFERRED_RADIUS_MIN_METERS,
  preferencesUpdateSchema,
  type PreferencesUpdateFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { usersApi } from '@/api';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { useLocaleStore } from '@/i18n/localeStore';
import { showError, showSuccess } from '@/lib/toast';

export function PreferencesCard() {
  const { t } = useTranslation('settings');
  const query = useQuery({ queryKey: ['me', 'preferences'], queryFn: usersApi.getMyPreferences });

  return (
    <SettingsSectionCard
      title={t('preferences.title')}
      icon="notifications"
      description={t('preferences.description')}
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
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const syncFromServer = useLocaleStore((s) => s.syncFromServer);
  const locale = useLocaleStore((s) => s.locale);

  useEffect(() => {
    if (preferences.preferredLocale) {
      syncFromServer(preferences.preferredLocale);
    }
  }, [preferences.preferredLocale, syncFromServer]);

  const mutation = useMutation({
    mutationFn: usersApi.updateMyPreferences,
    onSuccess: (next) => {
      queryClient.setQueryData(['me', 'preferences'], next);
      showSuccess(t('preferences.savedToast'));
    },
    onError: () => showError(t('preferences.saveError')),
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
      preferredLocale: preferences.preferredLocale ?? locale,
      preferredRadiusMeters: preferences.preferredRadiusMeters,
      notificationsEnabled: preferences.notificationsEnabled,
    },
  });

  const preferredLocale = watch('preferredLocale') ?? locale;

  const onLocaleChange = (next: ParkioLocale) => {
    setValue('preferredLocale', next, { shouldDirty: true, shouldValidate: true });
    setLocale(next);
    if (isAuthenticated) {
      mutation.mutate({ preferredLocale: next });
    }
  };

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
          <span className="text-label-md font-semibold text-on-surface">{t('language.label')}</span>
          <p className="m-0 text-label-sm text-on-surface-variant">{t('language.description')}</p>
          <div
            role="radiogroup"
            aria-label={t('language.aria')}
            className="mt-xs flex flex-wrap gap-sm"
          >
            {(
              [
                { value: 'tr' as const, label: t('language.turkish') },
                { value: 'en' as const, label: t('language.english') },
              ] as const
            ).map((option) => {
              const selected = preferredLocale === option.value;
              return (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  onClick={() => onLocaleChange(option.value)}
                  className={
                    selected
                      ? 'rounded-full bg-primary-container px-md py-sm text-label-md font-semibold text-on-primary-container'
                      : 'rounded-full bg-surface-container px-md py-sm text-label-md font-medium text-on-surface hover:bg-surface-container-high'
                  }
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="flex flex-col gap-xs">
          <div className="flex items-baseline justify-between gap-sm">
            <span className="text-label-md font-semibold text-on-surface">
              {t('preferences.radiusLabel')}
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
            aria-label={t('preferences.radiusSliderAria')}
          />
          <Input
            label={t('preferences.radiusMeters')}
            type="number"
            inputMode="numeric"
            min={PREFERRED_RADIUS_MIN_METERS}
            max={PREFERRED_RADIUS_MAX_METERS}
            error={errors.preferredRadiusMeters?.message}
            {...register('preferredRadiusMeters')}
          />
          <p className="m-0 text-label-sm text-on-surface-variant">
            {t('preferences.radiusHelp', {
              min: PREFERRED_RADIUS_MIN_METERS,
              maxKm: (PREFERRED_RADIUS_MAX_METERS / 1000).toFixed(0),
            })}
          </p>
        </div>
        <label className="flex items-center gap-sm text-body-md text-on-surface">
          <input
            type="checkbox"
            className="h-4 w-4 rounded border-outline-variant text-primary focus:ring-primary"
            {...register('notificationsEnabled')}
          />
          {t('preferences.notificationsEnabled')}
        </label>
        {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-label-sm text-secondary">
            <Icon name="check_circle" className="text-[14px] leading-none" />
            {t('actions.saved', { ns: 'common' })}
          </p>
        ) : null}
        <Button type="submit" disabled={mutation.isPending} className="self-start">
          {mutation.isPending ? t('preferences.saving') : t('preferences.save')}
        </Button>
      </fieldset>
    </form>
  );
}
