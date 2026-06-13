/**
 * Parkio V2 design tokens (see frontend/DESIGN_SYSTEM.md).
 *
 * @deprecated for new code — prefer Tailwind utility classes backed by the CSS
 * variables in `apps/web/src/styles/index.css`. These constants remain only so
 * existing inline-styled pages keep rendering consistently until they are
 * migrated; the keys are stable, the values now follow the V2 palette.
 */
export const colors = {
  /** primary `#0050cb` */
  primary: '#0050cb',
  /** on-primary-fixed-variant — pressed/hover primary */
  primaryHover: '#003fa4',
  onPrimary: '#ffffff',
  /** background / surface `#f8f9ff` */
  background: '#f8f9ff',
  /** surface-container-lowest — card surfaces */
  surface: '#ffffff',
  /** outline-variant */
  border: '#c2c6d8',
  /** on-surface */
  text: '#0b1c30',
  /** on-surface-variant */
  textMuted: '#424656',
  /** error */
  error: '#ba1a1a',
  /** error-container */
  errorBg: '#ffdad6',
  /** tertiary (V2 warning/amber) */
  warning: '#7f4f00',
  /** secondary (V2 success/emerald) */
  success: '#006c49',
} as const;

/** 4px-baseline spacing scale (xs 4 / sm 8 / md 16 / lg 24 / xl 32). */
export const spacing = {
  xs: '0.25rem',
  sm: '0.5rem',
  md: '1rem',
  lg: '1.5rem',
  xl: '2rem',
} as const;

/** Radius scale (DEFAULT 4 / lg 8 / xl 12 — `full` for pills). */
export const radius = {
  sm: '0.25rem',
  md: '0.5rem',
  lg: '0.75rem',
  full: '9999px',
} as const;

export const tokens = { colors, spacing, radius } as const;
