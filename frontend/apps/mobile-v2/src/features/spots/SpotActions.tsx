import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createIdempotencyKey } from '@parkio/api-client';
import type { ModerationReason, VerificationResult } from '@parkio/types';
import { MODERATION_REASONS, VERIFICATION_RESULTS } from '@parkio/types';
import { Button } from '@/components/ui/Button';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { IconButton } from '@/components/ui/IconButton';
import { OptionSheet } from '@/components/ui/OptionSheet';
import { Sheet } from '@/components/ui/Sheet';
import { TextArea } from '@/components/ui/TextArea';
import { parkingKeys, reportsKeys } from '@/data/keys';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { moderationApi, parkingApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';

export interface SpotActionsProps {
  spotId: string;
  /** peek = Doğrula + Park ettim side by side; bar = all three (detail/expanded). */
  variant: 'peek' | 'bar';
  /** Called after a state-changing action succeeds (close sheets, refresh). */
  onActionDone?: () => void;
}

const VERIFY_ICONS: Record<VerificationResult, React.ComponentProps<typeof IconButton>['icon']> = {
  AVAILABLE: 'check-circle-outline',
  FILLED: 'car-off',
  INVALID: 'close-circle-outline',
  ILLEGAL_OR_RISKY: 'alert-octagon-outline',
  WRONG_VEHICLE_SIZE: 'arrow-expand-horizontal',
};

/**
 * The three free spot actions (brief §2.1): Verify (result chooser), Claim
 * ("Park ettim" confirm), Report (reason + optional note). Owns its sheets and
 * mutations; invalidates nearby/spot caches on success.
 */
export function SpotActions({ spotId, variant, onActionDone }: SpotActionsProps) {
  const t = useT();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [verifyOpen, setVerifyOpen] = useState(false);
  const [claimOpen, setClaimOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [reportReason, setReportReason] = useState<ModerationReason | null>(null);
  const [reportNote, setReportNote] = useState('');

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: parkingKeys.nearbyRoot() });
    void queryClient.invalidateQueries({ queryKey: parkingKeys.spot(spotId) });
    void queryClient.invalidateQueries({ queryKey: parkingKeys.mySpots() });
  };

  const verifyMutation = useMutation({
    mutationFn: (result: VerificationResult) =>
      parkingApi.verifySpot(spotId, { result }, createIdempotencyKey()),
    onSuccess: () => {
      toast.show(t('spot.verify.success'), 'success');
      invalidate();
      setVerifyOpen(false);
      onActionDone?.();
    },
    onError: (error) => {
      setVerifyOpen(false);
      toast.show(describeApiError(error, t).message, 'error');
    },
  });

  const claimMutation = useMutation({
    mutationFn: () => parkingApi.claimSpot(spotId, createIdempotencyKey()),
    onSuccess: () => {
      toast.show(t('spot.claim.success'), 'success');
      invalidate();
      setClaimOpen(false);
      onActionDone?.();
    },
    onError: (error) => {
      setClaimOpen(false);
      toast.show(describeApiError(error, t).message, 'error');
    },
  });

  const reportMutation = useMutation({
    mutationFn: (input: { reason: ModerationReason; description?: string }) =>
      moderationApi.createReport({
        targetType: 'PARKING_SPOT',
        targetId: spotId,
        reason: input.reason,
        ...(input.description ? { description: input.description } : {}),
      }),
    onSuccess: () => {
      toast.show(t('spot.report.success'), 'success');
      void queryClient.invalidateQueries({ queryKey: reportsKeys.all });
      closeReport();
      onActionDone?.();
    },
    onError: (error) => {
      closeReport();
      toast.show(describeApiError(error, t).message, 'error');
    },
  });

  const closeReport = () => {
    setReportOpen(false);
    setReportReason(null);
    setReportNote('');
  };

  return (
    <>
      {variant === 'peek' ? (
        <View style={styles.peekRow}>
          <Button
            label={t('spot.verify')}
            size="md"
            onPress={() => setVerifyOpen(true)}
            style={styles.flex}
          />
          <Button
            label={t('spot.claim')}
            size="md"
            variant="tonal"
            onPress={() => setClaimOpen(true)}
            style={styles.flex}
          />
        </View>
      ) : (
        <View style={styles.barRow}>
          <Button
            label={t('spot.verify')}
            size="md"
            onPress={() => setVerifyOpen(true)}
            style={styles.flexGrow}
          />
          <Button
            label={t('spot.claim')}
            size="md"
            variant="tonal"
            onPress={() => setClaimOpen(true)}
            style={styles.flexGrow}
          />
          <IconButton
            icon="flag-outline"
            variant="destructiveGhost"
            accessibilityLabel={t('spot.report')}
            onPress={() => setReportOpen(true)}
          />
        </View>
      )}

      {/* Verify result chooser. */}
      <OptionSheet
        visible={verifyOpen}
        onClose={() => setVerifyOpen(false)}
        title={t('spot.verifySheet.title')}
        hint={t('spot.verifySheet.hint')}
        options={VERIFICATION_RESULTS.map((result) => ({
          value: result,
          label: t(`spot.verifyResult.${result}`),
          icon: VERIFY_ICONS[result],
          tone: result === 'ILLEGAL_OR_RISKY' ? ('danger' as const) : ('default' as const),
        }))}
        onSelect={(result) => verifyMutation.mutate(result)}
      />

      {/* Claim confirm. */}
      <ConfirmModal
        visible={claimOpen}
        title={t('spot.claim.confirmTitle')}
        body={t('spot.claim.confirmBody')}
        confirmLabel={t('spot.claim.confirmCta')}
        cancelLabel={t('common.cancel')}
        onConfirm={() => claimMutation.mutate()}
        onCancel={() => setClaimOpen(false)}
        loading={claimMutation.isPending}
      />

      {/* Report: reason list, then optional note. */}
      <OptionSheet
        visible={reportOpen && reportReason === null}
        onClose={closeReport}
        title={t('spot.report.title')}
        options={MODERATION_REASONS.map((reason) => ({
          value: reason,
          label: t(`report.${reason}`),
          tone:
            reason === 'ILLEGAL_OR_RISKY' || reason === 'ABUSE_REPORT'
              ? ('danger' as const)
              : ('default' as const),
        }))}
        onSelect={(reason) => setReportReason(reason)}
      />
      <Sheet
        visible={reportOpen && reportReason !== null}
        onClose={closeReport}
        title={reportReason ? t(`report.${reportReason}`) : t('spot.report.title')}
      >
        <TextArea
          label={t('spot.report.descriptionLabel')}
          placeholder={t('spot.report.descriptionPlaceholder')}
          value={reportNote}
          onChangeText={setReportNote}
          maxLength={2000}
          minHeight={88}
        />
        <Button
          label={t('spot.report.submit')}
          variant="destructive"
          loading={reportMutation.isPending}
          onPress={() =>
            reportReason &&
            reportMutation.mutate({ reason: reportReason, description: reportNote.trim() || undefined })
          }
        />
      </Sheet>
    </>
  );
}

const styles = StyleSheet.create({
  peekRow: { flexDirection: 'row', gap: 8 },
  barRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  flex: { flex: 1 },
  flexGrow: { flexGrow: 1, flexBasis: 0 },
});
