import { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler } from 'react-native';
import { useFocusEffect, useNavigation, useRouter } from 'expo-router';
import { decideShareBack } from '@/features/share/shareWizardNavigation';
import { useShareSessionStore } from '@/features/share/shareSessionStore';
import { useShareSheetStore } from '@/features/share/shareSheetStore';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

interface Options {
  /** When true (success screen), system back may leave without cancel confirm. */
  allowSystemExit?: boolean;
}

/**
 * Single back-policy for the Share wizard host:
 * - later steps → previous step (no route pop)
 * - first step → cancel confirmation
 * - confirmed cancel → clear draft + dismiss once to session origin
 *
 * Wired to Android hardware Back (`BackHandler`, focus-scoped) and stack
 * dismiss (`beforeRemove`). Header back should call `handleShareBack` too.
 * Camera sub-route keeps default back (pop to photo step).
 */
export function useShareWizardBack({ allowSystemExit = false }: Options = {}) {
  const router = useRouter();
  const navigation = useNavigation();
  const step = useShareDraftStore((s) => s.step);
  const setStep = useShareDraftStore((s) => s.setStep);
  const cancelAndClear = useShareDraftStore((s) => s.cancelAndClear);

  const [cancelConfirmVisible, setCancelConfirmVisible] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const allowExitRef = useRef(false);
  const backLockRef = useRef(false);
  const cancelConfirmVisibleRef = useRef(false);
  const cancellingRef = useRef(false);

  const dismissCancelConfirm = useCallback(() => {
    if (cancellingRef.current) {
      return;
    }
    cancelConfirmVisibleRef.current = false;
    setCancelConfirmVisible(false);
  }, []);

  const handleShareBack = useCallback(() => {
    if (allowSystemExit || cancellingRef.current) {
      return;
    }
    if (backLockRef.current) {
      return;
    }
    backLockRef.current = true;
    try {
      if (cancelConfirmVisibleRef.current) {
        cancelConfirmVisibleRef.current = false;
        setCancelConfirmVisible(false);
        return;
      }
      const decision = decideShareBack(useShareDraftStore.getState().step);
      if (decision.type === 'step') {
        setStep(decision.step);
        return;
      }
      cancelConfirmVisibleRef.current = true;
      setCancelConfirmVisible(true);
    } finally {
      backLockRef.current = false;
    }
  }, [allowSystemExit, setStep]);

  const confirmCancelShare = useCallback(async () => {
    if (cancellingRef.current) {
      return;
    }
    cancellingRef.current = true;
    setCancelling(true);
    try {
      await cancelAndClear();
      useShareSheetStore.getState().close();
      const { returnTo } = useShareSessionStore.getState().end();
      allowExitRef.current = true;
      cancelConfirmVisibleRef.current = false;
      setCancelConfirmVisible(false);
      if (router.canDismiss()) {
        router.dismissAll();
      }
      router.replace(returnTo);
    } finally {
      cancellingRef.current = false;
      setCancelling(false);
    }
  }, [cancelAndClear, router]);

  const markExitAllowed = useCallback(() => {
    allowExitRef.current = true;
    useShareSessionStore.getState().end();
  }, []);

  // Only intercept hardware back while the wizard index is focused — not when
  // the nested camera route is on top.
  useFocusEffect(
    useCallback(() => {
      if (allowSystemExit) {
        return undefined;
      }
      const sub = BackHandler.addEventListener('hardwareBackPress', () => {
        handleShareBack();
        return true;
      });
      return () => sub.remove();
    }, [allowSystemExit, handleShareBack]),
  );

  useEffect(() => {
    if (allowSystemExit) {
      return;
    }
    const unsubscribe = navigation.addListener('beforeRemove', (event) => {
      if (allowExitRef.current) {
        return;
      }
      event.preventDefault();
      handleShareBack();
    });
    return unsubscribe;
  }, [allowSystemExit, handleShareBack, navigation]);

  return {
    step,
    cancelConfirmVisible,
    cancelling,
    handleShareBack,
    dismissCancelConfirm,
    confirmCancelShare,
    markExitAllowed,
  };
}