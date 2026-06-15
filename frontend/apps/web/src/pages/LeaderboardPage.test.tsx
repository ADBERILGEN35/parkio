import type { GamificationProgress, LeaderboardEntry, PublicProfile } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { LeaderboardPage } from './LeaderboardPage';

const MY_USER_ID = 'aaaaaaaa-0000-0000-0000-000000000002';

const entries: LeaderboardEntry[] = [
  { rank: 1, userId: '11111111-0000-0000-0000-000000000001', totalPoints: 500, currentLevel: 5 },
  { rank: 2, userId: MY_USER_ID, totalPoints: 300, currentLevel: 3 },
  { rank: 3, userId: '33333333-0000-0000-0000-000000000003', totalPoints: 200, currentLevel: 2 },
  { rank: 4, userId: '44444444-0000-0000-0000-000000000004', totalPoints: 100, currentLevel: 1 },
];

const progress: GamificationProgress = {
  userId: MY_USER_ID,
  totalPoints: 300,
  currentLevel: 3,
  updatedAt: '2026-06-11T09:00:00Z',
};

/** Display names per user id; userId absent here ⇒ profile fetch 404s. */
const profileNames: Record<string, string> = {
  '11111111-0000-0000-0000-000000000001': 'Ada Lovelace',
  [MY_USER_ID]: 'Test Driver',
  '33333333-0000-0000-0000-000000000003': 'Grace Hopper',
};

function publicProfile(userId: string, displayName: string): PublicProfile {
  return {
    userId,
    displayName,
    city: 'İzmir',
    trustBand: 'TRUSTED',
    currentLevel: 3,
    status: 'ACTIVE',
    memberSince: '2026-01-01T00:00:00Z',
  };
}

interface HandlerOptions {
  rows?: LeaderboardEntry[];
  /** Override the leaderboard rows returned for a specific requested limit. */
  rowsByLimit?: Record<string, LeaderboardEntry[]>;
  myProgress?: GamificationProgress | null;
  /** User ids whose profile fetch should fail (simulating a 404/500). */
  failingProfiles?: string[];
  onLeaderboardRequest?: (limit: string | null) => void;
}

function useLeaderboardHandlers(options: HandlerOptions = {}) {
  const {
    rows = entries,
    rowsByLimit,
    myProgress = progress,
    failingProfiles = [],
    onLeaderboardRequest,
  } = options;

  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/gamification/me/progress`, () =>
      myProgress ? HttpResponse.json(myProgress) : new HttpResponse(null, { status: 404 }),
    ),
    http.get(`${API_BASE}/gamification/leaderboard`, ({ request }) => {
      const limit = new URL(request.url).searchParams.get('limit');
      onLeaderboardRequest?.(limit);
      const resolved = (limit && rowsByLimit?.[limit]) || rows;
      return HttpResponse.json(resolved);
    }),
    http.get(`${API_BASE}/users/:userId/public-profile`, ({ params }) => {
      const userId = String(params.userId);
      if (failingProfiles.includes(userId)) {
        return new HttpResponse(null, { status: 404 });
      }
      const name = profileNames[userId];
      if (!name) return new HttpResponse(null, { status: 404 });
      return HttpResponse.json(publicProfile(userId, name));
    }),
  );
}

describe('LeaderboardPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('renders the top-3 podium', async () => {
    useLeaderboardHandlers();
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    const podium = await screen.findByRole('region', { name: 'Top three contributors' });
    // All three podium contributors are present (names resolved from public profiles).
    expect(within(podium).getByText('Ada Lovelace')).toBeInTheDocument();
    expect(within(podium).getByText('Grace Hopper')).toBeInTheDocument();
    expect(within(podium).getByText('500')).toBeInTheDocument();
  });

  it('uses the public-profile display name when available', async () => {
    useLeaderboardHandlers();
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    // Trust band from the public profile surfaces in the table row (rank 4).
    expect(screen.getByText('100 pts')).toBeInTheDocument();
  });

  it('falls back to a shortened user id when the profile fetch fails', async () => {
    useLeaderboardHandlers({ failingProfiles: ['11111111-0000-0000-0000-000000000001'] });
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    // Rank 1's profile 404s, so the podium shows the shortened id instead of a name.
    expect(await screen.findByText('11111111…')).toBeInTheDocument();
  });

  it('highlights the current user with a "Your standing" card', async () => {
    useLeaderboardHandlers();
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText('Your standing')).toBeInTheDocument();
    expect(screen.getByText('#2')).toBeInTheDocument();
  });

  it('shows an honest "not in Top N" message when the caller is absent', async () => {
    useLeaderboardHandlers({
      rows: entries.filter((entry) => entry.userId !== MY_USER_ID),
    });
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText(/You are not in the current Top N yet/)).toBeInTheDocument();
  });

  it('requests a larger limit when "Show more" is clicked', async () => {
    const requestedLimits: (string | null)[] = [];
    const extended = [
      ...entries,
      { rank: 5, userId: '55555555-0000-0000-0000-000000000005', totalPoints: 50, currentLevel: 1 },
    ];
    useLeaderboardHandlers({
      rowsByLimit: { '10': entries, '20': extended },
      onLeaderboardRequest: (limit) => requestedLimits.push(limit),
    });
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    // Initial load requests the first step (top 10).
    expect(await screen.findByText('Showing top 10')).toBeInTheDocument();
    expect(requestedLimits).toContain('10');

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Show more' }));

    // Re-queries with the next step (top 20) and shows the additional row.
    expect(await screen.findByText('Showing top 20')).toBeInTheDocument();
    expect(requestedLimits).toContain('20');
    expect(screen.getByText('50 pts')).toBeInTheDocument();
  });

  it('shows the empty state when there are no ranked users', async () => {
    useLeaderboardHandlers({ rows: [] });
    renderWithProviders(<LeaderboardPage />, { initialEntries: ['/leaderboard'] });

    expect(await screen.findByText('No ranked contributors yet')).toBeInTheDocument();
  });
});
