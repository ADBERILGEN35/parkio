import type { Profile, UserPreference, UserStats, VehicleProfile } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
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
    http.get(`${API_BASE}/users/me/vehicle`, () => HttpResponse.json(vehicle)),
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

  it('shows the vehicle empty state when no vehicle is configured', async () => {
    useProfileHandlers();
    renderProfile();

    expect(await screen.findByText(/No vehicle configured yet/)).toBeInTheDocument();
  });

  it('shows a saved confirmation after a successful profile update', async () => {
    useProfileHandlers();
    renderProfile();
    const user = userEvent.setup();

    const cityField = await screen.findByLabelText('City');
    await user.clear(cityField);
    await user.type(cityField, 'Ankara');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText('Saved.')).toBeInTheDocument();
  });

  it('renders a sign out button', async () => {
    useProfileHandlers();
    renderProfile();

    expect(await screen.findByRole('button', { name: /Sign out/ })).toBeInTheDocument();
  });
});
