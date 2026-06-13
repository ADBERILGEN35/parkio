import type { GamificationProgress, LeaderboardEntry } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { LeaderboardPage } from './LeaderboardPage';

const MY_USER_ID = 'aaaaaaaa-0000-0000-0000-000000000002';

const entries: LeaderboardEntry[] = [
  { rank: 1, userId: '0b8f6c3a-0000-0000-0000-000000000001', totalPoints: 500, currentLevel: 5 },
  { rank: 2, userId: MY_USER_ID, totalPoints: 300, currentLevel: 3 },
];

const progress: GamificationProgress = {
  userId: MY_USER_ID,
  totalPoints: 300,
  currentLevel: 3,
  updatedAt: '2026-06-11T09:00:00Z',
};

function useLeaderboardHandlers(rows: LeaderboardEntry[] = entries) {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/gamification/me/progress`, () => HttpResponse.json(progress)),
    http.get(`${API_BASE}/gamification/leaderboard`, () => HttpResponse.json(rows)),
  );
}

describe('LeaderboardPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('renders ranked rows with shortened user ids', async () => {
    useLeaderboardHandlers();
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText('0b8f6c3a…')).toBeInTheDocument();
    expect(screen.getByText('500 pts')).toBeInTheDocument();
  });

  it("surfaces the current user's rank when present", async () => {
    useLeaderboardHandlers();
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText(/Your rank:/)).toBeInTheDocument();
  });

  it('shows the empty state when there are no ranked users', async () => {
    useLeaderboardHandlers([]);
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText('No ranked users yet')).toBeInTheDocument();
  });
});
