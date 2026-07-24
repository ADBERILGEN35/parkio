import { useEffect, useState } from 'react';
import { AppState } from 'react-native';

/**
 * Re-render clock for elapsed displays.
 * Source of truth remains startedAt + Date.now(); this only triggers recalculation.
 * Recalculates on interval and AppState → active (background/foreground).
 */
export function useNowTicker(enabled: boolean, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!enabled) {
      return;
    }

    const bootId = setTimeout(() => {
      setNow(Date.now());
    }, 0);
    const id = setInterval(() => {
      setNow(Date.now());
    }, intervalMs);

    const subscription = AppState.addEventListener('change', (status) => {
      if (status === 'active') {
        setNow(Date.now());
      }
    });

    return () => {
      clearTimeout(bootId);
      clearInterval(id);
      subscription.remove();
    };
  }, [enabled, intervalMs]);

  return now;
}