import { Stack, useNavigation, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button, Screen, StateView } from '@/components/ui';
import { WizardStepIndicator } from '@/features/spot-create/components/WizardStepIndicator';
import { useCreateSpotSubmit } from '@/features/spot-create/hooks/useCreateSpotSubmit';
import { isGpsAccuracyAcceptable } from '@/features/spot-create/lib/locationAccuracy';
import {
  nextWizardStep,
  prevWizardStep,
  type SpotCreationWizardStep,
} from '@/features/spot-create/lib/wizardSteps';
import { DetailsStep } from '@/features/spot-create/steps/DetailsStep';
import { LocationStep } from '@/features/spot-create/steps/LocationStep';
import { SummaryStep } from '@/features/spot-create/steps/SummaryStep';
import { useSpotCreationDraftStore } from '@/features/spot-create/state/spotCreationDraftStore';
import { useUnsavedChangesGuard } from '@/hooks/useUnsavedChangesGuard';
import { useLocale } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme';

/**
 * Spot Creation wizard orchestrator (steps after photo upload).
 * Photo capture stays on `upload.tsx` as step 1; this screen owns location → details → summary.
 */
export default function SpotCreateScreen() {
  const router = useRouter();
  const navigation = useNavigation();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { t } = useLocale();
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const patchDraft = useSpotCreationDraftStore((state) => state.patchDraft);
  const clearDraft = useSpotCreationDraftStore((state) => state.clearDraft);
  const submit = useCreateSpotSubmit();
  const [mapInteracting, setMapInteracting] = useState(false);
  const bypassWizardBackRef = useRef(false);

  const wizardStep: SpotCreationWizardStep = draft?.wizardStep ?? 'location';
  const missingMedia = Boolean(draft) && !draft?.media?.mediaId;

  // Leave-flow dialog only on location (or when somehow on photo/missing media).
  const allowNextNavigation = useUnsavedChangesGuard(
    Boolean(draft) && !submit.isSuccess && (wizardStep === 'location' || missingMedia),
  );

  const allowLeaveWizard = useCallback(() => {
    bypassWizardBackRef.current = true;
    allowNextNavigation();
  }, [allowNextNavigation]);

  // Within-wizard back: details/summary decrement step instead of leaving the flow.
  useEffect(() => {
    if (submit.isSuccess || !draft || missingMedia) return;
    if (wizardStep !== 'details' && wizardStep !== 'summary') return;

    return navigation.addListener('beforeRemove', (event) => {
      if (bypassWizardBackRef.current) return;
      const previous = prevWizardStep(wizardStep);
      if (!previous) return;
      event.preventDefault();
      patchDraft({ wizardStep: previous });
    });
  }, [draft, missingMedia, navigation, patchDraft, submit.isSuccess, wizardStep]);

  useEffect(() => {
    if (!missingMedia) return;
    allowLeaveWizard();
    router.replace('/(main)/upload');
  }, [allowLeaveWizard, missingMedia, router]);

  if (submit.isSuccess && submit.data) {
    return (
      <>
        <Stack.Screen options={{ title: t('Spot created') }} />
        <Screen>
          <StateView
            icon="checkmark-circle-outline"
            title={t('Spot submitted')}
            description={t('Your photo is being checked. You can track status in My spots. It appears on the map only after validation passes.')}
            actionLabel={t('View map')}
            onAction={() => {
              allowLeaveWizard();
              router.replace(`/(main)/spots/${submit.data.id}`);
            }}
          >
            <Button
              label={t('Share another spot')}
              variant="secondary"
              onPress={() => {
                allowLeaveWizard();
                router.replace('/(main)/upload');
              }}
            />
          </StateView>
        </Screen>
      </>
    );
  }

  if (!draft) {
    return (
      <>
        <Stack.Screen options={{ title: t('Share spot') }} />
        <Screen>
          <StateView
            icon="image-outline"
            title={t('Upload a photo first')}
            description={t('Spot Creation starts after a successful parking photo upload.')}
            actionLabel={t('Upload photo')}
            onAction={() => router.replace('/(main)/upload')}
          />
        </Screen>
      </>
    );
  }

  if (missingMedia) {
    return null;
  }

  const locationReady =
    draft.location && (draft.manualLocationEdited || isGpsAccuracyAcceptable(draft.gpsAccuracyMeters));
  const canGoNextFromLocation = Boolean(locationReady);
  const canSubmit = Boolean(locationReady) && !submit.isPending;
  const nextStep = nextWizardStep(wizardStep);

  const goNext = () => {
    if (!nextStep) return;
    if (wizardStep === 'location' && !canGoNextFromLocation) return;
    patchDraft({ wizardStep: nextStep });
  };

  const discardDraft = () => {
    clearDraft();
    allowLeaveWizard();
    router.replace('/(main)/upload');
  };

  const editPhoto = () => {
    allowLeaveWizard();
    router.replace('/(main)/upload');
  };

  return (
    <>
      <Stack.Screen
        options={{
          title: t('Share spot'),
          headerBackTitle: t('Photo'),
        }}
      />
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Screen
          contentStyle={styles.content}
          scrollEnabled={!mapInteracting}
          testID="spot-create-screen"
        >
          <WizardStepIndicator step={wizardStep} />

          {wizardStep === 'location' ? (
            <LocationStep onMapInteractingChange={setMapInteracting} />
          ) : null}
          {wizardStep === 'details' ? <DetailsStep /> : null}
          {wizardStep === 'summary' ? (
            <SummaryStep
              onEditPhoto={editPhoto}
              onEditStep={(step) => patchDraft({ wizardStep: step })}
              onShare={() => submit.mutate()}
              canShare={canSubmit}
              isSharing={submit.isPending}
              errorMessage={submit.errorMessage}
            />
          ) : null}
        </Screen>

        <View
          style={[
            styles.footer,
            {
              backgroundColor: theme.colors.surface,
              borderTopColor: theme.colors.border,
              paddingBottom: Math.max(insets.bottom, 12),
              paddingHorizontal: theme.spacing.gutter,
            },
          ]}
        >
          {wizardStep === 'location' || wizardStep === 'details' ? (
            <Button
              label={t('Next')}
              onPress={goNext}
              disabled={wizardStep === 'location' ? !canGoNextFromLocation : false}
            />
          ) : null}
          <Button
            label={t('Discard draft')}
            variant="ghost"
            onPress={discardDraft}
            disabled={submit.isPending}
          />
        </View>
      </KeyboardAvoidingView>
    </>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  content: { gap: 16, paddingBottom: 24 },
  footer: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 12,
    gap: 8,
  },
});
