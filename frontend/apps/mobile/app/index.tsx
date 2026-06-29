import { Redirect } from 'expo-router';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { useTheme } from '@/theme';
import { useAuthStore } from '@/state/authStore';

/**
 * Entry gate. While the cold-start session restore runs we hold a themed splash;
 * once it settles we redirect into the app or to login. The group layouts
 * ((auth)/(main)) re-assert this guard for deep links into protected routes.
 */
export default function Index() {
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

  return <Redirect href={isAuthenticated ? '/(main)/(tabs)/home' : '/(auth)/login'} />;
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
