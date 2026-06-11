export const colors = {
  primary: '#2563eb',
  primaryHover: '#1d4ed8',
  onPrimary: '#ffffff',
  background: '#f8fafc',
  surface: '#ffffff',
  border: '#e2e8f0',
  text: '#0f172a',
  textMuted: '#64748b',
  error: '#dc2626',
  errorBg: '#fef2f2',
  warning: '#d97706',
  success: '#16a34a',
} as const;

export const spacing = {
  xs: '0.25rem',
  sm: '0.5rem',
  md: '1rem',
  lg: '1.5rem',
  xl: '2rem',
} as const;

export const radius = {
  sm: '0.25rem',
  md: '0.5rem',
  lg: '0.75rem',
  full: '9999px',
} as const;

export const tokens = { colors, spacing, radius } as const;
