import type { Profile, UserPreference, UserStats, VehicleProfile } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { ProfilePage } from './ProfilePage';

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

const stats: UserStats = {
  trustScore: 72,
  trustBand: 'TRUSTED',
  totalPoints: 340,
  currentLevel: 3,
};

const preferences: UserPreference = {
  preferredRadiusMeters: 1500,
  notificationsEnabled: true,
};

const emptyVehicle: VehicleProfile = { vehicleType: null, plate: null };

function useProfileHandlers(vehicle: VehicleProfile = emptyVehicle) {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/users/me`, () => HttpResponse.json(profile)),
    http.patch(`${API_BASE}/users/me`, () => HttpResponse.json({ ...profile, city: 'Ankara' })),
    http.get(`${API_BASE}/users/me/stats`, () => HttpResponse.json(stats)),
    http.get(`${API_BASE}/users/me/preferences`, () => HttpResponse.json(preferences)),
    http.patch(`${API_BASE}/users/me/preferences`, () => HttpResponse.json(preferences)),
    http.get(`${API_BASE}/users/me/vehicle`, () => HttpResponse.json(vehicle)),
    http.put(`${API_BASE}/users/me/vehicle`, () => HttpResponse.json(vehicle)),
  );
}

function renderProfile() {
  return renderWithProviders(
    <Routes>
      <Route path="/profile" element={<ProfilePage />} />
      <Route path="/login" element={<div>Login page</div>} />
    </Routes>,
    { initialEntries: ['/profile'] },
  );
}

describe('ProfilePage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('renders the impact stats from the backend', async () => {
    useProfileHandlers();
    renderProfile();

    expect(await screen.findByText('340')).toBeInTheDocument(); // total points
    expect(screen.getByText('72')).toBeInTheDocument(); // trust score
    expect(screen.getByText('Trusted')).toBeInTheDocument(); // trust band
  });

  it('shows the Profile & Account section by default', async () => {
    useProfileHandlers();
    renderProfile();

    expect(await screen.findByLabelText('Display name')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Sign out/ })).toBeInTheDocument();
  });

  it('switches visible sections when a section tab is selected', async () => {
    useProfileHandlers();
    renderProfile();
    const user = userEvent.setup();

    // Account section is active by default.
    expect(await screen.findByLabelText('Display name')).toBeInTheDocument();

    // Switch to Notifications — the radius control appears, the profile form goes away.
    await user.click(screen.getByRole('tab', { name: 'Notifications' }));
    expect(await screen.findByText('Preferred search radius')).toBeInTheDocument();
    expect(screen.queryByLabelText('Display name')).not.toBeInTheDocument();

    // Switch to Trust & Progress — the honest "no streaks" note appears.
    await user.click(screen.getByRole('tab', { name: 'Trust & Progress' }));
    expect(
      await screen.findByText(/Streaks, achievements and activity heatmaps aren't available yet/),
    ).toBeInTheDocument();
  });

  it('shows the vehicle empty state when no vehicle is configured', async () => {
    useProfileHandlers();
    renderProfile();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('tab', { name: 'Vehicle' }));

    expect(await screen.findByText(/No vehicle configured yet/)).toBeInTheDocument();
  });

  it('shows the current vehicle when one is configured', async () => {
    useProfileHandlers({ vehicleType: 'SUV', plate: '34ABC123' });
    renderProfile();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('tab', { name: 'Vehicle' }));

    expect(await screen.findByText('34ABC123')).toBeInTheDocument();
  });

  it('shows a saved confirmation after a successful profile update', async () => {
    useProfileHandlers();
    renderProfile();
    const user = userEvent.setup();

    expect(await screen.findByText('Istanbul')).toBeInTheDocument();
    const cityField = await screen.findByLabelText('City');
    await user.clear(cityField);
    await user.type(cityField, 'Ankara');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(screen.getByText('Ankara')).toBeInTheDocument();
  });

  it('shows a saved confirmation after saving preferences', async () => {
    useProfileHandlers();
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Notifications' }));
    await user.click(await screen.findByRole('button', { name: 'Save preferences' }));

    expect(await screen.findByText('Saved.')).toBeInTheDocument();
  });

  it('renders a sign out button', async () => {
    useProfileHandlers();
    renderProfile();

    expect(await screen.findByRole('button', { name: /Sign out/ })).toBeInTheDocument();
  });

  it('logs out all devices after confirmation', async () => {
    useProfileHandlers();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    let called = false;
    server.use(
      http.post(`${API_BASE}/auth/logout-all`, () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderProfile();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Log out of all devices/ }));

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(called).toBe(true);
    confirm.mockRestore();
  });

  it('changes password and redirects to login', async () => {
    useProfileHandlers();
    let body: { currentPassword: string; newPassword: string } | null = null;
    server.use(
      http.post(`${API_BASE}/auth/change-password`, async ({ request }) => {
        body = (await request.json()) as { currentPassword: string; newPassword: string };
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderProfile();
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText('Current password'), 'OldStrong123');
    await user.type(screen.getByLabelText('New password'), 'FreshStrong123');
    await user.type(screen.getByLabelText('Confirm new password'), 'FreshStrong123');
    await user.click(screen.getByRole('button', { name: /Change password/ }));

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(body).toEqual({ currentPassword: 'OldStrong123', newPassword: 'FreshStrong123' });
  });
});
