import { Redirect } from 'expo-router';

/** Tab chrome for Leaderboard — reuse the richer stack screen. */
export default function LeaderboardTab() {
  return <Redirect href="/(main)/leaderboard" />;
}