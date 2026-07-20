import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation } from '@tanstack/react-query';
import { isStrongPassword } from '@parkio/validation';
import { Button } from '@/components/ui/Button';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { TextField } from '@/components/ui/TextField';
import { PasswordChecklist } from '@/features/auth/PasswordChecklist';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

export default function ChangePasswordScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const toast = useToast();
  const insets = useSafeAreaInsets();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null);

  const change = useMutation({
    mutationFn: () => authApi.changePassword({ currentPassword, newPassword }),
    onSuccess: () => {
      toast.show(t('profile.password.changed'), 'success');
      router.back();
    },
    onError: (raw) => setError(describeApiError(raw, t)),
  });

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.colors.background }]} edges={['top']}>
      <ScreenHeader title={t('profile.password.title')} />
      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 24 }]}
        keyboardShouldPersistTaps="handled"
      >
        <TextField
          label={t('profile.password.current')}
          password
          autoComplete="current-password"
          value={currentPassword}
          onChangeText={setCurrentPassword}
          error={error?.message ?? null}
          traceId={error?.traceId ?? null}
        />
        <View style={styles.newBlock}>
          <TextField
            label={t('profile.password.new')}
            password
            autoComplete="new-password"
            value={newPassword}
            onChangeText={setNewPassword}
          />
          <PasswordChecklist password={newPassword} />
        </View>
        <Button
          label={t('common.save')}
          onPress={() => {
            setError(null);
            change.mutate();
          }}
          loading={change.isPending}
          disabled={currentPassword.length === 0 || !isStrongPassword(newPassword)}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, paddingTop: 8, gap: 16 },
  newBlock: { gap: 10 },
});
