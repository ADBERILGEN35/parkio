import type {
  GamificationAccessPolicy,
  LevelRule,
  LevelStanding,
  PointsSummary,
} from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { GamificationPage } from './GamificationPage';

const level: LevelStanding = {
  userId: 'aaaaaaaa-0000-0000-0000-000000000002',
  currentLevel: 3,
  totalPoints: 340,
  currentLevelMinPoints: 300,
  nextLevelMinPoints: 600,
  pointsToNextLevel: 260,
};

const points: PointsSummary = {
  userId: 'aaaaaaaa-0000-0000-0000-000000000002',
  totalPoints: 340,
  recentTransactions: [
    {
      sourceType: 'PARKING_VERIFIED',
      direction: 'EARNED',
      points: 20,
      relatedSpotId: null,
      createdAt: '2026-06-11T09:00:00Z',
    },
  ],
};

const accessPolicy: GamificationAccessPolicy = {
  userId: 'aaaaaaaa-0000-0000-0000-000000000002',
  currentLevel: 3,
  searchRadiusMeters: 2000,
  resultLimit: 15,
  dailyViewLimit: 100,
  verifiedSpotPriority: true,
  notificationPriority: false,
};

const levels: LevelRule[] = [
  {
    level: 3,
    minPoints: 300,
    maxPoints: 599,
    searchRadiusMeters: 2000,
    resultLimit: 15,
    dailyViewLimit: 100,
    verifiedSpotPriority: true,
    notificationPriority: false,
  },
];

function useGamificationHandlers() {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/gamification/me/level`, () => HttpResponse.json(level)),
    http.get(`${API_BASE}/gamification/me/points`, () => HttpResponse.json(points)),
    http.get(`${API_BASE}/gamification/me/access-policy`, () => HttpResponse.json(accessPolicy)),
    http.get(`${API_BASE}/gamification/levels`, () => HttpResponse.json(levels)),
  );
}

describe('GamificationPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('renders the level hero with current level and points-to-next', async () => {
    useGamificationHandlers();
    renderWithProviders(<GamificationPage />, { initialEntries: ['/gamification'] });

    expect(await screen.findByRole('heading', { name: 'Level 3' })).toBeInTheDocument();
    expect(screen.getByText(/340 \/ 600 points/)).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('renders points history and access policy', async () => {
    useGamificationHandlers();
    renderWithProviders(<GamificationPage />, { initialEntries: ['/gamification'] });

    expect(await screen.findByText('Parking verified')).toBeInTheDocument();
    expect(screen.getByText('2000 m')).toBeInTheDocument();
  });
});
