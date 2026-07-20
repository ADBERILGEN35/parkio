import { useRef, useState } from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { PressableScale } from '@/components/ui/PressableScale';
import { prepareImage } from '@/features/share/prepareImage';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { useT } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';

interface Captured {
  uri: string;
  width?: number;
  height?: number;
}

/**
 * In-app camera (pen `uXZnx`): dark chrome, framing-guide corners, "show the
 * spot clearly" hint, shutter + torch + gallery shortcut; capture → preview
 * with retake/use.
 */
export default function ShareCameraScreen() {
  const t = useT();
  const router = useRouter();
  const toast = useToast();
  const insets = useSafeAreaInsets();
  const cameraRef = useRef<CameraView>(null);
  const [permission, requestPermission] = useCameraPermissions();
  const [torch, setTorch] = useState(false);
  const [captured, setCaptured] = useState<Captured | null>(null);
  const [busy, setBusy] = useState(false);

  const capture = async () => {
    if (busy) return;
    try {
      const photo = await cameraRef.current?.takePictureAsync({ quality: 0.9 });
      if (photo?.uri) {
        setCaptured({ uri: photo.uri, width: photo.width, height: photo.height });
      }
    } catch {
      toast.show(t('common.error.generic'), 'error');
    }
  };

  const pickFromGallery = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 1,
      exif: false,
    });
    const asset = result.assets?.[0];
    if (asset) {
      setCaptured({ uri: asset.uri, width: asset.width, height: asset.height });
    }
  };

  const usePhoto = async () => {
    if (!captured || busy) return;
    setBusy(true);
    try {
      const prepared = await prepareImage(captured);
      useShareDraftStore.getState().setPhoto(prepared);
      useShareDraftStore.getState().setStep('photo');
      router.back();
    } catch {
      toast.show(t('common.error.generic'), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (!permission) {
    return <View style={styles.root} />;
  }

  if (!permission.granted) {
    return (
      <SafeAreaView style={styles.root}>
        <View style={styles.permissionWrap}>
          <AppText variant="titleLg" color="#E7ECF7" align="center">
            {t('share.camera.permissionTitle')}
          </AppText>
          <AppText variant="bodyMd" color="#A7B0C4" align="center">
            {t('share.camera.permissionBody')}
          </AppText>
          <Button
            label={t('common.allow')}
            onPress={() => {
              void requestPermission();
            }}
          />
          <Button label={t('share.source.gallery')} variant="ghost" onPress={pickFromGallery} />
          <Button label={t('common.back')} variant="ghost" onPress={() => router.back()} />
        </View>
      </SafeAreaView>
    );
  }

  return (
    <View style={styles.root}>
      {captured ? (
        <Image source={{ uri: captured.uri }} style={styles.preview} resizeMode="cover" />
      ) : (
        <CameraView ref={cameraRef} style={styles.preview} facing="back" enableTorch={torch} />
      )}

      <SafeAreaView style={styles.chrome} edges={['top', 'left', 'right']} pointerEvents="box-none">
        <View style={styles.topRow}>
          <IconButton
            icon="close"
            variant="glassless"
            accessibilityLabel={t('common.close')}
            onPress={() => router.back()}
            style={styles.darkButton}
          />
          {!captured && (
            <IconButton
              icon={torch ? 'flash' : 'flash-off'}
              variant="glassless"
              accessibilityLabel="flash"
              onPress={() => setTorch((value) => !value)}
              style={styles.darkButton}
            />
          )}
        </View>

        {!captured && (
          <>
            {/* Framing guide corners */}
            <View pointerEvents="none" style={styles.frameGuide}>
              <View style={[styles.corner, styles.cornerTL]} />
              <View style={[styles.corner, styles.cornerTR]} />
              <View style={[styles.corner, styles.cornerBL]} />
              <View style={[styles.corner, styles.cornerBR]} />
              <View style={styles.hintChip}>
                <AppText variant="bodySm" color="#FFFFFF">
                  {t('share.camera.hint')}
                </AppText>
              </View>
            </View>
          </>
        )}

        <View style={[styles.bottomBar, { paddingBottom: insets.bottom + 18 }]}>
          {captured ? (
            <View style={styles.previewActions}>
              <Button
                label={t('share.camera.retake')}
                variant="ghost"
                size="md"
                block={false}
                onPress={() => setCaptured(null)}
                disabled={busy}
              />
              <Button
                label={t('share.camera.use')}
                size="md"
                block={false}
                onPress={usePhoto}
                loading={busy}
              />
            </View>
          ) : (
            <View style={styles.captureRow}>
              <IconButton
                icon="image-multiple-outline"
                size={46}
                variant="glassless"
                accessibilityLabel={t('share.source.gallery')}
                onPress={pickFromGallery}
                style={styles.darkButton}
              />
              <PressableScale
                scaleTo={0.9}
                onPress={capture}
                accessibilityRole="button"
                accessibilityLabel={t('share.source.camera')}
                style={styles.shutterOuter}
              >
                <View style={styles.shutterInner} />
              </PressableScale>
              <View style={styles.captureSpacer} />
            </View>
          )}
        </View>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0B1626' },
  preview: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  chrome: { flex: 1, justifyContent: 'space-between' },
  topRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingTop: 6,
  },
  darkButton: { backgroundColor: 'rgba(11,22,38,0.55)' },
  frameGuide: {
    position: 'absolute',
    top: '18%',
    bottom: '26%',
    left: 26,
    right: 26,
    alignItems: 'center',
    justifyContent: 'flex-end',
  },
  corner: {
    position: 'absolute',
    width: 34,
    height: 34,
    borderColor: 'rgba(255,255,255,0.85)',
  },
  cornerTL: { top: 0, left: 0, borderTopWidth: 2.5, borderLeftWidth: 2.5, borderTopLeftRadius: 10 },
  cornerTR: { top: 0, right: 0, borderTopWidth: 2.5, borderRightWidth: 2.5, borderTopRightRadius: 10 },
  cornerBL: { bottom: 0, left: 0, borderBottomWidth: 2.5, borderLeftWidth: 2.5, borderBottomLeftRadius: 10 },
  cornerBR: { bottom: 0, right: 0, borderBottomWidth: 2.5, borderRightWidth: 2.5, borderBottomRightRadius: 10 },
  hintChip: {
    backgroundColor: 'rgba(11,22,38,0.65)',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 999,
    marginBottom: -16,
  },
  bottomBar: {
    backgroundColor: 'rgba(11,22,38,0.85)',
    paddingTop: 18,
    paddingHorizontal: 26,
  },
  captureRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  captureSpacer: { width: 46 },
  shutterOuter: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 4,
    borderColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
  },
  shutterInner: { width: 56, height: 56, borderRadius: 28, backgroundColor: '#FFFFFF' },
  previewActions: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  permissionWrap: { flex: 1, justifyContent: 'center', padding: 28, gap: 12 },
});
