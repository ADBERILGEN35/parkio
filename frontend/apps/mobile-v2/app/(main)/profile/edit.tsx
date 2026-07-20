import { useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AppText } from '@/components/ui/AppText';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { TextField } from '@/components/ui/TextField';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { usersApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

export default function ProfileEditScreen() {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const insets = useSafeAreaInsets();
  const { colors } = theme;

  const profile = useQuery({ queryKey: ['my-profile'], queryFn: () => usersApi.getMyProfile() });
  const [displayName, setDisplayName] = useState('');
  const [phone, setPhone] = useState('');
  const [city, setCity] = useState('');
  const hydrated = useRef(false);

  useEffect(() => {
    if (profile.data && !hydrated.current) {
      hydrated.current = true;
      setDisplayName(profile.data.displayName ?? '');
      setPhone(profile.data.phoneNumber ?? '');
      setCity(profile.data.city ?? '');
    }
  }, [profile.data]);

  const save = useMutation({
    mutationFn: () =>
      usersApi.updateMyProfile({
        displayName: displayName.trim(),
        phoneNumber: phone.trim(),
        city: city.trim(),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['my-profile'], updated);
      toast.show(t('profile.account.saved'), 'success');
    },
    onError: (error) => toast.show(describeApiError(error, t).message, 'error'),
  });

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('profile.menu.account')} />
      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 24 }]}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.emailRow}>
          <AppText variant="bodyMd" color={colors.onSurfaceVariant} style={styles.email} numberOfLines={1}>
            {user?.email}
          </AppText>
          <Badge
            label={t('profile.account.emailVerified')}
            icon="check-decagram-outline"
            fg={colors.secondary}
            bg={colors.secondaryContainer}
            size="sm"
          />
        </View>
        <TextField
          label={t('profile.account.displayName')}
          placeholder={t('profile.account.displayNamePlaceholder')}
          value={displayName}
          onChangeText={setDisplayName}
          maxLength={50}
        />
        <TextField
          label={t('profile.account.phone')}
          keyboardType="phone-pad"
          value={phone}
          onChangeText={setPhone}
          maxLength={32}
        />
        <TextField label={t('profile.account.city')} value={city} onChangeText={setCity} maxLength={80} />
        <Button
          label={t('common.save')}
          onPress={() => save.mutate()}
          loading={save.isPending}
          disabled={displayName.trim().length < 2}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, paddingTop: 8, gap: 16 },
  emailRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  email: { flexShrink: 1 },
});
