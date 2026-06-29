/**
 * Semantic color palettes.
 *
 * `light` is the fully-designed palette. `dark` is PREPARED but not yet finalised
 * (M0 scope is architecture, not a polished dark theme) — it is a sensible,
 * shippable starting point so the theming plumbing is real and switchable.
 * Keep both palettes key-for-key identical so {@link ColorTokens} stays a single
 * shared shape.
 */
export interface ColorTokens {
  background: string;
  surface: string;
  surfaceMuted: string;
  surfaceInverse: string;
  border: string;
  borderStrong: string;

  text: string;
  textMuted: string;
  textInverse: string;

  primary: string;
  primaryPressed: string;
  onPrimary: string;
  primarySoft: string;

  success: string;
  successSoft: string;
  warning: string;
  warningSoft: string;
  danger: string;
  dangerSoft: string;

  overlay: string;
  skeleton: string;
}

export const light: ColorTokens = {
  background: '#F5F7FA',
  surface: '#FFFFFF',
  surfaceMuted: '#EEF1F6',
  surfaceInverse: '#0A2540',
  border: '#E2E8F0',
  borderStrong: '#CBD5E1',

  text: '#0A2540',
  textMuted: '#5B6B7F',
  textInverse: '#FFFFFF',

  primary: '#0050CB',
  primaryPressed: '#0042A8',
  onPrimary: '#FFFFFF',
  primarySoft: '#E5EEFB',

  success: '#12805C',
  successSoft: '#DCF3EA',
  warning: '#B45309',
  warningSoft: '#FCEFD9',
  danger: '#C4314B',
  dangerSoft: '#FBE3E7',

  overlay: 'rgba(10, 37, 64, 0.45)',
  skeleton: '#E2E8F0',
};

export const dark: ColorTokens = {
  background: '#0B1220',
  surface: '#131C2B',
  surfaceMuted: '#1B2638',
  surfaceInverse: '#F5F7FA',
  border: '#26334A',
  borderStrong: '#33425C',

  text: '#EAF0F8',
  textMuted: '#9AA8BD',
  textInverse: '#0A2540',

  primary: '#5B9CFF',
  primaryPressed: '#7FB2FF',
  onPrimary: '#04203F',
  primarySoft: '#16294A',

  success: '#34D399',
  successSoft: '#10362B',
  warning: '#FBBF24',
  warningSoft: '#3A2A0C',
  danger: '#F87171',
  dangerSoft: '#3A1620',

  overlay: 'rgba(0, 0, 0, 0.55)',
  skeleton: '#1F2A3D',
};
