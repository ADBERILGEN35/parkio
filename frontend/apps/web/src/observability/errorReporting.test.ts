import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/auth/store';

async function loadReporting() {
  vi.resetModules();
  return import('./errorReporting');
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  useAuthStore.getState().clearSession();
});

describe('frontend error reporting', () => {
  it('no-ops when disabled', async () => {
    vi.stubEnv('VITE_FRONTEND_ERROR_REPORTING', 'disabled');
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { reportFrontendError } = await loadReporting();

    reportFrontendError(new Error('boom'), { source: 'test' });

    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('redacts sensitive query tokens from reported route context', async () => {
    vi.stubEnv('VITE_FRONTEND_ERROR_REPORTING', 'console');
    window.history.pushState(
      null,
      '',
      '/reset-password?resetToken=abc123&verification_token=verify-me&ok=yes',
    );
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { reportFrontendError } = await loadReporting();

    reportFrontendError(new Error('boom'), {
      accessToken: 'secret-access',
      formValues: { email: 'tester@parkio.dev' },
    });

    const context = errorSpy.mock.calls[0]?.[3] as {
      route: string;
      accessToken: string;
      formValues: string;
    };
    expect(context.route).toContain('resetToken=%5BREDACTED%5D');
    expect(context.route).toContain('verification_token=%5BREDACTED%5D');
    expect(context.route).toContain('ok=yes');
    expect(context.accessToken).toBe('[REDACTED]');
    expect(context.formValues).toBe('[REDACTED]');
  });
});
