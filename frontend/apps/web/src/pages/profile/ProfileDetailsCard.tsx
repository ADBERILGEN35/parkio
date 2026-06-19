import { zodResolver } from '@hookform/resolvers/zod';
import type { Profile } from '@parkio/types';
import { Button, Card, Icon, Input, LoadingState } from '@parkio/ui';
import { profileUpdateSchema, type ProfileUpdateFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { showError, showSuccess } from '@/lib/toast';

export function ProfileDetailsCard() {
  const query = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  return (
    <Card title="Profile">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <ProfileForm profile={query.data} />
      )}
    </Card>
  );
}

function ProfileForm({ profile }: { profile: Profile }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyProfile,
    onSuccess: () => {
      showSuccess('Profile saved.');
      void queryClient.invalidateQueries({ queryKey: ['me', 'profile'] });
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

  return (
    <form onSubmit={onSubmit}>
      <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
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
