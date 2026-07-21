jest.mock('@/services/api', () => ({
  gamificationApi: {
    getLeaderboard: jest.fn(),
    getMyProgress: jest.fn(),
    getMyLevel: jest.fn(),
  },
}));

jest.mock('@/state/authStore', () => ({
  useAuthStore: (selector: (state: { user: { id: string } | null }) => unknown) =>
    selector({ user: { id: 'me' } }),
}));

import { fireEvent, waitFor } from '@testing-library/react-native';
import type { LeaderboardEntry } from '@parkio/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useShareSheetStore } from '@/features/share/shareSheetStore';
import { gamificationApi } from '@/services/api';
import LeaderboardScreen from '../../../../app/(main)/(tabs)/leaderboard';

const getLeaderboard = gamificationApi.getLeaderboard as jest.Mock;
const getMyProgress = gamificationApi.getMyProgress as jest.Mock;
const getMyLevel = gamificationApi.getMyLevel as jest.Mock;

function entry(
  partial: Partial<LeaderboardEntry> & Pick<LeaderboardEntry, 'userId' | 'rank'>,
): LeaderboardEntry {
  return { totalPoints: 10, currentLevel: 1, ...partial };
}

describe('LeaderboardScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useShareSheetStore.setState({ visible: false });
    getMyProgress.mockResolvedValue({
      userId: 'me',
      totalPoints: 30,
      currentLevel: 1,
      updatedAt: new Date().toISOString(),
    });
    getMyLevel.mockResolvedValue({
      userId: 'me',
      currentLevel: 1,
      totalPoints: 30,
      currentLevelMinPoints: 0,
      nextLevelMinPoints: 50,
      pointsToNextLevel: 20,
    });
  });

  it('shows an error state when the leaderboard API fails', async () => {
    getLeaderboard.mockRejectedValue(new Error('boom'));
    const { findByText } = renderWithProviders(<LeaderboardScreen />);
    expect(await findByText(/Siralama|Sıralama/)).toBeTruthy();
  });

  it('renders the empty board with how-to-earn and share CTA', async () => {
    getLeaderboard.mockResolvedValue([]);
    const { findByText } = renderWithProviders(<LeaderboardScreen />);
    expect(await findByText(/Henüz sıralama yok|Henuz/)).toBeTruthy();
    expect(await findByText(/Nasıl puan kazanılır|Nasil/)).toBeTruthy();
    fireEvent.press(await findByText('Yer paylaş'));
    await waitFor(() => expect(useShareSheetStore.getState().visible).toBe(true));
  });

  it('renders a solo self state for one entry', async () => {
    getLeaderboard.mockResolvedValue([entry({ userId: 'me', rank: 1, totalPoints: 30 })]);
    const { findByText } = renderWithProviders(<LeaderboardScreen />);
    expect(await findByText(/İlk sıradasın|Ilk/)).toBeTruthy();
    expect(await findByText('Senin durumun')).toBeTruthy();
  });

  it('renders podium content for three or more entries', async () => {
    getLeaderboard.mockResolvedValue([
      entry({ userId: 'a', rank: 1, totalPoints: 90 }),
      entry({ userId: 'b', rank: 2, totalPoints: 40 }),
      entry({ userId: 'me', rank: 3, totalPoints: 30 }),
      entry({ userId: 'd', rank: 4, totalPoints: 5 }),
    ]);
    const { findByText, findAllByText } = renderWithProviders(<LeaderboardScreen />);
    expect(await findByText('Liderlik')).toBeTruthy();
    expect(await findByText('Senin durumun')).toBeTruthy();
    expect((await findAllByText('+5')).length).toBeGreaterThanOrEqual(2);
    expect(await findByText('+20')).toBeTruthy();
  });
});
