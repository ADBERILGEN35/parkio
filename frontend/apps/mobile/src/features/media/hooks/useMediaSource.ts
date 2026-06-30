import { useCameraPermissions } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';
import { useCallback } from 'react';
import type { LocalAsset } from '../types';

function toLocalAsset(asset: ImagePicker.ImagePickerAsset): LocalAsset {
  return {
    uri: asset.uri,
    width: asset.width,
    height: asset.height,
    fileSize: asset.fileSize,
    mimeType: asset.mimeType,
  };
}

export interface UseMediaSource {
  cameraPermission: ReturnType<typeof useCameraPermissions>[0];
  requestCameraPermission: ReturnType<typeof useCameraPermissions>[1];
  libraryPermission: ReturnType<typeof ImagePicker.useMediaLibraryPermissions>[0];
  requestLibraryPermission: ReturnType<typeof ImagePicker.useMediaLibraryPermissions>[1];
  /** Opens the gallery; resolves to the picked asset, or null if cancelled. */
  pickFromLibrary: () => Promise<LocalAsset | null>;
}

/**
 * Owns the two permission flows the capture experience needs:
 * - camera (`expo-camera`) for the in-app {@link CameraCapture} view, and
 * - media library (`expo-image-picker`) for the gallery picker.
 *
 * Permission *prompts* are driven from the UI (so they fire from a user gesture,
 * as the platforms require); this hook centralises the status + request handles
 * and the gallery launch. The returned permission objects expose `granted` and
 * `canAskAgain` so the UI can route a permanently-denied user to Settings.
 */
export function useMediaSource(): UseMediaSource {
  const [cameraPermission, requestCameraPermission] = useCameraPermissions();
  const [libraryPermission, requestLibraryPermission] = ImagePicker.useMediaLibraryPermissions();

  const pickFromLibrary = useCallback(async (): Promise<LocalAsset | null> => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsMultipleSelection: false,
      // Full quality here — preparation does the resize/compress deterministically.
      quality: 1,
    });
    if (result.canceled || result.assets.length === 0) {
      return null;
    }
    return toLocalAsset(result.assets[0]);
  }, []);

  return {
    cameraPermission,
    requestCameraPermission,
    libraryPermission,
    requestLibraryPermission,
    pickFromLibrary,
  };
}
