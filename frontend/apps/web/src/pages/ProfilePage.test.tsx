import type {
  GeocodeResult,
  Profile,
  SmartReturnSettings,
  UserPreference,
  UserStats,
  VehicleProfile,
} from '@parkio/types';
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

const smartReturn: SmartReturnSettings = {
  enabled: false,
  homeLatitude: null,
  homeLongitude: null,
  homeLabel: null,
  defaultReturnTime: '18:30',
  reminderLeadMinutes: 15,
  lastPromptDate: null,
  todayStatus: 'UNKNOWN',
  todayExpectedReturnAt: null,
  todayReturnCheckCompletedAt: null,
  todayNotificationSentAt: null,
};

const emptyVehicle: VehicleProfile = { vehicleType: null, plate: null };
const GEOCODING_URL = `${API_BASE}/geocoding/search`;

function geocodeResult(overrides: Partial<GeocodeResult> = {}): GeocodeResult {
  return {
    id: 'konak-1',
    displayName: 'Konak, İzmir, Ege Bölgesi, Türkiye',
    primary: 'Konak',
    secondary: 'İzmir',
    lat: 38.4187168,
    lng: 27.1282675,
    ...overrides,
  };
}

function useProfileHandlers(vehicle: VehicleProfile = emptyVehicle, smartReturnSettings: SmartReturnSettings = smartReturn) {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/users/me`, () => HttpResponse.json(profile)),
    http.patch(`${API_BASE}/users/me`, () => HttpResponse.json({ ...profile, city: 'Ankara' })),
    http.get(`${API_BASE}/users/me/stats`, () => HttpResponse.json(stats)),
    http.get(`${API_BASE}/users/me/preferences`, () => HttpResponse.json(preferences)),
    http.patch(`${API_BASE}/users/me/preferences`, () => HttpResponse.json(preferences)),
    http.get(`${API_BASE}/users/me/smart-return`, () => HttpResponse.json(smartReturnSettings)),
    http.put(`${API_BASE}/users/me/smart-return/settings`, async ({ request }) => {
      const body = (await request.json()) as Partial<SmartReturnSettings>;
      return HttpResponse.json({ ...smartReturnSettings, ...body });
    }),
    http.post(`${API_BASE}/users/me/smart-return/today/left-by-car`, async ({ request }) => {
      const body = (await request.json()) as { expectedReturnAt: string };
      return HttpResponse.json({
        ...smartReturnSettings,
        enabled: true,
        todayStatus: 'LEFT_BY_CAR',
        todayExpectedReturnAt: body.expectedReturnAt,
      });
    }),
    http.post(`${API_BASE}/users/me/smart-return/today/not-by-car`, () =>
      HttpResponse.json({ ...smartReturnSettings, todayStatus: 'NOT_BY_CAR' }),
    ),
    http.post(`${API_BASE}/users/me/smart-return/today/cancel`, () =>
      HttpResponse.json({ ...smartReturnSettings, todayStatus: 'CANCELLED' }),
    ),
    http.get(`${API_BASE}/users/me/vehicle`, () => HttpResponse.json(vehicle)),
    http.put(`${API_BASE}/users/me/vehicle`, () => HttpResponse.json(vehicle)),
  );
}

const enabledSmartReturn: SmartReturnSettings = {
  ...smartReturn,
  enabled: true,
  homeLatitude: 38.4237,
  homeLongitude: 27.1428,
  homeLabel: 'Konak',
};

function renderProfile(props: { smartReturnEnabled?: boolean; initialEntries?: string[] } = {}) {
  return renderWithProviders(
    <Routes>
      <Route path="/profile" element={<ProfilePage {...props} />} />
      <Route path="/login" element={<div>Login page</div>} />
    </Routes>,
    { initialEntries: props.initialEntries ?? ['/profile'] },
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

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    expect(await screen.findByText('Private by design')).toBeInTheDocument();
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

  it('saves Smart Return settings with a home area and schedules today', async () => {
    useProfileHandlers();
    server.use(
      http.get(GEOCODING_URL, () =>
        HttpResponse.json({
          results: [geocodeResult({ id: 'konak', primary: 'Konak', secondary: 'İzmir', lat: 38.4187, lng: 27.1283 })],
        }),
      ),
    );
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: /Enable Smart Return/ }));
    await user.type(await screen.findByLabelText('Home area'), 'Konak');
    await user.click(await screen.findByRole('button', { name: /Konak/ }));
    await user.click(screen.getByRole('button', { name: 'Turn on Smart Return' }));

    expect(await screen.findByRole('button', { name: 'Yes, driving' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Not by car' })).toBeEnabled();
  });

  it('opens Smart Return directly from the notification deeplink', async () => {
    useProfileHandlers(emptyVehicle, enabledSmartReturn);
    renderProfile({ initialEntries: ['/profile?section=smart-return'] });

    expect(await screen.findByRole('heading', { name: 'Today' })).toBeInTheDocument();
    expect(screen.getByText('Are you driving today?')).toBeInTheDocument();
  });

  it('driving today opens the return time flow and saving updates the current plan', async () => {
    useProfileHandlers(emptyVehicle, enabledSmartReturn);
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: 'Yes, driving' }));
    expect(screen.getByLabelText('Expected return time')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Expected return time'));
    await user.type(screen.getByLabelText('Expected return time'), '23:30');
    await user.click(screen.getByRole('button', { name: "Save today's plan" }));

    expect(await screen.findByText(/Smart Return is active/)).toBeInTheDocument();
  });

  it('not-by-car updates the Smart Return today state', async () => {
    useProfileHandlers(emptyVehicle, enabledSmartReturn);
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: 'Not by car' }));

    expect(await screen.findByText('No Smart Return scheduled today.')).toBeInTheDocument();
  });

  it('cancel today clears the current Smart Return plan', async () => {
    server.use(
      http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/users/me`, () => HttpResponse.json(profile)),
      http.get(`${API_BASE}/users/me/stats`, () => HttpResponse.json(stats)),
      http.get(`${API_BASE}/users/me/preferences`, () => HttpResponse.json(preferences)),
      http.get(`${API_BASE}/users/me/smart-return`, () =>
        HttpResponse.json({
          ...smartReturn,
          enabled: true,
          homeLatitude: 38.4237,
          homeLongitude: 27.1428,
          todayStatus: 'LEFT_BY_CAR',
          todayExpectedReturnAt: '2026-06-28T20:00:00Z',
        }),
      ),
      http.post(`${API_BASE}/users/me/smart-return/today/cancel`, () =>
        HttpResponse.json({
          ...smartReturn,
          enabled: true,
          homeLatitude: 38.4237,
          homeLongitude: 27.1428,
          todayStatus: 'CANCELLED',
          todayExpectedReturnAt: null,
        }),
      ),
      http.get(`${API_BASE}/users/me/vehicle`, () => HttpResponse.json(emptyVehicle)),
    );
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(await screen.findByText('Are you driving today?')).toBeInTheDocument();
    expect(screen.queryByText(/Smart Return is active/)).not.toBeInTheDocument();
  });

  it('selects a Smart Return home area from geocoding suggestions', async () => {
    useProfileHandlers();
    server.use(
      http.get(GEOCODING_URL, () =>
        HttpResponse.json({
          results: [
            geocodeResult({ id: 'konak', primary: 'Konak', secondary: 'İzmir', lat: 38.4187, lng: 27.1283 }),
            geocodeResult({ id: 'alsancak', primary: 'Alsancak', secondary: 'Konak, İzmir', lat: 38.438, lng: 27.141 }),
            geocodeResult({ id: 'karsiyaka', primary: 'Karşıyaka', secondary: 'İzmir', lat: 38.462, lng: 27.114 }),
            geocodeResult({
              id: 'vali-nevzat',
              primary: 'Vali Nevzat Ayaz Lisesi',
              secondary: 'Karşıyaka, İzmir',
              lat: 38.461,
              lng: 27.102,
            }),
          ],
        }),
      ),
    );
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: /Enable Smart Return/ }));
    await user.type(await screen.findByLabelText('Home area'), 'Konak');
    const options = await screen.findAllByRole('button', { name: /Konak/ });
    await user.click(options[0]);

    // No raw coordinates are ever shown — only a friendly saved-area chip.
    expect(await screen.findByText('İzmir')).toBeInTheDocument();
    expect(screen.getByText('Saved')).toBeInTheDocument();
    expect(screen.queryByLabelText('Home latitude')).not.toBeInTheDocument();
  });

  it('shows an empty Smart Return home search state without treating it as an error', async () => {
    useProfileHandlers();
    server.use(http.get(GEOCODING_URL, () => HttpResponse.json({ results: [] })));
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: /Enable Smart Return/ }));
    await user.type(await screen.findByLabelText('Home area'), 'zzqqww');

    expect(await screen.findByText('No places found')).toBeInTheDocument();
    expect(screen.queryByText('Could not load suggestions')).not.toBeInTheDocument();
  });

  it('shows an error only when Smart Return home search fails', async () => {
    useProfileHandlers();
    server.use(http.get(GEOCODING_URL, () => new HttpResponse(null, { status: 500 })));
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));
    await user.click(await screen.findByRole('button', { name: /Enable Smart Return/ }));
    await user.type(await screen.findByLabelText('Home area'), 'Konak');

    expect(await screen.findByText('Could not load suggestions')).toBeInTheDocument();
  });

  it('keeps the Smart Return today prompt usable at 360px width', async () => {
    useProfileHandlers(emptyVehicle, enabledSmartReturn);
    window.innerWidth = 360;
    window.dispatchEvent(new Event('resize'));
    renderProfile();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: 'Smart Return' }));

    expect(await screen.findByText('Are you driving today?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Yes, driving' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not by car' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Yes, driving' }));
    expect(screen.getByLabelText('Expected return time')).toBeInTheDocument();
  });

  it('hides Smart Return when the feature flag is off', async () => {
    useProfileHandlers();
    renderProfile({ smartReturnEnabled: false });

    expect(await screen.findByLabelText('Display name')).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Smart Return' })).not.toBeInTheDocument();
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
