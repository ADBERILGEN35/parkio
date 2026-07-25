import { useEffect, useState } from 'react';

/** 1 Hz clock while enabled — drives elapsed Parking Session display. */
export function useNowTicker(enabled: boolean): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!enabled) return;
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [enabled]);

  return now;
}