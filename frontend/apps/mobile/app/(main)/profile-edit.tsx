import { zodResolver } from '@hookform/resolvers/zod';
import type { Profile } from '@parkio/types';
import { profileUpdateSchema, type ProfileUpdateFormValues } from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { useForm } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import { Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useToast } from '@/providers/ToastProvider';
import { usersApi } from '@/services/api';
import { toUserMessage } from '@/utils/errors';

export default function ProfileEditScreen() {
  const query = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Edit profile' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        {query.isPending ? (
          <SkeletonCard />
        ) : query.isError ? (
          <StateView
            icon="alert-circle-outline"
            title="Couldn’t load profile"
            actionLabel="Retry"
            onAction={() => void query.refetch()}
          />
        ) : (
          <ProfileForm profile={query.data} />
        )}
      </Screen>
    </>
  );
}

function ProfileForm({ profile }: { profile: Profile }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyProfile,
    onSuccess: (updated) => {
      queryClient.setQueryData(['me', 'profile'], updated);
      toast.showSuccess('Profile saved.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const { control, handleSubmit } = useForm<ProfileUpdateFormValues>({
    resolver: zodResolver(profileUpdateSchema),
    defaultValues: {
      displayName: profile.displayName ?? '',
      phoneNumber: profile.phoneNumber ?? '',
      city: profile.city ?? '',
    },
  });

  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      displayName: values.displayName || undefined,
      phoneNumber: values.phoneNumber || undefined,
      city: values.city || undefined,
    });
  });

  return (
    <Card>
      <AppText variant="body" tone="muted">
        Keep the public details drivers and moderators use to recognize you. Leave a field empty to
        keep its current value.
      </AppText>
      <View style={styles.form}>
        <FormTextField control={control} name="displayName" label="Display name" />
        <FormTextField
          control={control}
          name="phoneNumber"
          label="Phone number"
          keyboardType="phone-pad"
          autoComplete="tel"
          textContentType="telephoneNumber"
        />
        <FormTextField control={control} name="city" label="City" />
        <Button label="Save profile" onPress={onSubmit} loading={mutation.isPending} />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  form: { gap: 12, marginTop: 12 },
});