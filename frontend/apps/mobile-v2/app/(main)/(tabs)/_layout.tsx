import { useCallback, useState } from 'react';
import { Tabs, useRouter } from 'expo-router';
import { AppTabBar } from '@/components/navigation/AppTabBar';
import { ShareSourceSheet, type ShareSource } from '@/features/share/ShareSourceSheet';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

export default function TabsLayout() {
  const router = useRouter();
  const [shareSheetVisible, setShareSheetVisible] = useState(false);

  const openShare = useCallback((source: ShareSource) => {
    setShareSheetVisible(false);
    // A fresh pick replaces any stale draft photo state.
    useShareDraftStore.getState().dismissResume();
    router.push({ pathname: '/(main)/share', params: { source } });
  }, [router]);

  const resumeShare = useCallback(() => {
    setShareSheetVisible(false);
    useShareDraftStore.getState().dismissResume();
    router.push('/(main)/share');
  }, [router]);

  return (
    <>
      <Tabs
        screenOptions={{ headerShown: false }}
        tabBar={(props) => (
          <AppTabBar {...props} onSharePress={() => setShareSheetVisible(true)} />
        )}
      >
        <Tabs.Screen name="map" />
        <Tabs.Screen name="my-spots" />
        <Tabs.Screen name="leaderboard" />
        <Tabs.Screen name="profile" />
      </Tabs>
      <ShareSourceSheet
        visible={shareSheetVisible}
        onClose={() => setShareSheetVisible(false)}
        onPick={openShare}
        onResume={resumeShare}
      />
    </>
  );
}
