import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import { themeForScheme, type Theme } from './theme';

/**
 * Theme architecture.
 *
 * The provider resolves the active {@link Theme} from the OS color scheme so the
 * plumbing for dark mode is real and live today. Dark mode itself is only
 * *prepared* in M0 (see `colors.ts`) — a future sprint finalises the dark palette
 * and adds an explicit in-app override. Components must read color/spacing/type
 * exclusively through {@link useTheme}; never hardcode hex values.
 */
const ThemeContext = createContext<Theme | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const scheme = useColorScheme();
  const theme = useMemo(() => themeForScheme(scheme), [scheme]);
  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  const theme = useContext(ThemeContext);
  if (!theme) {
    throw new Error('useTheme must be used within a <ThemeProvider>.');
  }
  return theme;
}
