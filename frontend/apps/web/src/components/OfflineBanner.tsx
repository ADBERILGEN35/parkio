import { Icon } from '@parkio/ui';
import { useEffect, useState } from 'react';

function getOnlineState() {
  if (typeof navigator === 'undefined') return true;
  return navigator.onLine;
}

export function OfflineBanner() {
  const [online, setOnline] = useState(getOnlineState);

  useEffect(() => {
    const update = () => setOnline(getOnlineState());
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    update();
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
    };
  }, []);

  if (online) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed inset-x-0 top-0 z-[2000] flex justify-center px-sm pt-sm"
    >
      <div className="glass-panel flex max-w-lg items-center gap-sm rounded-full px-md py-sm text-label-sm font-medium text-on-surface shadow-lg">
        <Icon name="wifi_off" className="text-[18px] leading-none text-warning" />
        You are offline. Parkio will reconnect when your network returns.
      </div>
    </div>
  );
}
