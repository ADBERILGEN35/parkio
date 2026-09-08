import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { AuthGateDialog } from './AuthGateDialog';

describe('AuthGateDialog', () => {
  it('offers login with return path and truthful registration status CTA', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <AuthGateDialog
        open
        onClose={onClose}
        returnPath="/facilities/00000000-0000-0000-0000-000000000901"
      />,
      { initialEntries: ['/explore'] },
    );

    expect(screen.getByTestId('auth-gate-dialog')).toHaveTextContent('Sign in required');
    expect(screen.getByTestId('auth-gate-login')).toHaveAttribute(
      'href',
      '/login?return=%2Ffacilities%2F00000000-0000-0000-0000-000000000901',
    );
    expect(screen.getByTestId('auth-gate-register-status')).toHaveAttribute('href', '/register');
    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));
    expect(onClose).toHaveBeenCalled();
  });
});
