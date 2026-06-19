import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { toast } from 'sonner';
import { AppToaster } from '@/components/AppToaster';
import { showError, showSuccess } from './toast';

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
});
