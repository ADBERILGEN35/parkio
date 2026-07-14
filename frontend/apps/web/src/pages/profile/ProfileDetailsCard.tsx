import { zodResolver } from '@hookform/resolvers/zod';
import type { Profile } from '@parkio/types';
import { Button, Icon, Input, LoadingState } from '@parkio/ui';
import { profileUpdateSchema, type ProfileUpdateFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { showError, showSuccess } from '@/lib/toast';

export function ProfileDetailsCard() {
  const { t } = useTranslation('settings');
  const query = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  return (
    <SettingsSectionCard
      title={t('profile.title')}
      icon="person"
      description={t('profile.description')}
    >
      {query.isPending ? (
        <LoadingState label={t('common:actions.loading')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <ProfileForm profile={query.data} />
      )}
    </SettingsSectionCard>
  );
}

function ProfileForm({ profile }: { profile: Profile }) {
  const { t } = useTranslation(['settings', 'common']);
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyProfile,
    onSuccess: (profile) => {
      queryClient.setQueryData(['me', 'profile'], profile);
      showSuccess(t('profile.savedToast'));
    },
    onError: () => showError(t('profile.saveError')),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ProfileUpdateFormValues>({
    resolver: zodResolver(profileUpdateSchema),
    defaultValues: {
      displayName: profile.displayName ?? '',
      phoneNumber: profile.phoneNumber ?? '',
      city: profile.city ?? '',
    },
  });

  // Empty fields are omitted from the PATCH body (backend leaves them unchanged).
  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      displayName: values.displayName || undefined,
      phoneNumber: values.phoneNumber || undefined,
      city: values.city || undefined,
    });
  });

  const profileBasics = [
    { label: t('profile.displayName'), complete: Boolean(profile.displayName?.trim()) },
    { label: t('profile.phoneNumber'), complete: Boolean(profile.phoneNumber?.trim()) },
    { label: t('profile.city'), complete: Boolean(profile.city?.trim()) },
  ];
  const completedBasics = profileBasics.filter((item) => item.complete).length;
  const missingBasics = profileBasics.filter((item) => !item.complete).map((item) => item.label);

  return (
    <form onSubmit={onSubmit}>
      <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
        <div className="rounded-2xl bg-surface-container p-md">
          <p className="m-0 flex items-center gap-xs text-label-md font-semibold text-on-surface">
            <Icon name="task_alt" className="text-[16px] leading-none text-primary" />
            {t('profile.basics', { done: completedBasics, total: profileBasics.length })}
          </p>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {missingBasics.length > 0
              ? t('profile.missing', { items: missingBasics.join(', ') })
              : t('actions.saved', { ns: 'common' })}
          </p>
        </div>
        <Input label={t('profile.displayName')} error={errors.displayName?.message} {...register('displayName')} />
        <Input label={t('profile.phoneNumber')} autoComplete="tel" error={errors.phoneNumber?.message} {...register('phoneNumber')} />
        <Input label={t('profile.city')} error={errors.city?.message} {...register('city')} />
        {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-label-sm text-secondary">
            <Icon name="check_circle" className="text-[14px] leading-none" />
            {t('actions.saved', { ns: 'common' })}
          </p>
        ) : null}
        <Button type="submit" disabled={mutation.isPending} className="self-start">
          {mutation.isPending ? t('profile.saving') : t('profile.save')}
        </Button>
      </fieldset>
    </form>
  );
}
