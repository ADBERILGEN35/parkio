import type { CSSProperties } from 'react';
import { Toaster } from 'sonner';

/**
 * Sonner's light rich-colour text is below WCAG AA (4.5:1) on its own backgrounds: success 4.29,
 * info 4.35, warning 3.07, error 4.36 (CL-F30, measured in Chromium). The app's semantic colours pass
 * on the same backgrounds (6.14, 6.50, 6.76 and 5.84). Set on the toaster element itself, these
 * override the values sonner's injected stylesheet gives that element.
 */
export const TOAST_TEXT_COLORS = {
  '--success-text': 'rgb(var(--color-secondary))',
  '--info-text': 'rgb(var(--color-primary))',
  '--warning-text': 'rgb(var(--color-tertiary))',
  '--error-text': 'rgb(var(--color-error))',
} as CSSProperties;

export function AppToaster() {
  return (
    <Toaster
      richColors
      closeButton
      position="top-right"
      style={TOAST_TEXT_COLORS}
      toastOptions={{
        duration: 4500,
        className: 'font-sans',
      }}
    />
  );
}
