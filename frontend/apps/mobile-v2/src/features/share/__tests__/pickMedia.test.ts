jest.mock('expo-image-picker', () => ({
  getMediaLibraryPermissionsAsync: jest.fn(),
  requestMediaLibraryPermissionsAsync: jest.fn(),
  launchImageLibraryAsync: jest.fn(),
}));

import * as ImagePicker from 'expo-image-picker';
import { pickImageFromGallery } from '../pickMedia';

const getPerm = ImagePicker.getMediaLibraryPermissionsAsync as jest.Mock;
const requestPerm = ImagePicker.requestMediaLibraryPermissionsAsync as jest.Mock;
const launch = ImagePicker.launchImageLibraryAsync as jest.Mock;

describe('pickImageFromGallery', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('opens the gallery when permission is already granted', async () => {
    getPerm.mockResolvedValue({ granted: true, canAskAgain: true });
    launch.mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://a.jpg', width: 100, height: 100 }],
    });

    await expect(pickImageFromGallery()).resolves.toEqual({
      status: 'picked',
      asset: { uri: 'file://a.jpg', width: 100, height: 100 },
    });
    expect(launch).toHaveBeenCalledTimes(1);
  });

  it('requests permission then opens the gallery', async () => {
    getPerm.mockResolvedValue({ granted: false, canAskAgain: true });
    requestPerm.mockResolvedValue({ granted: true, canAskAgain: true });
    launch.mockResolvedValue({ canceled: true, assets: [] });

    await expect(pickImageFromGallery()).resolves.toEqual({ status: 'cancelled' });
    expect(requestPerm).toHaveBeenCalled();
  });

  it('returns permission_denied without launching the picker', async () => {
    getPerm.mockResolvedValue({ granted: false, canAskAgain: false });
    requestPerm.mockResolvedValue({ granted: false, canAskAgain: false });

    await expect(pickImageFromGallery()).resolves.toEqual({
      status: 'permission_denied',
      canAskAgain: false,
    });
    expect(launch).not.toHaveBeenCalled();
  });

  it('returns cancelled when the user dismisses the picker', async () => {
    getPerm.mockResolvedValue({ granted: true, canAskAgain: true });
    launch.mockResolvedValue({ canceled: true, assets: [] });

    await expect(pickImageFromGallery()).resolves.toEqual({ status: 'cancelled' });
  });

  it('surfaces picker failures as error results', async () => {
    getPerm.mockResolvedValue({ granted: true, canAskAgain: true });
    launch.mockRejectedValue(new Error('picker boom'));

    await expect(pickImageFromGallery()).resolves.toEqual({
      status: 'error',
      message: 'picker boom',
    });
  });
});
