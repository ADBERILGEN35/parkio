import { useCallback } from 'react';
import { Tabs, usePathname, useRouter } from 'expo-router';
import { AppTabBar } from '@/components/navigation/AppTabBar';
import { ShareSourceSheet, type ShareSource } from '@/features/share/ShareSourceSheet';
import { useShareSheetStore } from '@/features/share/shareSheetStore';
import {
  useShareSessionStore,
  type ShareReturnHref,
} from '@/features/share/shareSessionStore';
import { draftPhotoExists } from '@/features/share/prepareImage';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { useT } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';

function returnHrefFromPath(pathname: string): ShareReturnHref | undefined {
  if (pathname.includes('leaderboard')) return '/(main)/(tabs)/leaderboard';
  if (pathname.includes('my-spots')) return '/(main)/(tabs)/my-spots';
  if (pathname.includes('profile')) return '/(main)/(tabs)/profile';
  if (pathname.includes('map')) return '/(main)/(tabs)/map';
  return undefined;
}

export default function TabsLayout() {
  const router = useRouter();
  const pathname = usePathname();
  const toast = useToast();
  const t = useT();
  const shareSheetVisible = useShareSheetStore((s) => s.visible);
  const openSheet = useShareSheetStore((s) => s.open);
  const closeSheet = useShareSheetStore((s) => s.close);
  const beginSession = useShareSessionStore((s) => s.begin);

  const openShare = useCallback(
    (source: ShareSource) => {
      console.info(`[ShareSheet] onPick source=${source} -> navigate share wizard`);
      const entry = useShareSheetStore.getState().entry;
      beginSession(entry, returnHrefFromPath(pathname));
      closeSheet();
      useShareDraftStore.getState().dismissResume();
      router.push({ pathname: '/(main)/share', params: { source } });
    },
    [beginSession, closeSheet, pathname, router],
  );

  const resumeShare = useCallback(() => {
    const draft = useShareDraftStore.getState();
    if (draft.photo && !draftPhotoExists(draft.photo.uri)) {
      draft.clearPhoto();
      draft.setStep('photo');
      toast.show(t('share.draft.photoMissing'), 'error');
    }
    const entry = useShareSheetStore.getState().entry;
    beginSession(entry, returnHrefFromPath(pathname));
    closeSheet();
    draft.dismissResume();
    router.push('/(main)/share');
  }, [beginSession, closeSheet, pathname, router, t, toast]);

  return (
    <>
      <Tabs
        screenOptions={{ headerShown: false }}
        tabBar={(props) => (
          <AppTabBar {...props} onSharePress={() => openSheet('tab-bar')} />
        )}
      >
        <Tabs.Screen name="map" />
        <Tabs.Screen name="my-spots" />
        <Tabs.Screen name="leaderboard" />
        <Tabs.Screen name="profile" />
      </Tabs>
      <ShareSourceSheet
        visible={shareSheetVisible}
        onClose={closeSheet}
        onPick={openShare}
        onResume={resumeShare}
      />
    </>
  );
}
