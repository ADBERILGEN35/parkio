import { useEffect, useState } from 'react';
import NetInfo from '@react-native-community/netinfo';

/** Reactive connectivity flag (optimistic `true` until NetInfo reports). */
export function useOnlineStatus(): boolean {
  const [online, setOnline] = useState(true);

  useEffect(() => {
    const unsubscribe = NetInfo.addEventListener((state) => {
      setOnline(state.isConnected !== false);
    });
    return unsubscribe;
  }, []);

  return online;
}
