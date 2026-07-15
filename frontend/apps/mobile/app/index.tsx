import { Redirect } from 'expo-router';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { useTheme } from '@/theme';
import { useAuthStore } from '@/state/authStore';

/**
 * Entry gate. Authenticated users land on Map (product home); guests on Login.
 * Verification/preparing/suspended stay on their dedicated routes when deep-linked.
 */
export default function Index() {
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const theme = useTheme();

  if (bootstrapPending) {
    return (
      <View style={[styles.center, { backgroundColor: theme.colors.background }]}>
        <ActivityIndicator color={theme.colors.primary} accessibilityLabel="Loading" />
      </View>
    );
  }

  return <Redirect href={isAuthenticated ? '/(main)/(tabs)/map' : '/(auth)/login'} />;
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
