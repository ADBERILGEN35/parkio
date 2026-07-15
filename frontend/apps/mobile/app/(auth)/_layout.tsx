import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme';

/**
 * Auth group guard: a signed-in user has no business on login/register, so bounce
 * them into the app. While bootstrap is pending we render nothing (the root index
 * holds the splash).
 */
export default function AuthLayout() {
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const theme = useTheme();

  if (bootstrapPending) {
    return (
      <View style={[styles.center, { backgroundColor: theme.colors.background }]}>
        <ActivityIndicator color={theme.colors.primary} />
      </View>
    );
  }
  if (isAuthenticated) return <Redirect href="/(main)/(tabs)/map" />;

  return <Stack screenOptions={{ headerShown: false }} />;
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
