import { Icon } from '@parkio/ui';
import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/map/NearbySpotsMap', () => ({ NearbySpotsMap: () => null }));
import type { WebAppRuntime } from '@/app/runtime';
import {
  createTestAppRuntime,
  renderWithProviders as renderWithBaseProviders,
  signInAs,
  withLocale,
} from '@/test/utils';
import { GamificationPage } from '@/pages/GamificationPage';
import { LeaderboardPage } from '@/pages/LeaderboardPage';
import { ProfilePage } from '@/pages/ProfilePage';
import { http, HttpResponse } from 'msw';
import { API_BASE, server } from '@/test/server';

let runtime: WebAppRuntime;

function renderWithProviders(
  ui: Parameters<typeof renderWithBaseProviders>[0],
  options: Parameters<typeof renderWithBaseProviders>[1] = {},
) {
  return renderWithBaseProviders(ui, { ...options, runtime });
}

const TOKEN_LEAK_RE = /(?:\+)?__[A-Z0-9]+(?:__[A-Z0-9]+)*__/;

const EN_IMPACT_MARKERS = [
  'Your Impact',
  'Current level',
  'Total points',
  'Recent activity',
  'Your current benefits',
  'Level roadmap',
  'View spot',
];

function stubAuthenticatedSurfaces() {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/users/me/vehicle`, () =>
      HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
    ),
    http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/geocoding/search`, () => HttpResponse.json({ results: [] })),
    http.get(`${API_BASE}/gamification/me/level`, () =>
      HttpResponse.json({
        userId: 'aaaaaaaa-0000-0000-0000-000000000002',
        currentLevel: 1,
        totalPoints: 10,
        currentLevelMinPoints: 0,
        nextLevelMinPoints: 100,
        pointsToNextLevel: 90,
      }),
    ),
    http.get(`${API_BASE}/gamification/me/points`, () =>
      HttpResponse.json({
        userId: 'aaaaaaaa-0000-0000-0000-000000000002',
        totalPoints: 10,
        recentTransactions: [],
      }),
    ),
    http.get(`${API_BASE}/gamification/me/access-policy`, () =>
      HttpResponse.json({
        userId: 'aaaaaaaa-0000-0000-0000-000000000002',
        currentLevel: 1,
        searchRadiusMeters: 1000,
        resultLimit: 10,
        dailyViewLimit: 50,
        verifiedSpotPriority: false,
        notificationPriority: false,
      }),
    ),
    http.get(`${API_BASE}/gamification/levels`, () =>
      HttpResponse.json([
        {
          level: 1,
          minPoints: 0,
          maxPoints: 99,
          searchRadiusMeters: 1000,
          resultLimit: 10,
          dailyViewLimit: 50,
          verifiedSpotPriority: false,
          notificationPriority: false,
        },
      ]),
    ),
    http.get(`${API_BASE}/gamification/leaderboard`, () =>
      HttpResponse.json([
        {
          rank: 1,
          userId: 'aaaaaaaa-0000-0000-0000-000000000002',
          totalPoints: 10,
          currentLevel: 1,
        },
      ]),
    ),
    http.get(`${API_BASE}/gamification/me/progress`, () =>
      HttpResponse.json({
        userId: 'aaaaaaaa-0000-0000-0000-000000000002',
        totalPoints: 10,
        currentLevel: 1,
        updatedAt: '2026-06-11T09:00:00Z',
      }),
    ),
    http.get(`${API_BASE}/users/:userId/public-profile`, () =>
      HttpResponse.json({
        userId: 'aaaaaaaa-0000-0000-0000-000000000002',
        displayName: 'Test Driver',
        city: 'İzmir',
        trustBand: 'TRUSTED',
        currentLevel: 1,
        status: 'ACTIVE',
        memberSince: '2026-01-01T00:00:00Z',
      }),
    ),
    http.get(`${API_BASE}/users/me`, () =>
      HttpResponse.json({
        id: '0b8f6c3a-0000-0000-0000-000000000020',
        authUserId: 'aaaaaaaa-0000-0000-0000-000000000002',
        email: 'tester@parkio.dev',
        displayName: 'Test Driver',
        phoneNumber: null,
        city: 'Istanbul',
        status: 'ACTIVE',
        createdAt: '2026-01-01T09:00:00Z',
      }),
    ),
    http.get(`${API_BASE}/users/me/stats`, () =>
      HttpResponse.json({
        trustScore: 72,
        trustBand: 'HIGH_TRUST',
        totalPoints: 10,
        currentLevel: 1,
      }),
    ),
    http.get(`${API_BASE}/users/me/preferences`, () =>
      HttpResponse.json({
        preferredRadiusMeters: 1500,
        notificationsEnabled: true,
        preferredLocale: 'tr',
      }),
    ),
  );
}

