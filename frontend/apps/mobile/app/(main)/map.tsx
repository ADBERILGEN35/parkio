import { Stack } from 'expo-router';
import { MapScreen } from '@/features/map/presentation/MapScreen';

/** Map & Discovery route. The screen owns its own chrome (search, FABs, sheet). */
export default function MapRoute() {
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Find parking' }} />
      <MapScreen />
    </>
  );
}
