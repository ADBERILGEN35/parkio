/**
 * Parkio V2 design system theme — consolidated from frontend/DESIGN_SYSTEM.md §3.
 *
 * Colors point at CSS variables (RGB triplets defined in src/styles/index.css)
 * so Tailwind alpha modifiers keep working (e.g. `bg-secondary/10` status
 * badges) and a future dark mode only has to override the variables.
 */

/** @param {string} name */
const v = (name) => `rgb(var(--${name}) / <alpha-value>)`;

/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}', '../../packages/ui/src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Primary
        primary: v('color-primary'),
        'on-primary': v('color-on-primary'),
        'primary-container': v('color-primary-container'),
        'on-primary-container': v('color-on-primary-container'),
        'primary-fixed': v('color-primary-fixed'),
        'primary-fixed-dim': v('color-primary-fixed-dim'),
        'on-primary-fixed': v('color-on-primary-fixed'),
        'on-primary-fixed-variant': v('color-on-primary-fixed-variant'),
        'inverse-primary': v('color-inverse-primary'),
        'surface-tint': v('color-surface-tint'),
        // Secondary (V2: emerald — success / verified)
        secondary: v('color-secondary'),
        'on-secondary': v('color-on-secondary'),
        'secondary-container': v('color-secondary-container'),
        'on-secondary-container': v('color-on-secondary-container'),
        'secondary-fixed': v('color-secondary-fixed'),
        'secondary-fixed-dim': v('color-secondary-fixed-dim'),
        'on-secondary-fixed': v('color-on-secondary-fixed'),
        'on-secondary-fixed-variant': v('color-on-secondary-fixed-variant'),
        // Tertiary (V2: amber — warning / streaks)
        tertiary: v('color-tertiary'),
        'on-tertiary': v('color-on-tertiary'),
        'tertiary-container': v('color-tertiary-container'),
        'on-tertiary-container': v('color-on-tertiary-container'),
        'tertiary-fixed': v('color-tertiary-fixed'),
        'tertiary-fixed-dim': v('color-tertiary-fixed-dim'),
        'on-tertiary-fixed': v('color-on-tertiary-fixed'),
        'on-tertiary-fixed-variant': v('color-on-tertiary-fixed-variant'),
        // Error
        error: v('color-error'),
        'on-error': v('color-on-error'),
        'error-container': v('color-error-container'),
        'on-error-container': v('color-on-error-container'),
        // Surfaces & neutrals
        background: v('color-background'),
        'on-background': v('color-on-background'),
        surface: v('color-surface'),
        'surface-bright': v('color-surface-bright'),
        'surface-dim': v('color-surface-dim'),
        'surface-container-lowest': v('color-surface-container-lowest'),
        'surface-container-low': v('color-surface-container-low'),
        'surface-container': v('color-surface-container'),
        'surface-container-high': v('color-surface-container-high'),
        'surface-container-highest': v('color-surface-container-highest'),
        'surface-variant': v('color-surface-variant'),
        'on-surface': v('color-on-surface'),
        'on-surface-variant': v('color-on-surface-variant'),
        outline: v('color-outline'),
        'outline-variant': v('color-outline-variant'),
        'inverse-surface': v('color-inverse-surface'),
        'inverse-on-surface': v('color-inverse-on-surface'),
        // Semantic aliases (see CSS variables — success = secondary, warning = tertiary)
        success: v('color-success'),
        warning: v('color-warning'),
        muted: v('color-muted'),
      },
      borderRadius: {
        DEFAULT: '0.25rem',
        lg: '0.5rem',
        xl: '0.75rem',
        // 2xl (1rem) and 3xl (1.5rem) keep Tailwind defaults — both used by the design system
        full: '9999px',
      },
      spacing: {
        base: '4px',
        xs: '4px',
        sm: '8px',
        md: '16px',
        lg: '24px',
        xl: '32px',
        '2xl': '48px',
        gutter: '16px',
        'container-margin': '20px',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
      },
      fontSize: {
        'display-lg': ['48px', { lineHeight: '1.1', letterSpacing: '-0.02em', fontWeight: '700' }],
        'headline-lg': ['32px', { lineHeight: '1.2', letterSpacing: '-0.02em', fontWeight: '700' }],
        'headline-lg-mobile': ['24px', { lineHeight: '1.2', fontWeight: '700' }],
        'headline-md': ['24px', { lineHeight: '1.3', letterSpacing: '-0.01em', fontWeight: '600' }],
        'title-lg': ['20px', { lineHeight: '1.4', fontWeight: '600' }],
        'body-lg': ['16px', { lineHeight: '1.6', fontWeight: '400' }],
        'body-md': ['14px', { lineHeight: '1.5', fontWeight: '400' }],
        'label-md': ['12px', { lineHeight: '1', letterSpacing: '0.01em', fontWeight: '600' }],
        'label-sm': ['11px', { lineHeight: '1', fontWeight: '500' }],
      },
      boxShadow: {
        soft: '0px 4px 20px rgba(0, 0, 0, 0.05)',
        deep: '0px 12px 40px rgba(0, 0, 0, 0.1)',
        'sheet-left': '-10px 0px 40px rgba(0, 0, 0, 0.08)',
        'sheet-up': '0px -10px 40px rgba(0, 0, 0, 0.05)',
        'nav-up': '0px -4px 20px rgba(0, 0, 0, 0.05)',
        lift: '0 12px 24px rgba(0, 0, 0, 0.1)',
      },
      transitionDuration: {
        fast: '100ms',
        std: '250ms',
        fluid: '400ms',
      },
      transitionTimingFunction: {
        spring: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
      },
      keyframes: {
        'pulse-glow': {
          '0%': { boxShadow: '0 0 0 0 rgba(0, 80, 203, 0.4)' },
          '70%': { boxShadow: '0 0 0 10px rgba(0, 80, 203, 0)' },
          '100%': { boxShadow: '0 0 0 0 rgba(0, 80, 203, 0)' },
        },
        'slide-in-right': {
          from: { transform: 'translateX(100%)', opacity: '0' },
          to: { transform: 'translateX(0)', opacity: '1' },
        },
        'fade-in-up': {
          from: { opacity: '0', transform: 'translateY(10px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-10px)' },
        },
      },
      animation: {
        'pulse-glow': 'pulse-glow 2s infinite',
        'slide-in-right': 'slide-in-right 400ms cubic-bezier(0.34, 1.56, 0.64, 1) forwards',
        'fade-in-up': 'fade-in-up 0.3s ease-in-out forwards',
        float: 'float 6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};
