/**
 * Parkio design tokens — the single source of truth for color, type, spacing,
 * radius and elevation. Mirrors the Pencil design file variables
 * (`untitled.pen`, PARKIO-DESIGN-BRIEF §4) exactly. Light is the brand
 * identity; dark is a calm deep navy (never black, never neon).
 */
export type ThemeMode = 'light' | 'dark';

export interface ThemeColors {
  /** Electric blue — CTAs, active nav, links, focus, active markers. */
  primary: string;
  /** Filled accent / celebration gradient partner. */
  primaryContainer: string;
  /** Soft blue fill, selected chips. */
  primaryFixed: string;
  /** Secondary blue fill, progress tracks. */
  primaryFixedDim: string;
  onPrimary: string;
  /** Verified emerald. */
  secondary: string;
  secondaryContainer: string;
  /** Amber — warnings, in-review. */
  tertiary: string;
  tertiaryContainer: string;
  error: string;
  errorContainer: string;
  background: string;
  surface: string;
  surfaceContainer1: string;
  surfaceContainer2: string;
  surfaceContainer3: string;
  surfaceContainer4: string;
  onSurface: string;
  onSurfaceVariant: string;
  outline: string;
  outlineVariant: string;
  inverseSurface: string;
  onInverseSurface: string;
  /** Glass fill for chrome floating over the map (pair with blur + hairline). */
  glass: string;
  glassHairline: string;
  /** Freshness ring track (the undepleted remainder). */
  ringTrack: string;
  /** Scrim behind sheets/modals. */
  scrim: string;
}

export const lightColors: ThemeColors = {
  primary: '#0050CB',
  primaryContainer: '#0066FF',
  primaryFixed: '#DAE1FF',
  primaryFixedDim: '#B3C5FF',
  onPrimary: '#FFFFFF',
  secondary: '#006C49',
  secondaryContainer: '#6CF8BB',
  tertiary: '#7F4F00',
  tertiaryContainer: '#A06500',
  error: '#BA1A1A',
  errorContainer: '#FFDAD6',
  background: '#F8F9FF',
  surface: '#FFFFFF',
  surfaceContainer1: '#EFF4FF',
  surfaceContainer2: '#E5EEFF',
  surfaceContainer3: '#DCE9FF',
  surfaceContainer4: '#D3E4FE',
  onSurface: '#0B1C30',
  onSurfaceVariant: '#424656',
  outline: '#727687',
  outlineVariant: '#C2C6D8',
  inverseSurface: '#213145',
  onInverseSurface: '#F8F9FF',
  glass: 'rgba(248,249,255,0.82)',
  glassHairline: 'rgba(255,255,255,1)',
  ringTrack: '#DCE9FF',
  scrim: 'rgba(11,28,48,0.4)',
};

export const darkColors: ThemeColors = {
  primary: '#0066FF',
  primaryContainer: '#0066FF',
  primaryFixed: '#1D3049',
  primaryFixedDim: '#B3C5FF',
  onPrimary: '#FFFFFF',
  secondary: '#6CF8BB',
  secondaryContainer: '#0F2A1F',
  tertiary: '#FFB955',
  tertiaryContainer: '#2A1F0A',
  error: '#FFB4AB',
  errorContainer: '#3A0E0C',
  background: '#0B1626',
  surface: '#101E33',
  surfaceContainer1: '#101E33',
  surfaceContainer2: '#16273F',
  surfaceContainer3: '#1D3049',
  surfaceContainer4: '#1D3049',
  onSurface: '#E7ECF7',
  onSurfaceVariant: '#A7B0C4',
  outline: '#727687',
  outlineVariant: 'rgba(255,255,255,0.08)',
  inverseSurface: '#E7ECF7',
  onInverseSurface: '#0B1626',
  glass: 'rgba(13,22,38,0.78)',
  glassHairline: 'rgba(255,255,255,0.08)',
  ringTrack: '#1D3049',
  scrim: 'rgba(0,0,0,0.5)',
};

/** 4/8pt spacing scale. */
export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  xxl: 24,
  xxxl: 32,
} as const;

export const radius = {
  input: 8,
  chip: 999,
  card: 16,
  sheet: 24,
  modal: 24,
  pill: 999,
} as const;

/** Inter font family names as registered by @expo-google-fonts/inter. */
export const fonts = {
  regular: 'Inter_400Regular',
  medium: 'Inter_500Medium',
  semiBold: 'Inter_600SemiBold',
  bold: 'Inter_700Bold',
} as const;

export interface TypeStyle {
  fontFamily: string;
  fontSize: number;
  lineHeight: number;
  letterSpacing: number;
}

/** Type scale per brief §4.3 (mobile values). */
export const typeScale = {
  displayLg: { fontFamily: fonts.bold, fontSize: 40, lineHeight: 44, letterSpacing: -0.8 },
  headlineLg: { fontFamily: fonts.bold, fontSize: 24, lineHeight: 30, letterSpacing: -0.3 },
  headlineMd: { fontFamily: fonts.semiBold, fontSize: 20, lineHeight: 26, letterSpacing: -0.2 },
  titleLg: { fontFamily: fonts.semiBold, fontSize: 18, lineHeight: 24, letterSpacing: 0 },
  titleMd: { fontFamily: fonts.semiBold, fontSize: 16, lineHeight: 22, letterSpacing: 0 },
  bodyLg: { fontFamily: fonts.regular, fontSize: 16, lineHeight: 24, letterSpacing: 0 },
  bodyMd: { fontFamily: fonts.regular, fontSize: 14, lineHeight: 21, letterSpacing: 0 },
  bodySm: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, letterSpacing: 0 },
  labelMd: { fontFamily: fonts.semiBold, fontSize: 12, lineHeight: 16, letterSpacing: 0.7 },
  labelSm: { fontFamily: fonts.medium, fontSize: 11, lineHeight: 14, letterSpacing: 0 },
  countdownLg: { fontFamily: fonts.bold, fontSize: 28, lineHeight: 32, letterSpacing: -0.3 },
} satisfies Record<string, TypeStyle>;

/** Ambient shadows (brief §4.4). Use sparingly — hierarchy is tonal first. */
export const shadows = {
  ambientSoft: {
    shadowColor: '#000000',
    shadowOpacity: 0.05,
    shadowRadius: 20,
    shadowOffset: { width: 0, height: 4 },
    elevation: 2,
  },
  ambientDeep: {
    shadowColor: '#000000',
    shadowOpacity: 0.1,
    shadowRadius: 40,
    shadowOffset: { width: 0, height: 12 },
    elevation: 8,
  },
  blueGlow: {
    shadowColor: '#0066FF',
    shadowOpacity: 0.35,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 8 },
    elevation: 10,
  },
} as const;

export interface Theme {
  mode: ThemeMode;
  colors: ThemeColors;
}

export const lightTheme: Theme = { mode: 'light', colors: lightColors };
export const darkTheme: Theme = { mode: 'dark', colors: darkColors };

/**
 * Freshness-ring color by remaining-life fraction (brief §5.1):
 * >66% blue (primary) · 33–66% amber · <33% red.
 * Uses the fixed light-brand hues in both themes for instant recognizability,
 * except dark mode brightens amber/red for contrast.
 */
export function freshnessColor(fraction: number, theme: Theme): string {
  if (fraction > 0.66) {
    return theme.colors.primary;
  }
  if (fraction > 0.33) {
    return theme.mode === 'dark' ? '#FFB955' : '#A06500';
  }
  return theme.mode === 'dark' ? '#FFB4AB' : '#BA1A1A';
}
