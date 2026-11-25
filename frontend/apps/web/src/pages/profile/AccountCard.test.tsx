import type { ParkioLocale, Profile } from '@parkio/types';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WebAppRuntime } from '@/app/runtime';
import i18n from '@/i18n';
import * as toast from '@/lib/toast';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { createTestAppRuntime, renderWithProviders, signInAs, withLocale } from '@/test/utils';
import { AccountCard } from './AccountCard';

let runtime: WebAppRuntime;

const profile: Profile = {
  id: '0b8f6c3a-0000-0000-0000-000000000020',
  authUserId: '0b8f6c3a-0000-0000-0000-000000000021',
  email: 'tester@parkio.dev',
  displayName: 'Test Driver',
  phoneNumber: null,
  city: 'Istanbul',
  status: 'ACTIVE',
  createdAt: '2026-01-01T09:00:00Z',
};

function renderAccountCard() {
  return renderWithProviders(
    <Routes>
      <Route path="/profile" element={<AccountCard />} />
      <Route path="/login" element={<div>Login page</div>} />
    </Routes>,
    { runtime, initialEntries: ['/profile'] },
  );
}

/** Labels come from the active locale, so the same steps work in Turkish and English. */
async function submitPasswordChange() {
  const label = (key: string) => i18n.t(`settings:account.${key}`);
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(label('currentPassword')), 'OldStrong123');
  await user.type(screen.getByLabelText(label('newPassword')), 'FreshStrong123');
  await user.type(screen.getByLabelText(label('confirmPassword')), 'FreshStrong123');
  await user.click(screen.getByRole('button', { name: label('changePassword') }));
}

beforeEach(() => {
  runtime = createTestAppRuntime();
  signInAs(runtime, ['USER']);
  vi.spyOn(toast, 'showError').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showSuccess').mockImplementation(() => undefined);
  server.use(http.get(`${API_BASE}/users/me`, () => HttpResponse.json(profile)));
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AccountCard change-password errors', () => {
  // auth-service answers a wrong current password (and a throttled attempt) with 401
  // INVALID_CREDENTIALS. The message must come from the active locale, never the response body.
  it.each<[ParkioLocale, string]>([
    ['en', 'Invalid email or password.'],
    ['tr', 'E-posta veya şifre hatalı.'],
  ])('shows a wrong current password in the active locale (%s)', async (locale, expected) => {
    await withLocale(locale);
    server.use(
      http.post(`${API_BASE}/auth/change-password`, () =>
        HttpResponse.json(apiErrorBody('INVALID_CREDENTIALS', 'Server message.'), { status: 401 }),
      ),
    );
    renderAccountCard();

    await submitPasswordChange();

    expect(await screen.findByText(expected)).toBeInTheDocument();
    expect(toast.showError).toHaveBeenCalledWith(expected);
    expect(screen.queryByText('Server message.')).not.toBeInTheDocument();
    expect(screen.queryByText('Login page')).not.toBeInTheDocument();
  });
});
