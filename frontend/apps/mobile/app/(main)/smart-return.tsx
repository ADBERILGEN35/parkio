import { Stack } from 'expo-router';
import { useEffect } from 'react';
import { SmartReturnScreen } from '@/features/smart-return/presentation/SmartReturnScreen';
import { track } from '@/services/analytics';

export default function SmartReturnRoute() {
  useEffect(() => track('smart_return_opened'), []);
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Smart Return' }} />
      <SmartReturnScreen />
    </>
  );
}
