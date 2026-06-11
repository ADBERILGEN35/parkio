import { zodResolver } from '@hookform/resolvers/zod';
import type { UserPreference } from '@parkio/types';
import { Button, Card, Input, LoadingState } from '@parkio/ui';
import {
  PREFERRED_RADIUS_MAX_METERS,
  PREFERRED_RADIUS_MIN_METERS,
  preferencesUpdateSchema,
  type PreferencesUpdateFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { usersApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';

export function PreferencesCard() {
  const query = useQuery({ queryKey: ['me', 'preferences'], queryFn: usersApi.getMyPreferences });

  return (
    <Card title="Preferences">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ApiErrorMessage error={query.error} />
      ) : (
        <PreferencesForm preferences={query.data} />
      )}
    </Card>
  );
}

function PreferencesForm({ preferences }: { preferences: UserPreference }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyPreferences,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'preferences'] }),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<PreferencesUpdateFormValues>({
    resolver: zodResolver(preferencesUpdateSchema),
    defaultValues: {
      preferredRadiusMeters: preferences.preferredRadiusMeters,
      notificationsEnabled: preferences.notificationsEnabled,
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <form onSubmit={onSubmit}>
      <fieldset
        disabled={mutation.isPending}
        style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}
      >
        <Input
          label="Preferred search radius (meters)"
          type="number"
          min={PREFERRED_RADIUS_MIN_METERS}
          max={PREFERRED_RADIUS_MAX_METERS}
          error={errors.preferredRadiusMeters?.message}
          {...register('preferredRadiusMeters')}
        />
        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.875rem' }}>
          <input type="checkbox" {...register('notificationsEnabled')} />
          Notifications enabled
        </label>
        {mutation.isError ? <ApiErrorMessage error={mutation.error} /> : null}
        {mutation.isSuccess ? <p style={{ margin: 0, fontSize: '0.875rem' }}>Saved.</p> : null}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Saving…' : 'Save preferences'}
        </Button>
      </fieldset>
    </form>
  );
}
