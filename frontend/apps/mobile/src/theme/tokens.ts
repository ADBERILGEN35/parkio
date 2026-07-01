/**
 * Design tokens — the primitive scales every component composes from.
 *
 * Values mirror the web app's Parkio V2 Tailwind theme
 * (apps/web/tailwind.config.js) so native screens visually match web mobile:
 * 4px-baseline spacing, the web radius scale, the web type ramp, and RN
 * translations of the `shadow-soft`/`shadow-deep` box shadows. Colors live in
 * {@link ./colors} as semantic light/dark palettes.
 */

export const spacing = {
  none: 0,
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  '2xl': 48,
  '3xl': 64,
  /** Web `container-margin` (20px) — screen edge padding. */
  gutter: 20,
} as const;

/** Web radius scale: rounded (4) / lg (8) / xl (12) / 2xl (16) / 3xl (24). */
export const radius = {
  sm: 4,
  md: 8,
  lg: 12,
  xl: 16,
  '2xl': 24,
  full: 999,
} as const;

/** Minimum interactive size — WCAG / platform touch-target guidance (44pt). */
export const HIT_SLOP = { top: 8, bottom: 8, left: 8, right: 8 } as const;
export const MIN_TOUCH_TARGET = 44;

/**
 * Web type ramp (tailwind fontSize):
 * display=headline-lg, title=headline-lg-mobile, heading=title-lg,
 * body=body-md (the web default), callout=body-lg (lead copy),
 * label=label-md, caption=label-sm. Line heights get a touch more room than
 * the web's `1`-line labels so native text never clips.
 */
export const typography = {
  display: { fontSize: 32, lineHeight: 38, fontWeight: '700', letterSpacing: -0.6 },
  title: { fontSize: 24, lineHeight: 29, fontWeight: '700' },
  heading: { fontSize: 20, lineHeight: 28, fontWeight: '600' },
  subtitle: { fontSize: 16, lineHeight: 24, fontWeight: '600' },
  body: { fontSize: 14, lineHeight: 21, fontWeight: '400' },
  callout: { fontSize: 16, lineHeight: 26, fontWeight: '400' },
  label: { fontSize: 12, lineHeight: 16, fontWeight: '600', letterSpacing: 0.12 },
  caption: { fontSize: 11, lineHeight: 15, fontWeight: '500' },
} as const;

export type TypographyVariant = keyof typeof typography;

/** RN translations of the web box shadows (soft = cards, deep = floating). */
export const elevation = {
  card: {
    shadowColor: '#000000',
    shadowOpacity: 0.05,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 4 },
    elevation: 2,
  },
  floating: {
    shadowColor: '#000000',
    shadowOpacity: 0.1,
    shadowRadius: 20,
    shadowOffset: { width: 0, height: 12 },
    elevation: 8,
  },
} as const;
