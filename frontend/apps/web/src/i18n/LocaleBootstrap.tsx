import { useEffect } from 'react';
import { normalizeLocale } from '@parkio/types';
import { useAuthStore } from '@/auth/store';
import { useMyPreferencesLocaleBootstrapQuery } from '@/data/hooks/useMeQueries';
import { useLocaleStore } from '@/i18n/localeStore';

/**
 * When authenticated, restore the server-side locale preference on bootstrap/login.
 * Logout keeps the last explicit device locale (localStorage).
 */
export function LocaleBootstrap() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const syncFromServer = useLocaleStore((s) => s.syncFromServer);

  const preferencesQuery = useMyPreferencesLocaleBootstrapQuery({ enabled: isAuthenticated });

  useEffect(() => {
    if (!isAuthenticated || !preferencesQuery.data?.preferredLocale) return;
    syncFromServer(normalizeLocale(preferencesQuery.data.preferredLocale));
  }, [isAuthenticated, preferencesQuery.data?.preferredLocale, syncFromServer]);

  return null;
}
