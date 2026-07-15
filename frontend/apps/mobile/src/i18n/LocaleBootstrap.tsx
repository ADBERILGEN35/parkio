import { normalizeLocale } from '@parkio/types';
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { usersApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { useLocale } from './LocaleProvider';

/** Authenticated server preference is authoritative, matching the web app. */
export function LocaleBootstrap() {
  const authenticated = useAuthStore((state) => state.user !== null);
  const { syncFromServer } = useLocale();
  const preferences = useQuery({
    queryKey: ['me', 'preferences'],
    queryFn: usersApi.getMyPreferences,
    enabled: authenticated,
  });

  useEffect(() => {
    if (!authenticated || !preferences.data?.preferredLocale) return;
    syncFromServer(normalizeLocale(preferences.data.preferredLocale));
  }, [authenticated, preferences.data?.preferredLocale, syncFromServer]);

  return null;
}

