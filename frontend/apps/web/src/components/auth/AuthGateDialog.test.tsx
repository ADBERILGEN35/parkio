import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
import { AuthGateDialog } from './AuthGateDialog';

vi.mock('@/config/env', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/config/env')>();
  return {
    ...actual,
    frontendConfig: {
      ...actual.frontendConfig,
      registrationModeBootstrap: 'CLOSED',
    },
  };
});

describe('AuthGateDialog', () => {
  it('offers login with return path and truthful CLOSED registration CTA', async () => {
    server.use(
      http.get(`${API_BASE}/auth/registration-mode`, () =>
        HttpResponse.json({ mode: 'CLOSED' }),
      ),
    );
    const onClose = vi.fn();
    renderWithProviders(
      <AuthGateDialog
        open
        onClose={onClose}
        returnPath="/facilities/00000000-0000-0000-0000-000000000901"
        intent="facilityDetail"
      />,
      { initialEntries: ['/explore'] },
    );

    expect(screen.getByTestId('auth-gate-dialog')).toHaveTextContent('Discover more with Parkio');
    expect(screen.getByTestId('auth-gate-login')).toHaveAttribute(
      'href',
      '/login?return=%2Ffacilities%2F00000000-0000-0000-0000-000000000901',
    );
    expect(await screen.findByTestId('auth-gate-register')).toHaveAttribute('href', '/register');
    await waitFor(() => {
      expect(screen.getByTestId('auth-gate-register')).toHaveTextContent('About registration');
    });
    expect(screen.queryByText(/^Sign up$/)).not.toBeInTheDocument();
    expect(screen.getByTestId('auth-gate-registration-support')).toHaveTextContent(
      'New account registration will open soon.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('shows contribution AuthGate copy without implying open registration', async () => {
    server.use(
      http.get(`${API_BASE}/auth/registration-mode`, () =>
        HttpResponse.json({ mode: 'CLOSED' }),
      ),
    );
    renderWithProviders(
      <AuthGateDialog open onClose={() => undefined} returnPath="/upload" intent="contribute" />,
      { initialEntries: ['/explore'] },
    );

    expect(screen.getByTestId('auth-gate-dialog')).toHaveAttribute(
      'data-auth-gate-intent',
      'contribute',
    );
    expect(screen.getByTestId('auth-gate-dialog')).toHaveTextContent('Report a parking spot');
    expect(screen.getByTestId('auth-gate-dialog')).toHaveTextContent(
      'Sign in to add a new parking spot for the community.',
    );
    expect(screen.getByTestId('auth-gate-login')).toHaveAttribute('href', '/login?return=%2Fupload');
    expect(screen.queryByText(/^Sign up$/)).not.toBeInTheDocument();
    expect(await screen.findByTestId('auth-gate-register')).toHaveTextContent('About registration');
  });

  it('uses Sign up secondary CTA when registration is OPEN', async () => {
    server.use(
      http.get(`${API_BASE}/auth/registration-mode`, () =>
        HttpResponse.json({ mode: 'OPEN' }),
      ),
    );
    renderWithProviders(
      <AuthGateDialog open onClose={() => undefined} intent="community" />,
      { initialEntries: ['/explore'] },
    );

    await waitFor(() => {
      expect(screen.getByTestId('auth-gate-register')).toHaveTextContent('Sign up');
    });
    expect(screen.queryByTestId('auth-gate-registration-support')).not.toBeInTheDocument();
  });
});
