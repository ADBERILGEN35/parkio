import { zodResolver } from '@hookform/resolvers/zod';
import type { Profile } from '@parkio/types';
import { Button, Card, Input, LoadingState } from '@parkio/ui';
import { profileUpdateSchema, type ProfileUpdateFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';

export function ProfileDetailsCard() {
  const query = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  return (
    <Card title="Profile">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
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
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'profile'] }),
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
      <fieldset
        disabled={mutation.isPending}
        style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}
      >
        <Input label="Display name" error={errors.displayName?.message} {...register('displayName')} />
        <Input label="Phone number" autoComplete="tel" error={errors.phoneNumber?.message} {...register('phoneNumber')} />
        <Input label="City" error={errors.city?.message} {...register('city')} />
        {mutation.isError ? <ApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? <p style={{ margin: 0, fontSize: '0.875rem' }}>Saved.</p> : null}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Saving…' : 'Save profile'}
        </Button>
      </fieldset>
    </form>
  );
}
