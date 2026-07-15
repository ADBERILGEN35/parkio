import { Redirect } from 'expo-router';

/**
 * Legacy Home tab kept for deep links / push allowlist migration.
 * Product home is Map — redirect without rendering the dashboard.
 */
export default function HomeScreen() {
  return <Redirect href="/(main)/(tabs)/map" />;
}
