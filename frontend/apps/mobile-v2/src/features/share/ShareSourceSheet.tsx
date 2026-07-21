import { useEffect, useId } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Sheet } from '@/components/ui/Sheet';
import { useT } from '@/i18n/LocaleProvider';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { useShareSheetStore } from '@/features/share/shareSheetStore';
import { useToast } from '@/providers/ToastProvider';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export type ShareSource = 'camera' | 'gallery';

export interface ShareSourceSheetProps {
  visible: boolean;
  onClose: () => void;
  onPick: (source: ShareSource) => void;
  onResume: () => void;
}

let shareSourceSheetMountCount = 0;

/**
 * "Yer paylaş" source sheet. Diagnostic build uses RN Pressable (not
 * PressableScale) for Camera/Gallery so Modal hit-testing can be isolated.
 */
export function ShareSourceSheet({ visible, onClose, onPick, onResume }: ShareSourceSheetProps) {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const entry = useShareSheetStore((s) => s.entry);
  const resumable = useShareDraftStore((s) => s.resumableDraft);
  const hasContent = useShareDraftStore((s) => Boolean(s.photo || s.description || s.addressText));
  const reset = useShareDraftStore((s) => s.reset);
  const instanceId = useId();

  const showResume = Boolean(resumable && hasContent);
  const disabled = false;
  const loading = false;

  useEffect(() => {
    shareSourceSheetMountCount += 1;
    console.info(
      `[ShareSheet] ShareSourceSheet mounted id=${instanceId} count=${shareSourceSheetMountCount}`,
    );
    return () => {
      shareSourceSheetMountCount = Math.max(0, shareSourceSheetMountCount - 1);
      console.info(
        `[ShareSheet] ShareSourceSheet unmounted id=${instanceId} count=${shareSourceSheetMountCount}`,
      );
    };
  }, [instanceId]);

  useEffect(() => {
    if (!visible) {
      return;
    }
    console.info(
      `[ShareSheet] opened entry=${entry} draft=${showResume} mounts=${shareSourceSheetMountCount}`,
    );
    console.info(
      `[ShareSheet] action disabled=${disabled} loading=${loading}`,
    );
    if (showResume) {
      console.info('[ShareSheet] draft mode rendered');
    } else {
      console.info('[ShareSheet] source mode rendered');
    }
  }, [visible, entry, showResume, disabled, loading]);

  const discardDraft = () => {
    console.info('[ShareSheet] delete button press received');
    console.info(`[ShareSheet] action disabled=${disabled} loading=${loading}`);
    try {
      reset();
      toast.show(t('share.draft.discarded'), 'success');
    } catch (error) {
      console.warn('[share] discard draft failed', error);
      toast.show(t('common.error.generic'), 'error');
    }
  };

  const handleCameraPress = () => {
    console.info('[ShareSheet] camera button press received');
    console.info(`[ShareSheet] action disabled=${disabled} loading=${loading}`);
    console.info('[ShareSourceSheet] camera handler entered');
    onPick('camera');
  };

  const handleGalleryPress = () => {
    console.info('[ShareSheet] gallery button press received');
    console.info(`[ShareSheet] action disabled=${disabled} loading=${loading}`);
    console.info('[ShareSourceSheet] gallery handler entered');
    onPick('gallery');
  };

  const handleContinuePress = () => {
    console.info('[ShareSheet] continue button press received');
    console.info(`[ShareSheet] action disabled=${disabled} loading=${loading}`);
    onResume();
  };

  const handleClose = () => {
    console.info('[ShareSheet] close requested');
    onClose();
  };

  return (
    <Sheet visible={visible} onClose={handleClose} title={t('share.title')}>
      {showResume ? (
        <View style={styles.resumeBlock} testID="share-resume-block">
          <AppText variant="titleMd">{t('share.draft.resumeTitle')}</AppText>
          <AppText variant="bodySm" color={theme.colors.onSurfaceVariant}>
            {t('share.draft.resumeBody')}
          </AppText>
          <ActionButton
            label={t('share.draft.resume')}
            variant="primary"
            onPress={handleContinuePress}
          />
          <ActionButton
            label={t('share.draft.discard')}
            variant="ghost"
            onPress={discardDraft}
          />
        </View>
      ) : (
        <View style={styles.actions} testID="share-source-actions">
          <ActionButton
            label={t('share.source.camera')}
            icon="camera-outline"
            variant="primary"
            onPress={handleCameraPress}
          />
          <ActionButton
            label={t('share.source.gallery')}
            icon="image-multiple-outline"
            variant="tonal"
            onPress={handleGalleryPress}
          />
          <View style={styles.footnote}>
            <MaterialCommunityIcons
              name="shield-check-outline"
              size={14}
              color={theme.colors.onSurfaceVariant}
            />
            <AppText variant="bodySm" color={theme.colors.onSurfaceVariant} style={styles.footnoteText}>
              {t('share.source.exifNote')}
            </AppText>
          </View>
        </View>
      )}
    </Sheet>
  );
}

type ActionVariant = 'primary' | 'tonal' | 'ghost';

function ActionButton({
  label,
  onPress,
  variant,
  icon,
}: {
  label: string;
  onPress: () => void;
  variant: ActionVariant;
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
}) {
  const theme = useTheme();
  const { colors } = theme;
  const palette = {
    primary: { bg: colors.primary, fg: colors.onPrimary },
    tonal: {
      bg: colors.primaryFixed,
      fg: theme.mode === 'dark' ? colors.primaryFixedDim : colors.primary,
    },
    ghost: {
      bg: 'transparent',
      fg: theme.mode === 'dark' ? colors.primaryFixedDim : colors.primary,
    },
  }[variant];

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={[
        styles.actionBtn,
        { backgroundColor: palette.bg, borderRadius: radius.pill },
      ]}
    >
      {icon ? <MaterialCommunityIcons name={icon} size={19} color={palette.fg} /> : null}
      <AppText variant="titleMd" color={palette.fg} numberOfLines={1}>
        {label}
      </AppText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  actions: { gap: 10, paddingTop: 4 },
  resumeBlock: { gap: 10, paddingTop: 4 },
  actionBtn: {
    minHeight: 52,
    paddingHorizontal: 22,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    alignSelf: 'stretch',
  },
  footnote: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingTop: 6,
    paddingBottom: 2,
  },
  footnoteText: { flexShrink: 1 },
});
