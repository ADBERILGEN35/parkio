import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import { readJson, writeJson } from '@/services/jsonStore';
import { darkTheme, lightTheme, type Theme, type ThemeMode } from './tokens';

/** User-facing appearance choice; `system` follows the OS. */
export type AppearancePreference = 'system' | ThemeMode;

interface ThemeContextValue {
  theme: Theme;
  preference: AppearancePreference;
  setPreference: (preference: AppearancePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: lightTheme,
  preference: 'system',
  setPreference: () => undefined,
});

const STORE_KEY = 'appearance';

export function ThemeProvider({ children }: { children: ReactNode }) {
  const systemScheme = useColorScheme();
  const [preference, setPreferenceState] = useState<AppearancePreference>('system');

  useEffect(() => {
    void readJson<AppearancePreference>(STORE_KEY).then((stored) => {
      if (stored === 'light' || stored === 'dark' || stored === 'system') {
        setPreferenceState(stored);
      }
    });
  }, []);

  const setPreference = useCallback((next: AppearancePreference) => {
    setPreferenceState(next);
    void writeJson(STORE_KEY, next);
  }, []);

  const value = useMemo<ThemeContextValue>(() => {
    const mode: ThemeMode =
      preference === 'system' ? (systemScheme === 'dark' ? 'dark' : 'light') : preference;
    return {
      theme: mode === 'dark' ? darkTheme : lightTheme,
      preference,
      setPreference,
    };
  }, [preference, systemScheme, setPreference]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  return useContext(ThemeContext).theme;
}

export function useAppearance(): ThemeContextValue {
  return useContext(ThemeContext);
}
