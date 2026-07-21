import { useEffect, useRef, useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  View,
} from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { Spot } from '@parkio/types';
import { isValidClaimedRegion } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { IconButton } from '@/components/ui/IconButton';
import { WizardProgress } from '@/components/ui/WizardProgress';
import { DetailsStep } from '@/features/share/steps/DetailsStep';
import { LocationStep } from '@/features/share/steps/LocationStep';
import { PhotoStep } from '@/features/share/steps/PhotoStep';
import { ReviewStep } from '@/features/share/steps/ReviewStep';
import { SuccessStep } from '@/features/share/steps/SuccessStep';
import { openAppSettings, pickImageFromGallery } from '@/features/share/pickMedia';
import { draftPhotoExists, prepareImage } from '@/features/share/prepareImage';
import { SHARE_STEPS, useShareDraftStore, type ShareStep } from '@/features/share/state/shareDraftStore';
import { useShareWizardBack } from '@/features/share/useShareWizardBack';
import { useCreateSpot } from '@/features/share/useCreateSpot';
import { useDraftUpload } from '@/features/share/useDraftUpload';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

/** The camera-first share wizard host (brief §12.5 — "the money flow"). */
export default function ShareWizardScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const toast = useToast();
  const params = useLocalSearchParams<{ source?: string }>();
  const { colors } = theme;

  const step = useShareDraftStore((s) => s.step);
  const photo = useShareDraftStore((s) => s.photo);
  const location = useShareDraftStore((s) => s.location);
  const legalStatus = useShareDraftStore((s) => s.legalStatus);
  const vehicleTypes = useShareDraftStore((s) => s.vehicleTypes);
  const mediaId = useShareDraftStore((s) => s.mediaId);
  const uploadPhase = useShareDraftStore((s) => s.uploadPhase);
  const setStep = useShareDraftStore((s) => s.setStep);

  const upload = useDraftUpload();
  const { publish, phase: publishPhase } = useCreateSpot();
  const [published, setPublished] = useState<Spot | null>(null);
  const bootstrapped = useRef(false);

  const {
    cancelConfirmVisible,
    cancelling,
    handleShareBack,
    dismissCancelConfirm,
    confirmCancelShare,
    markExitAllowed,
  } = useShareWizardBack({ allowSystemExit: Boolean(published) });

  const pickFromGallery = async () => {
    console.info('[ShareSheet] share wizard gallery bootstrap');
    const generation = useShareDraftStore.getState().generation;
    const result = await pickImageFromGallery();
    if (!useShareDraftStore.getState().isGenerationCurrent(generation)) {
      return;
    }
    if (result.status === 'cancelled') {
      return;
    }
    if (result.status === 'permission_denied') {
      toast.show(t('share.gallery.permissionDenied'), 'error');
      if (!result.canAskAgain) {
        void openAppSettings();
      }
      return;
    }
    if (result.status === 'error') {
      toast.show(t('common.error.generic'), 'error');
      return;
    }
    try {
      const prepared = await prepareImage(result.asset);
      if (!useShareDraftStore.getState().isGenerationCurrent(generation)) {
        return;
      }
      useShareDraftStore.getState().setPhoto(prepared);
    } catch (error) {
      console.warn('[share] prepare gallery image failed', error);
      toast.show(t('common.error.generic'), 'error');
    }
  };

  // Entry source: jump straight into capture when arriving without a photo.
  useEffect(() => {
    if (bootstrapped.current) {
      return;
    }
    bootstrapped.current = true;
    const state = useShareDraftStore.getState();
    if (state.photo) {
      if (!draftPhotoExists(state.photo.uri)) {
        state.clearPhoto();
        state.setStep('photo');
        toast.show(t('share.draft.photoMissing'), 'error');
        if (params.source === 'gallery') {
          void pickFromGallery();
        } else if (params.source === 'camera') {
          router.push('/(main)/share/camera');
        }
      }
      return; // resuming a draft with a valid photo
    }
    state.setStep('photo');
    if (params.source === 'gallery') {
      void pickFromGallery();
    } else if (params.source === 'camera') {
      router.push('/(main)/share/camera');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stepIndex = SHARE_STEPS.indexOf(step);
  const stepLabels = [
    t('share.step.photo'),
    t('share.step.location'),
    t('share.step.details'),
    t('share.step.review'),
  ];

  const canContinue = (() => {
    switch (step) {
      case 'photo':
        return photo !== null && isValidClaimedRegion(photo.claimedRegion) && uploadPhase !== 'failed';
      case 'location':
        return location !== null;
      case 'details':
        return legalStatus !== null && legalStatus !== 'ILLEGAL_OR_RISKY' && vehicleTypes.length > 0;
      case 'review':
        return mediaId !== null;
    }
  })();

  const goNext = async () => {
    if (step === 'review') {
      try {
        const spot = await publish();
        setPublished(spot);
      } catch (error) {
        if (error instanceof Error && error.message === 'draft-incomplete') {
          toast.show(t('share.mediaNotReady'), 'error');
        } else {
          toast.show(describeApiError(error, t).message, 'error');
        }
      }
      return;
    }
    setStep(SHARE_STEPS[stepIndex + 1] as ShareStep);
  };

  if (published) {
    return (
      <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
        <View style={styles.successWrap}>
          <SuccessStep
            spot={published}
            onGoMySpots={() => {
              markExitAllowed();
              router.dismissAll();
              router.replace('/(main)/(tabs)/my-spots');
            }}
            onBackToMap={() => {
              markExitAllowed();
              router.dismissAll();
              router.replace('/(main)/(tabs)/map');
            }}
          />
        </View>
      </SafeAreaView>
    );
  }

  const publishLabel =
    publishPhase === 'waitingMedia'
      ? t('share.upload.scanning')
      : publishPhase === 'publishing'
        ? t('share.publishing')
        : t('share.publish');

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View style={styles.header}>
        <IconButton
          icon={stepIndex === 0 ? 'close' : 'arrow-left'}
          size={40}
          variant="glassless"
          accessibilityLabel={stepIndex === 0 ? t('common.close') : t('common.back')}
          onPress={handleShareBack}
        />
        <AppText variant="titleLg" style={styles.headerTitle}>
          {t('share.title')}
        </AppText>
        <AppText variant="bodySm" tabular color={colors.onSurfaceVariant}>
          {t('share.stepCount', { step: stepIndex + 1, total: SHARE_STEPS.length })}
        </AppText>
      </View>
      <View style={styles.progress}>
        <WizardProgress steps={stepLabels} activeIndex={stepIndex} />
      </View>

      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView
          contentContainerStyle={styles.scroll}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          {step === 'photo' && (
            <PhotoStep
              onRetake={() => router.push('/(main)/share/camera')}
              onPickGallery={() => void pickFromGallery()}
              onCancelUpload={upload.cancel}
              onRetryUpload={upload.retry}
            />
          )}
          {step === 'location' && <LocationStep />}
          {step === 'details' && <DetailsStep onEditLocation={() => setStep('location')} />}
          {step === 'review' && <ReviewStep onRetryUpload={upload.retry} />}
        </ScrollView>

        <View style={styles.footer}>
          <Button
            label={step === 'review' ? publishLabel : t('common.continue')}
            onPress={() => void goNext()}
            disabled={!canContinue}
            loading={publishPhase !== 'idle'}
          />
        </View>
      </KeyboardAvoidingView>

      <ConfirmModal
        visible={cancelConfirmVisible}
        title={t('share.leave.title')}
        body={t('share.leave.body')}
        confirmLabel={t('share.leave.leave')}
        cancelLabel={t('share.leave.stay')}
        confirmVariant="destructive"
        loading={cancelling}
        onConfirm={() => void confirmCancelShare()}
        onCancel={dismissCancelConfirm}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  flex: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 4,
    gap: 8,
  },
  headerTitle: { flex: 1, textAlign: 'center' },
  progress: { paddingHorizontal: 20, paddingBottom: 10 },
  scroll: { padding: 20, paddingTop: 6, paddingBottom: 24 },
  footer: { paddingHorizontal: 20, paddingBottom: 12, paddingTop: 6 },
  successWrap: { flex: 1, padding: 20 },
});
