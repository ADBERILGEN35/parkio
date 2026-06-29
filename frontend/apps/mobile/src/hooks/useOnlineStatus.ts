import NetInfo from '@react-native-community/netinfo';
import { useEffect, useState } from 'react';

/**
 * Tracks connectivity via NetInfo. `null` while the first sample is pending so the
 * UI doesn't flash an offline banner before the real state is known.
 */
export function useOnlineStatus(): boolean | null {
  const [online, setOnline] = useState<boolean | null>(null);

  useEffect(() => {
    const unsubscribe = NetInfo.addEventListener((state) => {
      const reachable = state.isInternetReachable;
      setOnline(state.isConnected === true && reachable !== false);
    });
    return unsubscribe;
  }, []);

  return online;
}
