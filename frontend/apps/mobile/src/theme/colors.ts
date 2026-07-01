/**
 * Semantic color palettes — mirrored from the web app's Parkio V2 design tokens
 * (apps/web/src/styles/index.css + frontend/DESIGN_SYSTEM.md §1) so the native
 * app visually matches the web mobile UI.
 *
 * Mapping (web variable → mobile token):
 * - background/surface `#f8f9ff` → background
 * - surface-container-lowest `#ffffff` (cards) → surface
 * - surface-container `#e5eeff` (tonal buttons) → surfaceMuted
 * - outline-variant `#c2c6d8` (hairlines at /20–/40) → border, borderStrong
 * - on-surface `#0b1c30` / on-surface-variant `#424656` → text / textMuted
 * - primary `#0050cb`, pressed = on-primary-fixed-variant `#003fa4`
 * - primary-container `#0066ff` / on-primary-container (active pills, solid badges)
 * - secondary (emerald `#006c49`) → success; tertiary (amber `#7f4f00`) → warning
 * - error `#ba1a1a` / error-container `#ffdad6` → danger / dangerSoft
 * - `*Soft` tints follow the web SoftBadge recipe (10–20% alpha over surface).
 *
 * `light` is the fully-designed palette. `dark` is PREPARED but not yet finalised
 * (no dark screens ship on web either) — it stays a sensible starting point so
 * the theming plumbing is real and switchable. Keep both palettes key-for-key
 * identical so {@link ColorTokens} stays a single shared shape.
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
  primaryContainer: string;
  onPrimaryContainer: string;

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
  background: '#F8F9FF',
  surface: '#FFFFFF',
  surfaceMuted: '#E5EEFF',
  surfaceInverse: '#213145',
  border: 'rgba(194, 198, 216, 0.4)',
  borderStrong: '#C2C6D8',

  text: '#0B1C30',
  textMuted: '#424656',
  textInverse: '#EAF1FF',

  primary: '#0050CB',
  primaryPressed: '#003FA4',
  onPrimary: '#FFFFFF',
  primarySoft: 'rgba(0, 80, 203, 0.1)',
  primaryContainer: '#0066FF',
  onPrimaryContainer: '#F8F7FF',

  success: '#006C49',
  successSoft: 'rgba(0, 108, 73, 0.12)',
  warning: '#7F4F00',
  warningSoft: 'rgba(160, 101, 0, 0.2)',
  danger: '#BA1A1A',
  dangerSoft: '#FFDAD6',

  overlay: 'rgba(33, 49, 69, 0.4)',
  skeleton: '#DCE9FF',
};

export const dark: ColorTokens = {
  background: '#0B1220',
  surface: '#131C2B',
  surfaceMuted: '#1B2638',
  surfaceInverse: '#F5F7FA',
  border: 'rgba(75, 88, 115, 0.5)',
  borderStrong: '#33425C',

  text: '#EAF0F8',
  textMuted: '#9AA8BD',
  textInverse: '#0B1C30',

  primary: '#B3C5FF',
  primaryPressed: '#DAE1FF',
  onPrimary: '#001849',
  primarySoft: 'rgba(179, 197, 255, 0.14)',
  primaryContainer: '#0054D6',
  onPrimaryContainer: '#DAE1FF',

  success: '#4EDEA3',
  successSoft: 'rgba(78, 222, 163, 0.14)',
  warning: '#FFB95F',
  warningSoft: 'rgba(255, 185, 95, 0.16)',
  danger: '#FFB4AB',
  dangerSoft: '#93000A',

  overlay: 'rgba(0, 0, 0, 0.55)',
  skeleton: '#1F2A3D',
};
