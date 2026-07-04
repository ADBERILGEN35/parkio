import { zodResolver } from '@hookform/resolvers/zod';
import type { UserPreference } from '@parkio/types';
import {
  PREFERRED_RADIUS_MAX_METERS,
  PREFERRED_RADIUS_MIN_METERS,
  preferencesUpdateSchema,
  type PreferencesUpdateFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Pressable, StyleSheet, View } from 'react-native';
import { Button, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useToast } from '@/providers/ToastProvider';
import { usersApi } from '@/services/api';
import { toUserMessage } from '@/utils/errors';
import { useTheme } from '@/theme';

export default function PreferencesScreen() {
  const query = useQuery({ queryKey: ['me', 'preferences'], queryFn: usersApi.getMyPreferences });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Preferences' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        {query.isPending ? (
          <SkeletonCard />
        ) : query.isError ? (
          <StateView
            icon="alert-circle-outline"
            title="Couldn’t load preferences"
            actionLabel="Retry"
            onAction={() => void query.refetch()}
          />
        ) : (
          <PreferencesForm preferences={query.data} />
        )}
      </Screen>
    </>
  );
}

function PreferencesForm({ preferences }: { preferences: UserPreference }) {
  const toast = useToast();
  const theme = useTheme();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: usersApi.updateMyPreferences,
    onSuccess: (updated) => {
      queryClient.setQueryData(['me', 'preferences'], updated);
      toast.showSuccess('Preferences saved.');
    },
    onError: (error) => toast.showError(toUserMessage(error)),
  });

  const { control, handleSubmit } = useForm<PreferencesUpdateFormValues>({
    resolver: zodResolver(preferencesUpdateSchema),
    defaultValues: {
      preferredRadiusMeters: preferences.preferredRadiusMeters,
      notificationsEnabled: preferences.notificationsEnabled,
    },
  });

  const radiusRaw = useWatch({ control, name: 'preferredRadiusMeters' });
  const radius = Number(radiusRaw);
  const radiusLabel = Number.isFinite(radius)
    ? radius >= 1000
      ? `${(radius / 1000).toFixed(radius % 1000 === 0 ? 0 : 1)} km`
      : `${radius} m`
    : '—';

  return (
    <Card>
      <AppText variant="body" tone="muted">
        Tune nearby search radius and account notification preferences.
      </AppText>
      <View style={styles.form}>
        <FormTextField
          control={control}
          name="preferredRadiusMeters"
          label={`Preferred search radius (${radiusLabel})`}
          keyboardType="number-pad"
        />
        <AppText variant="caption" tone="muted">
          Between {PREFERRED_RADIUS_MIN_METERS} m and {PREFERRED_RADIUS_MAX_METERS} m.
        </AppText>
        <Controller
          control={control}
          name="notificationsEnabled"
          render={({ field }) => (
            <Pressable
              accessibilityRole="switch"
              accessibilityState={{ checked: field.value }}
              onPress={() => field.onChange(!field.value)}
              style={[
                styles.toggle,
                {
                  borderColor: theme.colors.border,
                  backgroundColor: field.value ? theme.colors.primarySoft : theme.colors.surface,
                  borderRadius: theme.radius.lg,
                },
              ]}
            >
              <AppText variant="subtitle">Notifications enabled</AppText>
              <AppText variant="caption" tone="muted">
                {field.value ? 'On' : 'Off'}
              </AppText>
            </Pressable>
          )}
        />
        <Button
          label="Save preferences"
          onPress={handleSubmit((values) =>
            mutation.mutate({
              preferredRadiusMeters: Number(values.preferredRadiusMeters),
              notificationsEnabled: values.notificationsEnabled,
            }),
          )}
          loading={mutation.isPending}
        />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  form: { gap: 12, marginTop: 12 },
  toggle: { borderWidth: 1, padding: 14, gap: 4, minHeight: 44 },
});