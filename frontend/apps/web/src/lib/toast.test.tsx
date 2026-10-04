import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { toast } from 'sonner';
import { AppToaster } from '@/components/AppToaster';
import { showError, showSuccess, showWarning } from './toast';

afterEach(() => {
  toast.dismiss();
});

describe('toast helpers', () => {
  it('provider renders success toasts', async () => {
    render(<AppToaster />);

    showSuccess('Profile saved.');

    expect(await screen.findByText('Profile saved.')).toBeInTheDocument();
  });

  it('deduplicates repeated messages by helper id', async () => {
    render(<AppToaster />);

    showError('Could not save profile.');
    showError('Could not save profile.');

    await screen.findByText('Could not save profile.');
    await waitFor(() => {
      expect(screen.getAllByText('Could not save profile.')).toHaveLength(1);
    });
  });

  it('gives rich toasts the app text colours, which meet WCAG AA on their backgrounds (CL-F30)', async () => {
    render(<AppToaster />);

    showWarning('Signed out on this device.');
    await screen.findByText('Signed out on this device.');

    const toaster = document.querySelector<HTMLElement>('[data-sonner-toaster]');
    expect(toaster?.style.getPropertyValue('--success-text')).toBe('rgb(var(--color-secondary))');
    expect(toaster?.style.getPropertyValue('--info-text')).toBe('rgb(var(--color-primary))');
    expect(toaster?.style.getPropertyValue('--warning-text')).toBe('rgb(var(--color-tertiary))');
    expect(toaster?.style.getPropertyValue('--error-text')).toBe('rgb(var(--color-error))');
  });
});
