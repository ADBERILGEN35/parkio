import { Stack } from 'expo-router';
import { MapBenchScreen } from '@/features/map/bench/MapBenchScreen';

/**
 * DEV-ONLY map performance benchmark route (`/map-bench`). Not linked from any
 * user flow — open it manually to measure pan jank against marker density,
 * clustering, the banner and the sheet. See the map feature README for method.
 */
export default function MapBenchRoute() {
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Map benchmark (dev)' }} />
      <MapBenchScreen />
    </>
  );
}