describe('locale surface integrity', () => {
  beforeEach(() => {
    if (!(URL).createObjectURL) {
      (URL).createObjectURL = () => 'blob:mock';
      (URL).revokeObjectURL = () => undefined;
    }
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    stubAuthenticatedSurfaces();
  });

  afterEach(async () => {
    await withLocale('en');
  });

  it('Upload titles localize and Icon ligatures stay lowercase under uppercase', async () => {
    const { default: i18n } = await import('@/i18n');
    await withLocale('tr');
    expect(i18n.t('media:upload.title')).toMatch(/Park yeri paylaş/i);
    await withLocale('en');
    expect(i18n.t('media:upload.title')).toMatch(/Share a spot/i);
    const { container } = renderWithProviders(
      <p className="uppercase tracking-wider">
        <Icon name="add_location_alt" data-testid="upload-icon" />
        {i18n.t('media:upload.title')}
      </p>,
    );
    expect(container.querySelector('[data-testid="upload-icon"]')?.textContent).toBe('add_location_alt');
    expect(TOKEN_LEAK_RE.test(container.textContent ?? '')).toBe(false);
    expect(container.textContent).not.toMatch(/ADD_LOCATION_ALT/);
  });

  it('Leaderboard Turkish standing uses icon ligature, not PIN token', async () => {
    await withLocale('tr');
    const { container } = renderWithProviders(<LeaderboardPage />, {
      initialEntries: ['/leaderboard'],
    });
    expect(await screen.findByText(/Sizin durumunuz/i)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/PERSON_PIN_CIRCLE|PIN__CIRCLE/);
    expect(TOKEN_LEAK_RE.test(container.textContent ?? '')).toBe(false);
    const icons = [...container.querySelectorAll('.material-symbols-outlined')].map(
      (el) => el.textContent,
    );
    expect(icons).toContain('person_pin_circle');
  });

  it('Profile Turkish eyebrow is Ayarlar and SETTINGS text is absent', async () => {
    await withLocale('tr');
    const { container } = renderWithProviders(<ProfilePage />, { initialEntries: ['/profile'] });
    expect(await screen.findByText('Ayarlar')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: /Hesap ve tercihler/i })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\bSETTINGS\b/);
    expect(TOKEN_LEAK_RE.test(container.textContent ?? '')).toBe(false);
  });

  it('Impact Turkish is fully localized vs known English chrome', async () => {
    await withLocale('tr');
    const { container } = renderWithProviders(<GamificationPage />, {
      initialEntries: ['/gamification'],
    });
    expect(await screen.findByRole('heading', { name: 'Katkılarınız' })).toBeInTheDocument();
    expect((await screen.findAllByText('Mevcut seviye')).length).toBeGreaterThan(0);
    expect(await screen.findByText('Son etkinlikler')).toBeInTheDocument();
    expect(await screen.findByText('Mevcut avantajlarınız')).toBeInTheDocument();
    expect(await screen.findByText('Seviye yol haritası')).toBeInTheDocument();
    for (const marker of EN_IMPACT_MARKERS) {
      expect(container.textContent).not.toContain(marker);
    }
  });

  it('Impact English does not leak Turkish chrome', async () => {
    await withLocale('en');
    const { container } = renderWithProviders(<GamificationPage />, {
      initialEntries: ['/gamification'],
    });
    expect(await screen.findByRole('heading', { name: 'Your Impact' })).toBeInTheDocument();
    expect(container.textContent).not.toContain('Katkılarınız');
    expect(container.textContent).not.toContain('Seviye yol haritası');
  });

  it('shared Icon under uppercase never exposes ligature as UI copy', () => {
    const { container } = renderWithProviders(
      <p className="uppercase">
        <Icon name="settings" />
        Ayarlar
      </p>,
    );
    expect(container.querySelector('.material-symbols-outlined')?.textContent).toBe('settings');
    expect(container.textContent).not.toMatch(/\bSETTINGS\b/);
  });
});
