import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { normalizeLocale } from '@parkio/types';
import { usersApi } from '@/api';
import { useAuthStore } from '@/auth/store';
import { useLocaleStore } from '@/i18n/localeStore';

/**
 * When authenticated, restore the server-side locale preference on bootstrap/login.
 * Logout keeps the last explicit device locale (localStorage).
 */
export function LocaleBootstrap() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const syncFromServer = useLocaleStore((s) => s.syncFromServer);

  const preferencesQuery = useQuery({
    queryKey: ['me', 'preferences', 'locale-bootstrap'],
    queryFn: usersApi.getMyPreferences,
    enabled: isAuthenticated,
    staleTime: 60_000,
  });

  useEffect(() => {
    if (!isAuthenticated || !preferencesQuery.data?.preferredLocale) return;
    syncFromServer(normalizeLocale(preferencesQuery.data.preferredLocale));
  }, [isAuthenticated, preferencesQuery.data?.preferredLocale, syncFromServer]);

  return null;
}