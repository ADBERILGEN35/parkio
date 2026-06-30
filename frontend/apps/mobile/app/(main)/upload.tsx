import { Stack, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { Linking, StyleSheet, View } from 'react-native';
import { AppText, Button, Screen, StateView } from '@/components/ui';
import { CameraCapture } from '@/features/media/components/CameraCapture';
import { ImagePreview } from '@/features/media/components/ImagePreview';
import { MediaSourceSheet } from '@/features/media/components/MediaSourceSheet';
import { UploadProgress } from '@/features/media/components/UploadProgress';
import { useMediaSource } from '@/features/media/hooks/useMediaSource';
import { useMediaUpload } from '@/features/media/hooks/useMediaUpload';
import { validateLocalAsset } from '@/features/media/lib/validation';
import type { LocalAsset, MediaSource } from '@/features/media/types';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';

type Step = 'source' | 'camera' | 'preview' | 'uploading' | 'complete';

interface PermissionError {
  title: string;
  description: string;
  canOpenSettings: boolean;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function UploadScreen() {
  const router = useRouter();
  const online = useOnlineStatus();
  const {
    cameraPermission,
    requestCameraPermission,
    libraryPermission,
    requestLibraryPermission,
    pickFromLibrary,
  } = useMediaSource();
  const upload = useMediaUpload();
  const [step, setStep] = useState<Step>('source');
  const [asset, setAsset] = useState<LocalAsset | null>(null);
  const [assetError, setAssetError] = useState<string | null>(null);
  const [permissionError, setPermissionError] = useState<PermissionError | null>(null);

  const acceptAsset = useCallback(
    (nextAsset: LocalAsset) => {
      const validation = validateLocalAsset(nextAsset);
      upload.reset();
      if (!validation.ok) {
        setAsset(null);
        setAssetError(validation.message ?? 'That photo cannot be uploaded.');
        setStep('source');
        return;
      }
      setAsset(nextAsset);
      setAssetError(null);
      setPermissionError(null);
      setStep('preview');
    },
    [upload],
  );

  const openCamera = useCallback(async () => {
    setAssetError(null);
    setPermissionError(null);
    const permission = cameraPermission?.granted ? cameraPermission : await requestCameraPermission();
    if (permission.granted) {
      setStep('camera');
      return;
    }
    setPermissionError({
      title: 'Camera access is off',
      description: 'Allow camera access to take a parking spot photo.',
      canOpenSettings: permission.canAskAgain === false,
    });
  }, [cameraPermission, requestCameraPermission]);

  const openLibrary = useCallback(async () => {
    setAssetError(null);
    setPermissionError(null);
    const permission = libraryPermission?.granted ? libraryPermission : await requestLibraryPermission();
    if (!permission.granted) {
      setPermissionError({
        title: 'Gallery access is off',
        description: 'Allow photo library access to choose an existing parking spot photo.',
        canOpenSettings: permission.canAskAgain === false,
      });
      return;
    }
    const picked = await pickFromLibrary();
    if (picked) {
      acceptAsset(picked);
    }
  }, [acceptAsset, libraryPermission, pickFromLibrary, requestLibraryPermission]);

  const handlePickSource = useCallback(
    (source: MediaSource) => {
      if (source === 'camera') {
        void openCamera();
      } else {
        void openLibrary();
      }
    },
    [openCamera, openLibrary],
  );

  const startUpload = useCallback(() => {
    if (!asset) return;
    setStep('uploading');
    upload.start(asset);
  }, [asset, upload]);

  const chooseAnother = useCallback(() => {
    upload.reset();
    setAsset(null);
    setAssetError(null);
    setPermissionError(null);
    setStep('source');
  }, [upload]);

  const retryUpload = useCallback(() => {
    setStep('uploading');
    upload.retry();
  }, [upload]);

  const cancelled = upload.phase === 'cancelled';
  const uploadedMedia = upload.phase === 'success' ? upload.response : undefined;

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: step !== 'camera',
          title: 'Share a spot',
        }}
      />
      {step === 'camera' ? (
        <CameraCapture
          onCapture={acceptAsset}
          onClose={() => {
            setStep(asset ? 'preview' : 'source');
          }}
        />
      ) : (
        <Screen scroll={false} contentStyle={styles.screen} testID="mobile-upload-screen">
          {uploadedMedia ? (
            <StateView
              glyph="✓"
              title="Photo uploaded"
              description={`Media is ready for Spot Creation. ${uploadedMedia.status} · ${formatBytes(
                uploadedMedia.fileSize,
              )}`}
              actionLabel="Done"
              onAction={() => router.back()}
            >
              <Button label="Upload another photo" variant="secondary" onPress={chooseAnother} />
            </StateView>
          ) : cancelled ? (
            <StateView
              glyph="!"
              title="Upload cancelled"
              description="The photo is still prepared on this screen. Retry when ready, or choose another image."
            >
              <View style={styles.stateActions}>
                <Button label="Retry upload" onPress={retryUpload} disabled={online === false} />
                <Button label="Choose another" variant="secondary" onPress={chooseAnother} />
              </View>
            </StateView>
          ) : step === 'uploading' ? (
            <View style={styles.uploadPane}>
              <AppText variant="heading">Uploading photo</AppText>
              <AppText variant="body" tone="muted">
                Keep Parkio open while the upload finishes. If the connection drops, retry uses the same prepared
                file and idempotency key.
              </AppText>
              <UploadProgress
                phase={upload.phase}
                progress={upload.progress}
                errorMessage={upload.error}
                offline={online === false}
                onCancel={upload.cancel}
                onRetry={retryUpload}
              />
              {online === false ? (
                <AppText variant="callout" tone="danger" accessibilityRole="alert">
                  You are offline. Upload will be ready to retry when the connection returns.
                </AppText>
              ) : null}
              <Button label="Choose another" variant="ghost" onPress={chooseAnother} />
            </View>
          ) : step === 'preview' && asset ? (
            <ImagePreview
              asset={asset}
              onRetake={openCamera}
              onChooseAnother={chooseAnother}
              onConfirm={startUpload}
              busy={upload.phase === 'preparing' || upload.phase === 'uploading'}
            />
          ) : (
            <View style={styles.sourcePane}>
              <MediaSourceSheet onPick={handlePickSource} />
              {assetError ? (
                <AppText variant="callout" tone="danger" accessibilityRole="alert">
                  {assetError}
                </AppText>
              ) : null}
              {permissionError ? (
                <View style={styles.permissionBox} accessibilityRole="alert">
                  <AppText variant="subtitle">{permissionError.title}</AppText>
                  <AppText variant="body" tone="muted">
                    {permissionError.description}
                  </AppText>
                  {permissionError.canOpenSettings ? (
                    <Button label="Open Settings" variant="secondary" onPress={() => void Linking.openSettings()} />
                  ) : null}
                </View>
              ) : null}
            </View>
          )}
        </Screen>
      )}
    </>
  );
}

const styles = StyleSheet.create({
  screen: { gap: 16 },
  sourcePane: { flex: 1, gap: 16 },
  uploadPane: { flex: 1, justifyContent: 'center', gap: 16 },
  permissionBox: { gap: 8 },
  stateActions: { alignSelf: 'stretch', gap: 10, marginTop: 16 },
});
