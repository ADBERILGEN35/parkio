import { zodResolver } from '@hookform/resolvers/zod';
import type { Profile } from '@parkio/types';
import { Button, Icon, Input, LoadingState } from '@parkio/ui';
import { profileUpdateSchema, type ProfileUpdateFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { showError, showSuccess } from '@/lib/toast';

export function ProfileDetailsCard() {
  const query = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  return (
    <SettingsSectionCard
      title="Profile"
      icon="person"
      description="Keep the public details drivers and moderators use to recognize you."
    >
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <ProfileForm profile={query.data} />
      )}
    </SettingsSectionCard>
  );
}

function ProfileForm({ profile }: { profile: Profile }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyProfile,
    onSuccess: (profile) => {
      queryClient.setQueryData(['me', 'profile'], profile);
      showSuccess('Profile saved.');
    },
    onError: () => showError('Could not save profile.'),
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
    { label: 'Display name', complete: Boolean(profile.displayName?.trim()) },
    { label: 'Phone number', complete: Boolean(profile.phoneNumber?.trim()) },
    { label: 'City', complete: Boolean(profile.city?.trim()) },
  ];
  const completedBasics = profileBasics.filter((item) => item.complete).length;
  const missingBasics = profileBasics.filter((item) => !item.complete).map((item) => item.label);

  return (
    <form onSubmit={onSubmit}>
      <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
        <div className="rounded-2xl bg-surface-container p-md">
          <p className="m-0 flex items-center gap-xs text-label-md font-semibold text-on-surface">
            <Icon name="task_alt" className="text-[16px] leading-none text-primary" />
            Profile basics: {completedBasics}/{profileBasics.length} complete
          </p>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {missingBasics.length > 0
              ? `Add ${missingBasics.join(', ').toLowerCase()} to make your account easier to recognize.`
              : 'Your public profile basics are complete.'}
          </p>
        </div>
        <Input label="Display name" error={errors.displayName?.message} {...register('displayName')} />
        <Input label="Phone number" autoComplete="tel" error={errors.phoneNumber?.message} {...register('phoneNumber')} />
        <Input label="City" error={errors.city?.message} {...register('city')} />
        <p className="m-0 text-label-sm text-on-surface-variant">
          Leave a field empty to keep its current value — empty fields are not sent.
        </p>
        {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-label-sm text-secondary">
            <Icon name="check_circle" className="text-[14px] leading-none" />
            Saved.
          </p>
        ) : null}
        <Button type="submit" disabled={mutation.isPending} className="self-start">
          {mutation.isPending ? 'Saving…' : 'Save profile'}
        </Button>
      </fieldset>
    </form>
  );
}
