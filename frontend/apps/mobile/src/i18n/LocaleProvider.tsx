import * as SecureStore from 'expo-secure-store';
import { DEFAULT_LOCALE, LOCALE_STORAGE_KEY, normalizeLocale, type ParkioLocale } from '@parkio/types';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { translate } from './translations';

interface LocaleContextValue {
  locale: ParkioLocale;
  setLocale: (locale: ParkioLocale) => void;
  syncFromServer: (locale: ParkioLocale) => void;
  t: (value: string) => string;
}

// Direct component tests that do not mount AppProviders retain their historic
// English assertions. The real application always mounts LocaleProvider below,
// whose canonical first-run default is Turkish.
const fallback: LocaleContextValue = {
  locale: 'en',
  setLocale: () => undefined,
  syncFromServer: () => undefined,
  t: (value) => value,
};

const LocaleContext = createContext<LocaleContextValue>(fallback);

export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<ParkioLocale>(DEFAULT_LOCALE);

  useEffect(() => {
    let active = true;
    void SecureStore.getItemAsync(LOCALE_STORAGE_KEY)
      .then((stored) => {
        if (active && stored) setLocaleState(normalizeLocale(stored));
      })
      .catch(() => {
        // Turkish remains the fail-safe default when secure storage is unavailable.
      });
    return () => {
      active = false;
    };
  }, []);

  const applyLocale = useCallback((next: ParkioLocale) => {
    const normalized = normalizeLocale(next);
    setLocaleState(normalized);
    void SecureStore.setItemAsync(LOCALE_STORAGE_KEY, normalized).catch(() => {
      // The in-memory switch still applies; persistence can recover next launch.
    });
  }, []);

  const value = useMemo<LocaleContextValue>(
    () => ({
      locale,
      setLocale: applyLocale,
      syncFromServer: applyLocale,
      t: (text) => translate(locale, text),
    }),
    [applyLocale, locale],
  );

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale() {
  return useContext(LocaleContext);
}

