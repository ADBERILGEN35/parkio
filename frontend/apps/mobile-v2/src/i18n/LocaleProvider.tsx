import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { readJson, writeJson } from '@/services/jsonStore';
import { translations, type ParkioLocale, type TranslationKey } from './translations';

/**
 * Locale state + `t()` lookup. Turkish is the product default (İzmir beta);
 * the user's explicit choice is persisted and survives cold starts.
 */
export type TranslateParams = Record<string, string | number>;
export type Translator = (key: TranslationKey, params?: TranslateParams) => string;

interface LocaleContextValue {
  locale: ParkioLocale;
  setLocale: (locale: ParkioLocale) => void;
  t: Translator;
}

const LocaleContext = createContext<LocaleContextValue>({
  locale: 'tr',
  setLocale: () => undefined,
  t: (key) => key,
});

const STORE_KEY = 'locale';

export function interpolate(template: string, params?: TranslateParams): string {
  if (!params) {
    return template;
  }
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match,
  );
}

export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<ParkioLocale>('tr');

  useEffect(() => {
    void readJson<ParkioLocale>(STORE_KEY).then((stored) => {
      if (stored === 'tr' || stored === 'en') {
        setLocaleState(stored);
      }
    });
  }, []);

  const setLocale = useCallback((next: ParkioLocale) => {
    setLocaleState(next);
    void writeJson(STORE_KEY, next);
  }, []);

  const value = useMemo<LocaleContextValue>(() => {
    const table = translations[locale];
    const t: Translator = (key, params) => interpolate(table[key] ?? key, params);
    return { locale, setLocale, t };
  }, [locale, setLocale]);

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale(): LocaleContextValue {
  return useContext(LocaleContext);
}

export function useT(): Translator {
  return useContext(LocaleContext).t;
}
