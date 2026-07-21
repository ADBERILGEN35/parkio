import * as ImagePicker from 'expo-image-picker';
import { Linking, Platform } from 'react-native';

export type MediaPickResult =
  | { status: 'picked'; asset: ImagePicker.ImagePickerAsset }
  | { status: 'cancelled' }
  | { status: 'permission_denied'; canAskAgain: boolean }
  | { status: 'error'; message: string };

/**
 * Request gallery/media permission then open the system image library.
 * Camera capture uses the in-app CameraView route; gallery uses this helper.
 */
export async function pickImageFromGallery(): Promise<MediaPickResult> {
  try {
    console.info('[pickMedia] gallery permission request started');
    const current = await ImagePicker.getMediaLibraryPermissionsAsync();
    let granted = current.granted;
    let canAskAgain = current.canAskAgain;

    if (!granted) {
      const requested = await ImagePicker.requestMediaLibraryPermissionsAsync();
      granted = requested.granted;
      canAskAgain = requested.canAskAgain;
    }

    console.info(`[pickMedia] gallery permission result=${granted ? 'granted' : 'denied'}`);

    if (!granted) {
      return { status: 'permission_denied', canAskAgain };
    }

    console.info('[pickMedia] launchImageLibraryAsync called');
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 1,
      exif: false,
    });

    if (result.canceled || !result.assets?.[0]) {
      console.info('[pickMedia] launchImageLibraryAsync result=cancelled');
      return { status: 'cancelled' };
    }

    console.info('[pickMedia] launchImageLibraryAsync result=success');
    return { status: 'picked', asset: result.assets[0] };
  } catch (error) {
    const message = error instanceof Error ? error.message : 'gallery_failed';
    console.info('[pickMedia] launchImageLibraryAsync result=error');
    console.warn('[share] gallery pick failed', Platform.OS, message);
    return { status: 'error', message };
  }
}

/** Optional camera picker path for diagnostics (share flow prefers CameraView). */
export async function pickImageFromCamera(): Promise<MediaPickResult> {
  try {
    console.info('[pickMedia] camera permission request started');
    const current = await ImagePicker.getCameraPermissionsAsync();
    let granted = current.granted;
    let canAskAgain = current.canAskAgain;

    if (!granted) {
      const requested = await ImagePicker.requestCameraPermissionsAsync();
      granted = requested.granted;
      canAskAgain = requested.canAskAgain;
    }

    console.info(`[pickMedia] camera permission result=${granted ? 'granted' : 'denied'}`);

    if (!granted) {
      return { status: 'permission_denied', canAskAgain };
    }

    console.info('[pickMedia] launchCameraAsync called');
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ['images'],
      quality: 1,
      exif: false,
    });

    if (result.canceled || !result.assets?.[0]) {
      console.info('[pickMedia] launchCameraAsync result=cancelled');
      return { status: 'cancelled' };
    }

    console.info('[pickMedia] launchCameraAsync result=success');
    return { status: 'picked', asset: result.assets[0] };
  } catch (error) {
    const message = error instanceof Error ? error.message : 'camera_failed';
    console.info('[pickMedia] launchCameraAsync result=error');
    console.warn('[share] camera pick failed', Platform.OS, message);
    return { status: 'error', message };
  }
}

export async function openAppSettings(): Promise<void> {
  try {
    await Linking.openSettings();
  } catch (error) {
    console.warn('[share] openSettings failed', error);
  }
}
