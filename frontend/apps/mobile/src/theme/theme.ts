import { dark, light, type ColorTokens } from './colors';
import { elevation, radius, spacing, typography } from './tokens';

export interface Theme {
  scheme: 'light' | 'dark';
  colors: ColorTokens;
  spacing: typeof spacing;
  radius: typeof radius;
  typography: typeof typography;
  elevation: typeof elevation;
}

export const lightTheme: Theme = {
  scheme: 'light',
  colors: light,
  spacing,
  radius,
  typography,
  elevation,
};

export const darkTheme: Theme = {
  scheme: 'dark',
  colors: dark,
  spacing,
  radius,
  typography,
  elevation,
};

export function themeForScheme(scheme: string | null | undefined): Theme {
  return scheme === 'dark' ? darkTheme : lightTheme;
}
