import { Stack } from 'expo-router';
import { useTheme } from '@/theme/ThemeProvider';

export default function ShareLayout() {
  const theme = useTheme();
  return (
    <Stack
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: theme.colors.background },
        gestureEnabled: false,
      }}
    >
      <Stack.Screen name="index" />
      <Stack.Screen
        name="camera"
        options={{ animation: 'fade', contentStyle: { backgroundColor: '#0B1626' }, gestureEnabled: true }}
      />
    </Stack>
  );
}
