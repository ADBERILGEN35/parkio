import { QueryClientProvider, focusManager, onlineManager } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { AppState, Platform } from 'react-native';
import NetInfo from '@react-native-community/netinfo';
import { SessionQueryCacheSync } from '@/data/SessionQueryCacheSync';
import { createMobileQueryClient } from './query-client';

/**
 * Single Mobile QueryClient owner (WP-07): cancel-safe retries, AppState focus,
 * NetInfo online, and session cache sync on identity change.
 */
export function QueryProvider({ children }: { children: ReactNode }) {
  const [client] = useState(createMobileQueryClient);

  useEffect(() => {
    return onlineManager.setEventListener((setOnline) =>
      NetInfo.addEventListener((state) => {
        setOnline(Boolean(state.isConnected));
      }),
    );
  }, []);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (status) => {
      if (Platform.OS !== 'web') {
        focusManager.setFocused(status === 'active');
      }
    });
    return () => subscription.remove();
  }, []);

  return (
    <QueryClientProvider client={client}>
      <SessionQueryCacheSync />
      {children}
    </QueryClientProvider>
  );
}
